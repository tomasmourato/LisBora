package pt.lisbora.pipeline.basemap

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pt.lisbora.pipeline.datapack.PackManifest
import pt.lisbora.pipeline.datapack.PrettyJson
import pt.lisbora.pipeline.http.JdkHttpGet
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.ServiceArea
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * `basemap`: cuts the service area out of a Protomaps daily planet build with the `pmtiles` CLI
 * (HTTP range requests, no full download) and writes `basemap.pmtiles` + `basemap.meta.json`.
 */
class BasemapCommand(
    private val pmtilesExecutable: String,
    private val out: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
    private val bounds: GeoBounds = ServiceArea.BOUNDS,
) {

    fun run(packDir: Path, buildDate: String?, maxZoom: Int): Int {
        val date = buildDate ?: findLatestBuild() ?: run {
            out.println("ERRO: não encontrei nenhum build Protomaps dos últimos $LOOKBACK_DAYS dias.")
            return 4
        }
        val sourceUrl = buildUrl(date)
        PackManifest.prepare(packDir)
        val target = packDir.resolve(FILE_NAME)
        val partial = packDir.resolve("$FILE_NAME.partial")
        partial.deleteIfExists()

        out.println("A extrair ${bounds.toBboxString()} (z0–$maxZoom) de $sourceUrl…")
        val process = ProcessBuilder(
            pmtilesExecutable, "extract", sourceUrl, partial.toString(),
            "--bbox=${bounds.toBboxString()}", "--maxzoom=$maxZoom",
        ).inheritIO().start()
        val exit = process.waitFor()
        if (exit != 0) {
            partial.deleteIfExists()
            out.println("ERRO: pmtiles terminou com o código $exit.")
            return 5
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)

        val meta = buildJsonObject {
            put("source", "Protomaps daily build")
            put("sourceUrl", sourceUrl)
            put("buildDate", date)
            put("bbox", bounds.toBboxString())
            put("maxZoom", maxZoom)
            put("license", "ODbL (Produced Work)")
            put("attribution", ATTRIBUTION)
            put("extractedAt", clock.instant().toString())
        }
        packDir.resolve(META_FILE_NAME).writeText(PrettyJson.encode(meta) + "\n")
        PackManifest.write(packDir)
        out.println("Escrito $target (%.1f MB)".format(target.fileSize() / 1_048_576.0))
        return 0
    }

    private fun findLatestBuild(): String? {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        val today = LocalDate.now(clock)
        return (0 until LOOKBACK_DAYS).asSequence()
            .map { today.minusDays(it.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE) }
            .firstOrNull { date ->
                val request = HttpRequest.newBuilder(URI(buildUrl(date)))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", JdkHttpGet.USER_AGENT)
                    .build()
                runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }
                    .getOrDefault(false)
            }
    }

    companion object {
        const val FILE_NAME = "basemap.pmtiles"
        const val META_FILE_NAME = "basemap.meta.json"
        const val DEFAULT_MAX_ZOOM = 15
        const val ATTRIBUTION = "Protomaps © OpenStreetMap"
        private const val LOOKBACK_DAYS = 7

        fun buildUrl(date: String) = "https://build.protomaps.com/$date.pmtiles"
    }
}
