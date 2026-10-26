package pt.lisbora.data.discover

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.datapack.DataPackStore
import pt.lisbora.data.routing.RoutingEngineStore
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.di.DefaultDispatcher
import pt.lisbora.routing.Route
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryEngine
import pt.lisbora.routing.discover.DiscoveryRequest
import pt.lisbora.routing.discover.DiscoveryResult
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.discover.TaskRunner
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.weighting.RoutingPreferences
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

interface DiscoveryRepository {
    suspend fun discover(request: DiscoveryRequest): DiscoveryResult

    /** A saved route computed again with today's data; null when that is no longer possible. */
    suspend fun rebuild(saved: SavedRoute, preferences: RoutingPreferences): DiscoveredRoute?

    /** A route found elsewhere (an imported GPX) with the river, parks and places it passes, for its card. */
    suspend fun describe(route: Route, stops: List<GeoPoint>): DiscoveredRoute

    /** Prepares the engine (points of interest, network parts) before the first discovery. */
    suspend fun warmUp()
}

/**
 * Descobrir on the phone: the shared routing engine plus the points of interest of the installed data
 * pack. A discovery's candidate routes are computed in parallel on a small pool of its own.
 */
@Singleton
class GraphDiscoveryRepository @Inject constructor(
    private val engines: RoutingEngineStore,
    private val packs: DataPackStore,
    @param:DefaultDispatcher private val compute: CoroutineDispatcher,
) : DiscoveryRepository {

    private val mutex = Mutex()
    private var engine: DiscoveryEngine? = null
    private val runner by lazy { PoolRunner(Executors.newFixedThreadPool(THREADS) { Thread(it, "lisbora-discover").apply { isDaemon = true } }) }

    override suspend fun discover(request: DiscoveryRequest): DiscoveryResult {
        val engine = engine()
        return withContext(compute) {
            val started = System.nanoTime()
            engine.discover(request, runner).also { result ->
                val routes = (result as? DiscoveryResult.Success)?.routes?.size ?: 0
                Log.i(TAG, "$routes discovered routes in ${elapsedMs(started)} ms ($THREADS threads)")
            }
        }
    }

    override suspend fun rebuild(saved: SavedRoute, preferences: RoutingPreferences): DiscoveredRoute? {
        val engine = engine()
        return withContext(compute) { engine.rebuild(saved.stops, saved.purpose, saved.tweaks, preferences, saved.turnsBack) }
    }

    override suspend fun describe(route: Route, stops: List<GeoPoint>): DiscoveredRoute {
        val engine = engine()
        return withContext(compute) { engine.describe(route, stops) }
    }

    override suspend fun warmUp() {
        engine()
    }

    private suspend fun engine(): DiscoveryEngine = mutex.withLock {
        engine ?: run {
            val routing = engines.engine()
            val file = packs.pack().file(DataPackInstaller.POIS_FILE)
            withContext(compute) {
                val started = System.nanoTime()
                val pois = file?.let { PoiGeoJson.parse(it.readText()) }.orEmpty()
                DiscoveryEngine(routing, pois).also {
                    Log.i(TAG, "Discovery ready in ${elapsedMs(started)} ms (${pois.size} points of interest)")
                }
            }
        }.also { engine = it }
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    private class PoolRunner(private val executor: ExecutorService) : TaskRunner {
        override fun <T> runAll(tasks: List<() -> T>): List<T> = executor.invokeAll(tasks.map { Callable(it) }).map { it.get() }
    }

    private companion object {
        const val TAG = "LisBoraDiscover"

        /** Leaves a core for the interface; four searches at a time is where the gains stop. */
        val THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}

/** Reads `pois.geojson`, written by the pipeline's `search`; features of unknown kinds are skipped. */
internal object PoiGeoJson {
    fun parse(json: String): List<Poi> = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.mapNotNull { element ->
        val feature = element.jsonObject
        val properties = feature["properties"]!!.jsonObject
        val kind = PoiKind.fromCode(properties["kind"]!!.jsonPrimitive.content) ?: return@mapNotNull null
        val (lon, lat) = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.double }
        Poi(kind, properties["name"]?.jsonPrimitive?.contentOrNull, GeoPoint(lat, lon), properties["importance"]?.jsonPrimitive?.intOrNull ?: 0)
    }
}
