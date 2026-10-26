package pt.lisbora.pipeline.gira

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.pipeline.datapack.PackManifest
import pt.lisbora.pipeline.datapack.PrettyJson
import pt.lisbora.pipeline.http.JdkHttpGet
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.geo.ServiceArea
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.roundToLong

/** A GIRA station as the data pack keeps it: where it is and how many docks it has, without live counts. */
data class GiraStation(val number: Int, val name: String, val place: String, val point: GeoPoint, val docks: Int?)

/** Where the station list comes from; returns the raw response. */
fun interface GiraSource {
    @Throws(IOException::class)
    fun fetch(): String
}

/**
 * The GIRA stations as the official app reads them from the VAIMOO platform's Firestore, with that app's
 * public key: the same source as the app's `VaimooGiraFeed` (see the exception in CLAUDE.md). EMEL has
 * not published this API; when it fails, `gira` writes nothing and the pack keeps its stations.
 */
class VaimooGiraSource(private val timeout: Duration = Duration.ofSeconds(30)) : GiraSource {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    override fun fetch(): String {
        val request = HttpRequest.newBuilder(URI(ENDPOINT))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("User-Agent", JdkHttpGet.USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(QUERY))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("GIRA stations: HTTP ${response.statusCode()}")
        return response.body()
    }

    private companion object {
        const val PROJECT = "vaimoorotterdam"
        const val API_KEY = "AIzaSyAmKfHdjYUhzYmg7qSZtRwwYE92HQQlmJ4"
        const val TENANT = "P1/EML/EML/"
        const val ENDPOINT = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents:runQuery?key=$API_KEY"
        const val QUERY = """{"structuredQuery":{"from":[{"collectionId":"docking-stations"}],""" +
            """"where":{"fieldFilter":{"field":{"fieldPath":"Tenant"},"op":"EQUAL","value":{"stringValue":"$TENANT"}}}}}"""
    }
}

/** Reads the Firestore `runQuery` response: the stations that exist, open or closed right now. */
object GiraFirestore {
    /** "253 - Avenida da Universidade Técnica / FMV" → 253 and the place. */
    private val NAME = Regex("""^\s*(\d+)\s*-\s*(.*)$""")

    /** Stations taken out of the system (not active) and virtual ones are left out; [skipped] gets their names. */
    fun parse(json: String, skipped: (String) -> Unit = {}): List<GiraStation> = Json.parseToJsonElement(json).jsonArray.mapNotNull { result ->
        val fields = result.jsonObject["document"]?.jsonObject?.get("fields")?.jsonObject ?: return@mapNotNull null
        val name = fields.value("Name", "stringValue")?.jsonPrimitive?.contentOrNull.orEmpty()
        val location = fields.value("Location", "geoPointValue")?.jsonObject
        val lat = location?.get("latitude")?.jsonPrimitive?.doubleOrNull
        val lon = location?.get("longitude")?.jsonPrimitive?.doubleOrNull
        val match = NAME.find(name)
        val active = fields.value("IsActive", "booleanValue")?.jsonPrimitive?.booleanOrNull == true &&
            fields.value("IsVirtual", "booleanValue")?.jsonPrimitive?.booleanOrNull != true
        if (lat == null || lon == null || match == null || !active) {
            skipped(name.ifBlank { "(sem nome)" })
            return@mapNotNull null
        }
        GiraStation(
            number = match.groupValues[1].toInt(),
            name = name.trim(),
            place = match.groupValues[2].trim(),
            point = GeoPoint(lat, lon),
            docks = fields.value("DockLimit", "integerValue")?.jsonPrimitive?.longOrNull?.toInt(),
        )
    }.sortedBy { it.number }

    private fun JsonObject.value(field: String, type: String) = this[field]?.jsonObject?.get(type)
}

/**
 * `gira`: the GIRA stations (number, name, place, docks) → `gira.geojson` + `gira.meta.json`, so the app
 * knows the current stations offline, between data updates. Only written when the list looks whole.
 */
class GiraCommand(
    private val source: GiraSource,
    private val out: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
    private val bounds: GeoBounds = ServiceArea.BOUNDS,
) {

    fun run(packDir: Path): Int {
        out.println("A descarregar as estações GIRA…")
        val skipped = mutableListOf<String>()
        val stations = try {
            GiraFirestore.parse(source.fetch()) { skipped += it }
        } catch (e: Exception) {
            out.println("ERRO: não foi possível ler as estações GIRA (${e.message}). Nada foi escrito.")
            return 4
        }
        out.println("Estações GIRA: ${stations.size} (${stations.sumOf { it.docks ?: 0 }} docas); deixadas de fora: ${skipped.size} ${skipped.take(5)}")

        val target = packDir.resolve(FILE_NAME)
        val previous = target.takeIf { it.exists() }?.let { runCatching { stationCount(it.readText()) }.getOrNull() }
        val problems = validate(stations, previous)
        if (problems.isNotEmpty()) {
            problems.forEach { out.println("ERRO: $it") }
            out.println("Nada foi escrito; o pacote anterior mantém-se.")
            return 3
        }

        PackManifest.prepare(packDir)
        target.writeText(PrettyJson.encodeCompact(geoJson(stations)))
        packDir.resolve(META_FILE).writeText(PrettyJson.encode(meta(stations)) + "\n")
        PackManifest.write(packDir)
        out.println("Escrito em $packDir: $FILE_NAME, $META_FILE, ${PackManifest.FILE_NAME}")
        return 0
    }

    fun validate(stations: List<GiraStation>, previousCount: Int?): List<String> = buildList {
        if (stations.size < MIN_STATIONS) add("Só ${stations.size} estações (mínimo $MIN_STATIONS).")
        if (previousCount != null && stations.size < previousCount * (1 - MAX_DROP)) {
            add("A lista caiu de $previousCount para ${stations.size} estações (mais de ${(MAX_DROP * 100).toInt()}%).")
        }
        val outside = stations.filterNot { it.point in bounds }
        if (outside.isNotEmpty()) add("${outside.size} estações fora da região: ${outside.take(3).map { it.name }}")
        val repeated = stations.groupBy { it.number }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) add("Números repetidos: ${repeated.sorted()}")
    }

    private fun geoJson(stations: List<GiraStation>) = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            stations.forEach { s ->
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") {
                            add(round(s.point.lon))
                            add(round(s.point.lat))
                        }
                    }
                    putJsonObject("properties") {
                        put("number", s.number)
                        put("name", s.name)
                        put("place", s.place)
                        s.docks?.let { put("docks", it) }
                    }
                }
            }
        }
    }

    private fun meta(stations: List<GiraStation>) = buildJsonObject {
        put("source", "GIRA — Bicicletas de Lisboa (EMEL), plataforma VAIMOO")
        put("attribution", ATTRIBUTION)
        put("fetchedAt", clock.instant().toString())
        put("stations", stations.size)
        put("docks", stations.sumOf { it.docks ?: 0 })
    }

    companion object {
        const val FILE_NAME = "gira.geojson"
        const val META_FILE = "gira.meta.json"
        const val ATTRIBUTION = "Estações GIRA: EMEL"
        const val MIN_STATIONS = 150

        /** A smaller list than this, against the pack's, is more likely a broken answer than closed stations. */
        const val MAX_DROP = 0.2

        private fun round(degrees: Double) = (degrees * 1e6).roundToLong() / 1e6

        fun stationCount(geoJson: String): Int = Json.parseToJsonElement(geoJson).jsonObject["features"]!!.jsonArray.size
    }
}
