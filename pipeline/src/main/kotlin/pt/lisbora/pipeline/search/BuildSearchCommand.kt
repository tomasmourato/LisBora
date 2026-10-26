package pt.lisbora.pipeline.search

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.pipeline.datapack.PackManifest
import pt.lisbora.pipeline.datapack.PrettyJson
import pt.lisbora.pipeline.http.JdkHttpGet
import pt.lisbora.pipeline.osm.OsmExtract
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.ServiceArea
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.routing.search.SearchIndex
import pt.lisbora.routing.search.SearchIndexData
import pt.lisbora.routing.search.SearchIndexFormat
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class SearchOptions(
    val packDir: Path,
    val osmFile: Path,
    val refreshOsm: Boolean,
    val workDir: Path,
    val goldenFile: Path,
    /** Prints the OSM tags of places whose name contains this text. */
    val showName: String? = null,
)

/**
 * `search`: OSM → streets, places, points of interest and addresses of the region → `search.lbs`.
 * The index only reaches the data pack when every reference search finds its place.
 */
class BuildSearchCommand(
    private val http: JdkHttpGet,
    private val out: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
    private val bounds: GeoBounds = ServiceArea.BOUNDS,
) {

    fun run(options: SearchOptions): Int {
        val dataVersion = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC).format(clock.instant())
        val osm = OsmExtract(options.osmFile, http, out).ensure(options.refreshOsm)
        val places = OsmPlaceScan(osm, bounds, out, options.showName).scan()
        val (builder, stats) = PlaceIndexer(bounds).index(places)
        val data = builder.build(dataVersion)
        out.println(
            "Pesquisa: ${data.entryCount} entradas (${stats.streets} ruas, ${stats.pois} pontos de interesse, " +
                "${stats.addresses} moradas, ${stats.localities + stats.adminAreas} localidades), ${data.tokens.size} palavras",
        )

        val index = SearchIndex(data)
        val golden = GoldenSearchConfig.parse(options.goldenFile.readText()).run(index)
        golden.forEach { r ->
            val top = r.hits.take(3).joinToString(" | ") { h -> listOfNotNull(h.place.name, h.place.context).joinToString(", ") }
            out.println("${if (r.passed) "OK   " else "FALHA"} \"${r.search.query}\" → ${r.rank?.let { "#$it" } ?: "não encontrado"}: $top")
        }

        Files.createDirectories(options.workDir)
        writeIndex(data, options.workDir.resolve(CANDIDATE_FILE))
        val failed = golden.filterNot { it.passed }
        if (failed.isNotEmpty()) {
            out.println("ERRO: ${failed.size} pesquisas de referência falharam; o índice não foi escrito em ${options.packDir}.")
            return 3
        }

        PackManifest.prepare(options.packDir)
        val target = options.packDir.resolve(SearchIndexFormat.FILE_NAME)
        writeIndex(data, target)
        options.packDir.resolve(META_FILE).writeText(PrettyJson.encode(meta(data, places.dataDate)) + "\n")
        PackManifest.write(options.packDir)
        out.println("Escrito $target (%.1f MB)".format(target.fileSize() / 1_048_576.0))
        return 0
    }

    private fun writeIndex(data: SearchIndexData, target: Path) {
        val partial = target.resolveSibling(target.fileName.toString() + ".partial")
        partial.outputStream().buffered().use { SearchIndexFormat.write(data, it) }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun meta(data: SearchIndexData, osmDate: String?) = buildJsonObject {
        put("formatVersion", SearchIndexFormat.FORMAT_VERSION)
        put("dataVersion", data.dataVersion)
        put("bbox", bounds.toBboxString())
        put("license", "ODbL (base de dados derivada do OpenStreetMap)")
        put("attribution", OsmExtract.ATTRIBUTION)
        putJsonObject("sources") {
            putJsonObject("osm") {
                put("source", OsmExtract.DEFAULT_URL)
                put("dataDate", osmDate)
            }
        }
        putJsonObject("stats") {
            put("entries", data.entryCount)
            put("tokens", data.tokens.size)
            putJsonObject("byCategory") {
                PlaceCategory.entries.forEach { category ->
                    val count = data.categories.count { it.toInt() == category.code }
                    if (count > 0) put(category.name, count)
                }
            }
        }
    }

    companion object {
        const val META_FILE = "search.meta.json"
        const val CANDIDATE_FILE = "search-candidate.lbs"
    }
}
