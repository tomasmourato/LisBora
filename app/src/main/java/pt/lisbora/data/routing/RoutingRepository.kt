package pt.lisbora.data.routing

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import pt.lisbora.di.DefaultDispatcher
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.navigation.TrackMatcher
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

    /** The route along a [track] ridden or drawn elsewhere (an imported GPX), on the streets it keeps to. */
    suspend fun routeAlong(track: List<GeoPoint>, preferences: RoutingPreferences): RouteResult

    /** Name of the street nearest to [point] (within [maxDistanceM]), for labelling a point picked on the map. */
    suspend fun streetName(point: GeoPoint, maxDistanceM: Double = 60.0): String?

    /** Loads the graph ahead of the first route, so it does not wait for it. */
    suspend fun warmUp()
}

/**
 * Routes on the phone with the graph from the installed data pack (see [RoutingEngineStore]).
 * Searches run on [compute], away from the main thread.
 */
@Singleton
class GraphRoutingRepository @Inject constructor(
    private val engines: RoutingEngineStore,
    @param:DefaultDispatcher private val compute: CoroutineDispatcher,
) : RoutingRepository {

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        preferences: RoutingPreferences,
        alternatives: Int,
        onBest: suspend (RouteResult.Success) -> Unit,
    ): RouteResult {
        val engine = engines.engine()
        val started = System.nanoTime()
        val start = withContext(compute) { engine.snap(from, preferences) }
            ?: return RouteResult.Failure(RouteResult.Reason.START_NOT_ON_NETWORK)
        val end = withContext(compute) { engine.snap(to, preferences) }
            ?: return RouteResult.Failure(RouteResult.Reason.END_NOT_ON_NETWORK)
        val best = withContext(compute) { engine.route(start, end, preferences) }
        if (best is RouteResult.Success) Log.i(TAG, "Best route in ${elapsedMs(started)} ms (${best.route.settledStates} states)")
        if (best !is RouteResult.Success || alternatives <= 0) return best
        onBest(best)
        return withContext(compute) { engine.alternatives(start, end, preferences, alternatives, best.route) }.also { result ->
            Log.i(TAG, "${(result as? RouteResult.Success)?.routes?.size ?: 0} routes in ${elapsedMs(started)} ms")
        }
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    override suspend fun routeAlong(track: List<GeoPoint>, preferences: RoutingPreferences): RouteResult {
        val engine = engines.engine()
        val started = System.nanoTime()
        return withContext(compute) { TrackMatcher.match(engine, track, preferences) }.also { result ->
            Log.i(TAG, "Track of ${track.size} points followed in ${elapsedMs(started)} ms: ${(result as? RouteResult.Success)?.route?.distanceM?.toInt()} m")
        }
    }

    override suspend fun streetName(point: GeoPoint, maxDistanceM: Double): String? {
        val engine = engines.engine()
        return withContext(compute) {
            engine.snap(point, maxDistanceM = maxDistanceM)?.let { engine.graph.name(it.edge) }
        }
    }

    override suspend fun warmUp() {
        engines.engine()
    }

    private companion object {
        const val TAG = "LisBoraRouting"
    }
}
