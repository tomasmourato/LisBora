package pt.lisbora.pipeline.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.pipeline.cml.FetchCmlCommand
import pt.lisbora.pipeline.datapack.PackManifest
import pt.lisbora.pipeline.datapack.PrettyJson
import pt.lisbora.pipeline.dem.TerrariumDem
import pt.lisbora.pipeline.dem.TileSource
import pt.lisbora.pipeline.http.JdkHttpGet
import pt.lisbora.pipeline.osm.GraphHopperImport
import pt.lisbora.pipeline.osm.OsmExtract
import pt.lisbora.pipeline.osm.OsmFeatureScan
import pt.lisbora.pipeline.osm.OsmSceneryScan
import pt.lisbora.pipeline.qa.GoldenConfig
import pt.lisbora.pipeline.qa.GoldenRouteRunner
import pt.lisbora.pipeline.qa.QaInput
import pt.lisbora.pipeline.qa.QaReport
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.ServiceArea
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.GraphData
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class BuildOptions(
    val packDir: Path,
    val osmFile: Path,
    val refreshOsm: Boolean,
    /** The CML network the map shows, so map and router use the same version. */
    val cmlFile: Path,
    val demZoom: Int,
    val workDir: Path,
    val goldenFile: Path,
)

/**
 * `build`: OSM → GraphHopper import → region graph → elevation → CML conflation → attributes → `graph.lbg`,
 * then the QA report and the golden routes. The graph only reaches the data pack when QA passes.
 */
class BuildGraphCommand(
    private val http: JdkHttpGet,
    private val out: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
    private val bounds: GeoBounds = ServiceArea.BOUNDS,
) {

    fun run(options: BuildOptions): Int {
        if (!options.cmlFile.exists()) {
            out.println("ERRO: falta ${options.cmlFile}; corre primeiro fetch-cml.")
            return 2
        }
        val dataVersion = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC).format(clock.instant())
        val cmlCollection = Json.parseToJsonElement(options.cmlFile.readText()).jsonObject
        val cmlLines = CmlLine.fromFeatureCollection(cmlCollection)
        val cmlMetaFile = options.cmlFile.resolveSibling(FetchCmlCommand.META_FILE)
        val cmlLastEdit = if (cmlMetaFile.exists()) {
            Json.parseToJsonElement(cmlMetaFile.readText()).jsonObject["sourceLastEdit"]?.jsonPrimitive?.contentOrNull
        } else null

        val osm = OsmExtract(options.osmFile, http, out).ensure(options.refreshOsm)
        val hopper = GraphHopperImport(osm, options.workDir.resolve("gh"), out).importOrLoad()
        val osmDate = hopper.properties.get("datareader.data.date").ifBlank { null }
        val region = RegionExtractor(hopper, bounds).extract()
        hopper.close()
        out.println("Região: ${region.nodes.size} nós, ${region.edges.size} arestas, ${region.restrictions.size} restrições")
        val dropped = region.keepLargestComponent()
        out.println("Maior componente: ${region.nodes.size} nós ($dropped descartados)")

        val dem = TerrariumDem(httpTiles(), options.workDir.resolve("dem"), options.demZoom)
        dem.prefetch(bounds, out)
        val elevation = ElevationAssigner(dem).assign(region)
        out.println("Elevação: ${elevation.addedPoints} pontos acrescentados; ${elevation.interpolatedEdges} pontes/túneis interpolados")

        val features = OsmFeatureScan(osm, bounds, out).scan()
        val projection = LocalProjection(bounds)
        val conflation = CmlConflation(region, projection).run(cmlLines)
        out.println("CML: %.1f%% dos %.0f km associados".format(conflation.matchedShare * 100, conflation.totalKm))
        val enrichment = EdgeEnrichment(region, projection).apply(features)
        val scenery = SceneryEnrichment(region, projection).apply(OsmSceneryScan(osm, bounds, out).scan())
        out.println("Cénico: %.1f km junto ao rio, %.1f km em parques e matas, %d arestas junto a miradouros; %.1f km no concelho de Lisboa".format(
            scenery.riversideKm, scenery.greenKm, scenery.viewpointEdges, scenery.lisbonKm,
        ))

        val data = GraphExport.toGraphData(region, dataVersion, bounds)
        val graph = RoutingGraph(data)
        val golden = GoldenConfig.parse(options.goldenFile.readText())
        val runner = GoldenRouteRunner(graph)
        val graphFailures = runner.graphFailures(golden)
        val goldenResults = runner.run(golden)

        val qaDir = Files.createDirectories(options.workDir.resolve("qa"))
        // Always keep the candidate for inspection (golden-routes --graph), even when QA fails.
        writeGraph(data, options.workDir.resolve(CANDIDATE_FILE))
        val qa = QaInput(
            dataVersion = dataVersion,
            osmDate = osmDate,
            cmlLastEdit = cmlLastEdit,
            demSource = "Mapterhorn z${options.demZoom} (DGT LiDAR, Copernicus GLO-30)",
            graph = graph,
            droppedNodes = dropped,
            elevation = elevation,
            enrichment = enrichment,
            scenery = scenery,
            conflation = conflation,
            minCmlCoverage = MIN_CML_COVERAGE,
            graphFailures = graphFailures,
            golden = goldenResults,
        )
        qaDir.resolve("report.md").writeText(QaReport.markdown(qa))
        qaDir.resolve("cml-unmatched.geojson").writeText(PrettyJson.encodeCompact(QaReport.unmatchedGeoJson(conflation)))
        qaDir.resolve("contraflow.geojson").writeText(PrettyJson.encodeCompact(QaReport.contraflowGeoJson(conflation)))
        qaDir.resolve("steep.geojson").writeText(PrettyJson.encodeCompact(QaReport.steepGeoJson(graph)))
        qaDir.resolve("scenery.geojson").writeText(PrettyJson.encodeCompact(QaReport.sceneryGeoJson(graph)))
        qaDir.resolve("routes.geojson").writeText(PrettyJson.encodeCompact(GoldenRouteRunner.toGeoJson(goldenResults)))
        goldenResults.forEach { r ->
            val route = r.computed
            val summary = route?.let { "%.1f km, %.0f min, %.0f%% infra, +%.0f m".format(it.distanceM / 1000, it.durationS / 60, it.infraShare * 100, it.ascentM) } ?: "sem rota"
            out.println("${if (r.passed) "OK  " else "FALHA"} ${r.route.name}: $summary ${r.failures.joinToString("; ")}")
        }
        out.println("Relatório QA: ${qaDir.resolve("report.md")}")

        val problems = buildList {
            if (conflation.matchedShare < MIN_CML_COVERAGE) {
                add("Só %.1f%% da rede da CML foi associada ao grafo (mínimo %.0f%%).".format(conflation.matchedShare * 100, MIN_CML_COVERAGE * 100))
            }
            addAll(graphFailures)
            goldenResults.filterNot { it.passed }.forEach { add("Rota de referência falhou: ${it.route.name}") }
        }
        if (problems.isNotEmpty()) {
            problems.forEach { out.println("ERRO: $it") }
            out.println("O grafo não foi escrito em ${options.packDir}; o pacote anterior mantém-se.")
            return 3
        }

        PackManifest.prepare(options.packDir)
        val target = options.packDir.resolve(GraphFormat.FILE_NAME)
        writeGraph(data, target)
        options.packDir.resolve(META_FILE).writeText(
            PrettyJson.encode(meta(qa, osmDate, cmlLastEdit, PackManifest.sha256(options.cmlFile))) + "\n",
        )
        PackManifest.write(options.packDir)
        out.println("Escrito $target (%.1f MB)".format(target.fileSize() / 1_048_576.0))
        return 0
    }

    private fun writeGraph(data: GraphData, target: Path) {
        val partial = target.resolveSibling(target.fileName.toString() + ".partial")
        partial.outputStream().buffered().use { GraphFormat.write(data, it) }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun httpTiles() = TileSource { z, x, y ->
        http.getBytes(TerrariumDem.TILE_URL.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y"))
    }

    private fun meta(qa: QaInput, osmDate: String?, cmlLastEdit: String?, cmlSha256: String) = buildJsonObject {
        val g = qa.graph
        put("formatVersion", GraphFormat.FORMAT_VERSION)
        put("dataVersion", qa.dataVersion)
        put("bbox", bounds.toBboxString())
        put("license", "ODbL (base de dados derivada do OpenStreetMap)")
        put("attribution", ATTRIBUTION)
        putJsonObject("sources") {
            putJsonObject("osm") {
                put("source", OsmExtract.DEFAULT_URL)
                put("dataDate", osmDate)
                put("attribution", OsmExtract.ATTRIBUTION)
            }
            putJsonObject("cml") {
                put("file", FetchCmlCommand.GEOJSON_FILE)
                put("sha256", cmlSha256)
                put("sourceLastEdit", cmlLastEdit)
                put("attribution", FetchCmlCommand.ATTRIBUTION)
            }
            putJsonObject("elevation") {
                put("source", TerrariumDem.TILE_URL)
                put("attribution", TerrariumDem.ATTRIBUTION)
            }
        }
        putJsonObject("stats") {
            put("nodes", g.nodeCount)
            put("edges", g.edgeCount)
            put("turnRestrictions", g.data.restrictionCount)
            put("cmlMatchedShare", Math.round(qa.conflation.matchedShare * 1000) / 1000.0)
            put("riversideKm", Math.round(qa.scenery.riversideKm * 10) / 10.0)
            put("greenKm", Math.round(qa.scenery.greenKm * 10) / 10.0)
            put("viewpointEdges", qa.scenery.viewpointEdges)
            put("lisbonKm", Math.round(qa.scenery.lisbonKm * 10) / 10.0)
            putJsonObject("kmByInfra") {
                EdgeInfra.entries.forEach { infra ->
                    val km = (0 until g.edgeCount).filter { g.flags(it).infra == infra }.sumOf { g.length(it) } / 1000
                    put(infra.name, Math.round(km * 10) / 10.0)
                }
            }
        }
    }

    companion object {
        const val META_FILE = "graph.meta.json"
        const val CANDIDATE_FILE = "graph-candidate.lbg"

        /** Share of CML length that must land on edges of the same class; a drop means the data changed shape. */
        const val MIN_CML_COVERAGE = 0.85

        const val ATTRIBUTION = "${OsmExtract.ATTRIBUTION}; ${FetchCmlCommand.ATTRIBUTION}; ${TerrariumDem.ATTRIBUTION}"
    }
}
