package pt.lisbora.data.gira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import pt.lisbora.di.IoDispatcher
import pt.lisbora.routing.geo.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Where live GIRA station data comes from; returns the raw response. */
fun interface GiraFeed {
    @Throws(IOException::class)
    suspend fun fetch(): String
}

/**
 * The live state of the GIRA stations, as the official app reads it since GIRA moved to the VAIMOO
 * platform (2026): a Firestore database that allows reads with the app's public API key, shared with
 * other VAIMOO cities, so the query is scoped to the GIRA tenant.
 *
 * EMEL has not published this API and may change or close it at any time; the app then falls back to
 * the bundled stations (see [LiveGiraRepository]). Embedding the key is a deliberate exception to the
 * "no keys in the code" rule (see CLAUDE.md): it is the official app's public client key, not a secret.
 */
@Singleton
class VaimooGiraFeed @Inject constructor(
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : GiraFeed {

    override suspend fun fetch(): String = withContext(io) {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(QUERY.toByteArray()) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GIRA stations: HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PROJECT = "vaimoorotterdam"
        const val API_KEY = "AIzaSyAmKfHdjYUhzYmg7qSZtRwwYE92HQQlmJ4"
        const val TENANT = "P1/EML/EML/"
        const val ENDPOINT = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents:runQuery?key=$API_KEY"
        const val QUERY = """{"structuredQuery":{"from":[{"collectionId":"docking-stations"}],""" +
            """"where":{"fieldFilter":{"field":{"fieldPath":"Tenant"},"op":"EQUAL","value":{"stringValue":"$TENANT"}}}}}"""
        const val TIMEOUT_MS = 8_000
    }
}

/** Reads the Firestore `runQuery` response of [VaimooGiraFeed]. */
object GiraFirestore {
    /** "253 - Avenida da Universidade Técnica / FMV" → 253 and the place. */
    private val NAME = Regex("""^\s*(\d+)\s*-\s*(.*)$""")

    fun parse(json: String): List<GiraStation> = Json.parseToJsonElement(json).jsonArray.mapNotNull { result ->
        val fields = result.jsonObject["document"]?.jsonObject?.get("fields")?.jsonObject ?: return@mapNotNull null
        val location = fields.value("Location", "geoPointValue")?.jsonObject ?: return@mapNotNull null
        val lat = location["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lon = location["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val name = fields.value("Name", "stringValue")?.jsonPrimitive?.contentOrNull.orEmpty()
        val match = NAME.find(name)
        val inService = fields.value("IsActive", "booleanValue")?.jsonPrimitive?.booleanOrNull == true &&
            fields.value("ServiceStatus", "stringValue")?.jsonPrimitive?.contentOrNull == IN_SERVICE
        GiraStation(
            number = match?.groupValues?.get(1)?.toIntOrNull(),
            name = name,
            point = GeoPoint(lat, lon),
            bikes = fields.integer("AvailableBikes") ?: 0,
            freeDocks = fields.integer("FreeDocks") ?: 0,
            docks = fields.integer("DockLimit"),
            place = match?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() },
            inService = inService,
        )
    }

    private fun JsonObject.value(field: String, type: String) = this[field]?.jsonObject?.get(type)

    /** Firestore sends integers as strings. */
    private fun JsonObject.integer(field: String): Int? = value(field, "integerValue")?.jsonPrimitive?.longOrNull?.toInt()

    private const val IN_SERVICE = "AVAILABLE"
}

/**
 * GIRA stations with their live bikes and free docks, refreshed at most every [MAX_AGE_MS]; the last
 * live data, or else the bundled stations (no counts), when the live source cannot be reached.
 * [warn] reports why it could not be.
 */
class LiveGiraRepository(
    private val offline: GiraRepository,
    private val feed: GiraFeed,
    private val clock: Clock,
    private val warn: (String) -> Unit = {},
) : GiraRepository {

    private val mutex = Mutex()
    private var live: List<GiraStation>? = null
    private var fetchedAt = 0L

    override suspend fun stations(): List<GiraStation> = mutex.withLock {
        val now = clock.millis()
        live?.takeIf { now - fetchedAt < MAX_AGE_MS }?.let { return it }
        try {
            GiraFirestore.parse(feed.fetch()).takeIf { it.isNotEmpty() }?.also {
                live = it
                fetchedAt = now
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Live GIRA stations unavailable: ${e.message}")
            null
        } ?: live ?: offline.stations()
    }

    companion object {
        const val MAX_AGE_MS = 60_000L
    }
}
