package pt.lisbora.pipeline.qa

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.pipeline.graph.ConflationResult
import pt.lisbora.pipeline.graph.ElevationStats
import pt.lisbora.pipeline.graph.EnrichmentStats
import pt.lisbora.pipeline.graph.SceneryStats
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.model.CycleInfraClass
import kotlin.math.abs

/** Everything the QA report shows about one `build`. */
data class QaInput(
    val dataVersion: String,
    val osmDate: String?,
    val cmlLastEdit: String?,
    val demSource: String,
    val graph: RoutingGraph,
    val droppedNodes: Int,
    val elevation: ElevationStats,
    val enrichment: EnrichmentStats,
    val scenery: SceneryStats,
    val conflation: ConflationResult,
    val minCmlCoverage: Double,
    val graphFailures: List<String>,
    val golden: List<GoldenResult>,
)

/** An edge whose elevation profile is suspiciously steep. */
data class SteepEdge(val edge: Int, val gradePercent: Double, val name: String?, val point: GeoPoint)

/** Writes `qa/report.md` (Portuguese, for people) and the GeoJSON layers it refers to. */
object QaReport {

    /** Grades above this (outside steps) are rare in Lisbon streets and usually mean a DEM artefact. */
    const val IMPLAUSIBLE_GRADE = 0.25
    const val STEEP_MIN_LENGTH_M = 20.0

    /** Rideable edges of at least [STEEP_MIN_LENGTH_M] with a segment steeper than [IMPLAUSIBLE_GRADE], steepest first. */
    fun steepEdges(graph: RoutingGraph): List<SteepEdge> = (0 until graph.edgeCount)
        .filter { e ->
            val flags = graph.flags(e)
            !flags.isSteps && graph.length(e) >= STEEP_MIN_LENGTH_M &&
                (flags.mode(false) == TravelMode.RIDE || flags.mode(true) == TravelMode.RIDE)
        }
        .mapNotNull { e ->
            val worst = (0 until graph.segmentCount(e)).maxOfOrNull { abs(graph.segmentGrade(e, it)) } ?: 0.0
            if (worst <= IMPLAUSIBLE_GRADE) return@mapNotNull null
            SteepEdge(e, worst * 100, graph.name(e), graph.point(e, graph.pointCount(e) / 2))
        }
        .sortedByDescending { it.gradePercent }

    /** Known places whose ground elevation is printed for a sanity check. */
    val ELEVATION_PROBES = listOf(
        "Praça do Comércio" to GeoPoint(38.7075, -9.1364),
        "Restauradores" to GeoPoint(38.7155, -9.1416),
        "Miradouro de São Pedro de Alcântara" to GeoPoint(38.7151, -9.1443),
        "Largo da Graça" to GeoPoint(38.7168, -9.1312),
        "Marquês de Pombal" to GeoPoint(38.7253, -9.1500),
        "Saldanha" to GeoPoint(38.7347, -9.1452),
        "Cais do Sodré" to GeoPoint(38.7061, -9.1446),
        "Belém (Padrão dos Descobrimentos)" to GeoPoint(38.6935, -9.2056),
    )

    fun markdown(input: QaInput): String = buildString {
        val g = input.graph
        appendLine("# Relatório QA do grafo ${input.dataVersion}")
        appendLine()
        appendLine("## Fontes")
        appendLine("- OpenStreetMap (Geofabrik, Portugal): dados de ${input.osmDate ?: "data desconhecida"}")
        appendLine("- Rede Ciclável da CML: última edição ${input.cmlLastEdit ?: "desconhecida"}")
        appendLine("- Elevação: ${input.demSource}")
        appendLine()
        appendLine("## Grafo")
        var rideKm = 0.0
        var pushKm = 0.0
        val infraKm = HashMap<EdgeInfra, Double>()
        val infraEdges = HashMap<EdgeInfra, Int>()
        for (e in 0 until g.edgeCount) {
            val flags = g.flags(e)
            val km = g.length(e) / 1000
            if (flags.mode(false) == TravelMode.RIDE || flags.mode(true) == TravelMode.RIDE) rideKm += km else pushKm += km
            infraKm.merge(flags.infra, km, Double::plus)
            infraEdges.merge(flags.infra, 1, Int::plus)
        }
        appendLine("| | |")
        appendLine("|---|---:|")
        appendLine("| Nós | ${g.nodeCount} |")
        appendLine("| Arestas | ${g.edgeCount} |")
        appendLine("| km pedaláveis (pelo menos num sentido) | %.0f |".format(rideKm))
        appendLine("| km só a empurrar (passeios, escadas…) | %.0f |".format(pushKm))
        appendLine("| Restrições de viragem | ${g.data.restrictionCount} |")
        appendLine("| Nós fora do maior componente (descartados) | ${input.droppedNodes} |")
        appendLine("| Arestas com elétrico | ${input.enrichment.tramEdges} |")
        appendLine("| Arestas com semáforo | ${input.enrichment.signalEdges} |")
        appendLine("| Junto ao rio / em parques e matas (km) | %.1f / %.1f |".format(input.scenery.riversideKm, input.scenery.greenKm))
        appendLine("| Arestas junto a miradouros | ${input.scenery.viewpointEdges} |")
        appendLine("| No concelho de Lisboa (km, onde a GIRA pode ir) | %.1f |".format(input.scenery.lisbonKm))
        appendLine("| Sentidos passados de \"empurrar\" a \"pedalar\" por estarem na rede da CML | ${input.enrichment.upgradedToRide} |")
        appendLine("| Elevação: arestas amostradas / pontos acrescentados | ${input.elevation.sampledEdges} / ${input.elevation.addedPoints} |")
        appendLine("| Elevação: pontes, túneis e barcos interpolados (arestas / nós) | ${input.elevation.interpolatedEdges} / ${input.elevation.interpolatedNodes} |")
        appendLine()
        appendLine("### Infraestrutura nas arestas")
        appendLine("| Classe | Arestas | km |")
        appendLine("|---|---:|---:|")
        EdgeInfra.entries.forEach { infra ->
            appendLine("| ${infra.name} | ${infraEdges[infra] ?: 0} | %.1f |".format(infraKm[infra] ?: 0.0))
        }
        appendLine()

        val c = input.conflation
        appendLine("## Conflação da rede da CML")
        appendLine("| Classe | km CML | km em arestas dessa classe | % |")
        appendLine("|---|---:|---:|---:|")
        CycleInfraClass.entries.forEach { cls ->
            val total = c.totalKmByClass[cls] ?: 0.0
            val matched = c.matchedKmByClass[cls] ?: 0.0
            appendLine("| ${cls.name} | %.1f | %.1f | %.0f%% |".format(total, matched, if (total > 0) matched / total * 100 else 0.0))
        }
        appendLine("| **Total** | %.1f | %.1f | **%.1f%%** |".format(c.totalKm, c.matchedKm, c.matchedShare * 100))
        appendLine()
        appendLine("Mínimo exigido: %.0f%%. Troços sem correspondência: ${c.unmatched.size} (`qa/cml-unmatched.geojson`).".format(input.minCmlCoverage * 100))
        appendLine("Contrassentidos da CML em vias que o OSM ainda marca de sentido único para bicicletas: " +
            "${c.contraflowConflicts.size} (`qa/contraflow.geojson`). Não são corrigidos automaticamente.")
        appendLine()

        val steep = steepEdges(g)
        appendLine("## Declives suspeitos")
        appendLine("Arestas pedaláveis com ${STEEP_MIN_LENGTH_M.toInt()} m ou mais e algum troço acima de %.0f%%: ${steep.size} (`qa/steep.geojson`). ".format(IMPLAUSIBLE_GRADE * 100) +
            "Acima deste valor costuma ser um artefacto do modelo de terreno, mas há ruas assim em Lisboa.")
        if (steep.isNotEmpty()) {
            appendLine()
            appendLine("| Rua | Declive | Posição |")
            appendLine("|---|---:|---|")
            steep.take(15).forEach { appendLine("| ${it.name ?: "—"} | %.0f%% | %.5f, %.5f |".format(it.gradePercent, it.point.lat, it.point.lon)) }
        }
        appendLine()

        appendLine("## Elevação em pontos de controlo")
        appendLine("| Local | Elevação no grafo (m) |")
        appendLine("|---|---:|")
        ELEVATION_PROBES.forEach { (name, point) ->
            val node = nearestNode(g, point)
            appendLine("| $name | ${node?.let { "%.1f".format(g.nodeElevation(it)) } ?: "—"} |")
        }
        appendLine()

        appendLine("## Rotas de referência")
        input.graphFailures.forEach { appendLine("- ❌ $it") }
        appendLine("| Rota | Resultado | km | min | Infra | Subida (m) | Incl. máx | Falhas |")
        appendLine("|---|---|---:|---:|---:|---:|---:|---|")
        input.golden.forEach { r ->
            val route = r.computed
            appendLine(
                "| ${r.route.name} | ${if (r.passed) "✅" else "❌"} | " +
                    (route?.let { "%.1f | %.1f | %.0f%% | %.0f | %.1f%%".format(it.distanceM / 1000, it.durationS / 60, it.infraShare * 100, it.ascentM, it.maxGradePercent) }
                        ?: "— | — | — | — | —") +
                    " | ${r.failures.joinToString("; ")} |",
            )
        }
    }

    fun unmatchedGeoJson(result: ConflationResult): JsonObject = featureCollection(
        result.unmatched.map { run ->
            lineFeature(run.points) {
                put("cml_id", run.id)
                put("class", run.cmlClass.id)
                put("name", run.name)
            }
        },
    )

    fun contraflowGeoJson(result: ConflationResult): JsonObject = featureCollection(
        result.contraflowConflicts.map { conflict ->
            buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("cml_id", conflict.cmlId)
                    put("name", conflict.name)
                    put("osm_way", "https://www.openstreetmap.org/way/${conflict.wayId}")
                }
                putJsonObject("geometry") {
                    put("type", "Point")
                    put("coordinates", coordinates(conflict.point))
                }
            }
        },
    )

    fun steepGeoJson(graph: RoutingGraph): JsonObject = featureCollection(
        steepEdges(graph).map { steep ->
            buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("name", steep.name)
                    put("grade_pct", Math.round(steep.gradePercent * 10) / 10.0)
                    put("road", graph.flags(steep.edge).road.name)
                }
                putJsonObject("geometry") {
                    put("type", "Point")
                    put("coordinates", coordinates(steep.point))
                }
            }
        },
    )

    /** Riverside and green edges (Descobrir), to check the scenic attributes on a map. */
    fun sceneryGeoJson(graph: RoutingGraph): JsonObject = featureCollection(
        (0 until graph.edgeCount).mapNotNull { e ->
            val flags = graph.flags(e)
            val kind = when {
                flags.riverside && flags.green -> "both"
                flags.riverside -> "riverside"
                flags.green -> "green"
                else -> return@mapNotNull null
            }
            lineFeature((0 until graph.pointCount(e)).map { graph.point(e, it) }) {
                put("kind", kind)
                put("name", graph.name(e))
                put("viewpoint", flags.viewpoint)
            }
        },
    )

    private fun nearestNode(graph: RoutingGraph, point: GeoPoint): Int? =
        (0 until graph.nodeCount).minByOrNull { graph.nodePoint(it).distanceTo(point) }
            ?.takeIf { graph.nodePoint(it).distanceTo(point) < 100 }

    private fun featureCollection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }

    private fun lineFeature(points: List<GeoPoint>, properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties", properties)
        putJsonObject("geometry") {
            put("type", "LineString")
            putJsonArray("coordinates") { points.forEach { add(coordinates(it)) } }
        }
    }

    private fun coordinates(point: GeoPoint) = JsonArray(listOf(JsonPrimitive(point.lon), JsonPrimitive(point.lat)))
}
