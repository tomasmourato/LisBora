package pt.lisbora.data.datapack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.di.IoDispatcher
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** What the data in use is and where it comes from, for the settings screen. */
data class DataPackInfo(
    /** When the pack's content last changed. */
    val updatedAt: Instant? = null,
    /** Last edit of the CML cycle network. */
    val cycleNetworkEditedAt: Instant? = null,
    /** Date of the OpenStreetMap data behind the routes and the search. */
    val osmDataAt: Instant? = null,
    val giraStations: Int? = null,
    val giraFetchedAt: Instant? = null,
    /** When the phone last asked for newer data. */
    val checkedAt: Instant? = null,
) {
    companion object {
        private val VERSION = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /** "20261102T043012Z" → the instant; null for packs without a version. */
        fun parseVersion(version: String): Instant? =
            runCatching { LocalDateTime.parse(version, VERSION).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}

interface DataUpdatesRepository {
    val status: StateFlow<DataUpdateStatus>

    suspend fun info(): DataPackInfo

    /** Looks for newer data now, on any network (the user asked), and downloads it. */
    suspend fun checkNow(): DataUpdateStatus
}

@Singleton
class PackDataUpdatesRepository @Inject constructor(
    private val store: DataPackStore,
    private val updater: DataPackUpdater,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : DataUpdatesRepository {

    override val status: StateFlow<DataUpdateStatus> get() = updater.status

    override suspend fun info(): DataPackInfo {
        val pack = store.pack()
        val state = store.state()
        updater.refreshStatus()
        return withContext(io) {
            fun meta(path: String): JsonObject? = pack.file(path)?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
            val gira = meta(DataPackInstaller.GIRA_META_FILE)
            DataPackInfo(
                updatedAt = DataPackInfo.parseVersion(pack.manifest.dataVersion),
                cycleNetworkEditedAt = meta(DataPackInstaller.CYCLE_NETWORK_META_FILE)?.instant("sourceLastEdit"),
                osmDataAt = meta(DataPackInstaller.GRAPH_META_FILE)?.get("sources")?.jsonObject?.get("osm")?.jsonObject?.instant("dataDate"),
                giraStations = gira?.get("stations")?.jsonPrimitive?.intOrNull,
                giraFetchedAt = gira?.instant("fetchedAt"),
                checkedAt = state.checkedAt?.let(Instant::ofEpochMilli),
            )
        }
    }

    override suspend fun checkNow(): DataUpdateStatus = updater.update()

    private fun JsonObject.instant(key: String): Instant? =
        this[key]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
