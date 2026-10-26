package pt.lisbora.data.routing

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.datapack.DataPackStore
import pt.lisbora.di.DefaultDispatcher
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import pt.lisbora.routing.weighting.RoutingPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when the installed data pack has no routing graph (e.g. a trimmed build). */
class RoutingUnavailableException(message: String) : IOException(message)

interface RoutingRepository {
    /**
     * The best route plus up to [alternatives] clearly different ones. [onBest] gets the best route as soon
     * as it is known, so it can be shown while the alternatives are still being searched.
     */
    suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        preferences: RoutingPreferences,
        alternatives: Int = 2,
        onBest: suspend (RouteResult.Success) -> Unit = {},
    ): RouteResult

    /** Name of the street nearest to [point] (within [maxDistanceM]), for labelling a point picked on the map. */
    suspend fun streetName(point: GeoPoint, maxDistanceM: Double = 60.0): String?

    /** Loads the graph ahead of the first route, so it does not wait for it. */
    suspend fun warmUp()
}

/**
 * Routes on the phone with the graph from the installed data pack. The graph (~8 MB) is read once
 * and searches run on [compute], away from the main thread.
 */
@Singleton
class GraphRoutingRepository @Inject constructor(
    private val packs: DataPackStore,
    @param:DefaultDispatcher private val compute: CoroutineDispatcher,
) : RoutingRepository {

    private val mutex = Mutex()
    private var engine: RoutingEngine? = null

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        preferences: RoutingPreferences,
        alternatives: Int,
        onBest: suspend (RouteResult.Success) -> Unit,
    ): RouteResult {
        val engine = engine()
        val started = System.nanoTime()
        val start = withContext(compute) { engine.snap(from, preferences) }
            ?: return RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK)
        val end = withContext(compute) { engine.snap(to, preferences) }
            ?: return RouteResult.Failure(RouteResult.Reason.END_NOT_ON_NETWORK)
        val best = withContext(compute) { engine.route(start, end, preferences) }
        if (best !is RouteResult.Success || alternatives <= 0) return best
        Log.i(TAG, "Best route in ${elapsedMs(started)} ms (${best.route.settledStates} states)")
        onBest(best)
        return withContext(compute) { engine.alternatives(start, end, preferences, alternatives, best.route) }.also { result ->
            Log.i(TAG, "${(result as? RouteResult.Success)?.routes?.size ?: 0} routes in ${elapsedMs(started)} ms")
        }
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    override suspend fun streetName(point: GeoPoint, maxDistanceM: Double): String? {
        val engine = engine()
        return withContext(compute) {
            engine.snap(point, maxDistanceM = maxDistanceM)?.let { engine.graph.name(it.edge) }
        }
    }

    override suspend fun warmUp() {
        engine()
    }

    private suspend fun engine(): RoutingEngine = mutex.withLock {
        engine ?: run {
            val file = packs.pack().file(DataPackInstaller.GRAPH_FILE)
                ?: throw RoutingUnavailableException("Data pack has no ${DataPackInstaller.GRAPH_FILE}")
            withContext(compute) {
                val started = System.nanoTime()
                RoutingEngine(RoutingGraph(file.inputStream().buffered().use(GraphFormat::read))).also {
                    Log.i(TAG, "Graph loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
        }.also { engine = it }
    }

    private companion object {
        const val TAG = "LisBoraRouting"
    }
}
