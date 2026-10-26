package pt.lisbora.feature.planner

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import pt.lisbora.data.discover.DiscoveryRepository
import pt.lisbora.data.gira.GiraRepository
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.gpx.GpxFiles
import pt.lisbora.data.location.LocationFix
import pt.lisbora.data.location.LocationRepository
import pt.lisbora.data.routing.RoutingRepository
import pt.lisbora.data.saved.SavedKind
import pt.lisbora.data.saved.SavedPlace
import pt.lisbora.data.saved.SavedPlacesRepository
import pt.lisbora.data.saved.SavedRoute
import pt.lisbora.data.saved.SavedRoutesRepository
import pt.lisbora.data.search.SearchRepository
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.data.settings.RidingSettingsRepository
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteLeg
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryRequest
import pt.lisbora.routing.discover.DiscoveryResult
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.RouteHighlights
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeFlags
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.RoadKind
import pt.lisbora.routing.graph.TravelMode
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.SearchHit
import pt.lisbora.routing.weighting.RoutingPreferences

val Baixa = GeoPoint(38.7105, -9.1376)
val Belem = GeoPoint(38.6970, -9.2060)
val Graca = GeoPoint(38.7168, -9.1312)

/**
 * A straight route through [points], one leg per segment with the given infrastructure and
 * elevations, so view models and map layers can be tested without a graph.
 */
fun fakeRoute(
    points: List<GeoPoint>,
    durationS: Double = 600.0,
    infra: List<EdgeInfra> = List(points.size - 1) { EdgeInfra.NONE },
    elevations: List<Double> = List(points.size) { 0.0 },
    modes: List<TravelMode> = List(points.size - 1) { TravelMode.RIDE },
    stress: List<Int> = List(points.size - 1) { 2 },
): Route {
    val distances = points.runningFold(0.0 to points.first()) { (d, prev), p -> d + prev.distanceTo(p) to p }.drop(1).map { it.first }
    val legs = (0 until points.size - 1).map { i ->
        val length = distances[i + 1] - distances[i]
        RouteLeg(
            edge = i, reverse = false, from = 0.0, to = length,
            flags = EdgeFlags.of(modes[i], modes[i], infra = infra[i], road = RoadKind.RESIDENTIAL, stress = stress[i]),
            name = "Rua $i", lengthM = length, timeS = durationS / (points.size - 1), firstPoint = i, startM = distances[i],
        )
    }
    val climbs = elevations.zipWithNext { a, b -> b - a }
    return Route(
        points = points, elevations = elevations, distances = distances, legs = legs,
        distanceM = distances.last(), durationS = durationS,
        ascentM = climbs.filter { it > 0 }.sum(), descentM = -climbs.filter { it < 0 }.sum(),
        maxGradePercent = 0.0, cost = durationS, settledStates = 0,
    )
}

class FakeSearchRepository : SearchRepository {
    var hits: List<SearchHit> = emptyList()
    var described: Place? = null
    val queries = mutableListOf<String>()

    override suspend fun search(query: String, near: GeoPoint?): List<SearchHit> {
        queries += query
        return hits
    }

    override suspend fun describe(point: GeoPoint): Place? = described?.copy(point = point)

    override suspend fun warmUp() = Unit
}

class FakeRoutingRepository : RoutingRepository {
    var result: RouteResult = RouteResult.Failure(RouteResult.Reason.NO_PATH)
    val requests = mutableListOf<Triple<GeoPoint, GeoPoint, RoutingPreferences>>()

    override suspend fun route(
        from: GeoPoint,
        to: GeoPoint,
        preferences: RoutingPreferences,
        alternatives: Int,
        onBest: suspend (RouteResult.Success) -> Unit,
    ): RouteResult {
        requests += Triple(from, to, preferences)
        (result as? RouteResult.Success)?.let { onBest(RouteResult.Success(it.route)) }
        return result
    }

    var alongResult: RouteResult = RouteResult.Failure(RouteResult.Reason.NO_PATH)
    val tracks = mutableListOf<List<GeoPoint>>()

    override suspend fun routeAlong(track: List<GeoPoint>, preferences: RoutingPreferences): RouteResult {
        tracks += track
        return alongResult
    }

    override suspend fun streetName(point: GeoPoint, maxDistanceM: Double): String? = null

    override suspend fun warmUp() = Unit
}

class FakeLocationRepository(var permission: Boolean = true, var location: GeoPoint? = Baixa) : LocationRepository {
    /** Fixes sent to whoever follows the position (navigation). */
    val fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)

    override fun hasPermission() = permission
    override suspend fun currentLocation(): GeoPoint? = if (permission) location else null
    override fun updates(intervalMs: Long): Flow<LocationFix> = fixes
}

class FakeSettingsRepository : RidingSettingsRepository {
    val state = MutableStateFlow(RidingSettings())
    override val settings: Flow<RidingSettings> = state
    override suspend fun update(transform: (RidingSettings) -> RidingSettings) {
        state.value = transform(state.value)
    }
}

class FakeSavedPlacesRepository : SavedPlacesRepository {
    private val savedState = MutableStateFlow<List<SavedPlace>>(emptyList())
    private val recentState = MutableStateFlow<List<Place>>(emptyList())
    private var ids = 0L

    override val saved: Flow<List<SavedPlace>> = savedState
    override val recent: Flow<List<Place>> = recentState.map { it }

    override suspend fun save(place: Place, kind: SavedKind) {
        val kept = if (kind == SavedKind.FAVOURITE) savedState.value else savedState.value.filterNot { it.kind == kind }
        savedState.value = kept + SavedPlace(++ids, kind, place)
    }

    override suspend fun remove(id: Long) {
        savedState.value = savedState.value.filterNot { it.id == id }
    }

    override suspend fun addRecent(place: Place) {
        recentState.value = listOf(place) + recentState.value.filterNot { it == place }
    }

    override suspend fun clearRecent() {
        recentState.value = emptyList()
    }
}

/** A discovered route around [route], built through [stops], with the highlights given. */
fun fakeDiscovered(
    route: Route,
    stops: List<GeoPoint> = listOf(route.points.first(), route.points[route.points.size / 2], route.points.first()),
    anchors: List<Poi> = emptyList(),
    pois: List<Poi> = anchors,
    riversideM: Double = 0.0,
    greenM: Double = 0.0,
    segregatedM: Double = 0.0,
    calmShare: Double = 0.5,
    netDescentM: Double = 0.0,
) = DiscoveredRoute(
    route = route,
    stops = stops,
    anchors = anchors,
    turnsBack = false,
    highlights = RouteHighlights(
        riversideM = riversideM, greenM = greenM, segregatedM = segregatedM, infraShare = route.infraShare,
        calmShare = calmShare, pois = pois, netDescentM = netDescentM,
    ),
    score = 1.0,
)

class FakeDiscoveryRepository : DiscoveryRepository {
    var result: DiscoveryResult = DiscoveryResult.Failure(DiscoveryResult.Reason.NO_ROUTE)
    var rebuilt: DiscoveredRoute? = null
    val requests = mutableListOf<DiscoveryRequest>()
    val rebuildPreferences = mutableListOf<RoutingPreferences>()
    var warmUps = 0

    override suspend fun discover(request: DiscoveryRequest): DiscoveryResult {
        requests += request
        return result
    }

    override suspend fun rebuild(saved: SavedRoute, preferences: RoutingPreferences): DiscoveredRoute? {
        rebuildPreferences += preferences
        return rebuilt
    }

    override suspend fun describe(route: Route, stops: List<GeoPoint>): DiscoveredRoute = fakeDiscovered(route, stops = stops)

    override suspend fun warmUp() {
        warmUps++
    }
}

class FakeSavedRoutesRepository : SavedRoutesRepository {
    private val state = MutableStateFlow<List<SavedRoute>>(emptyList())
    private var ids = 0L

    override val routes: Flow<List<SavedRoute>> = state

    override suspend fun save(route: DiscoveredRoute, purpose: DiscoveryPurpose, tweaks: Set<DiscoveryTweak>, gira: Boolean): Long {
        val saved = SavedRoute(
            ++ids, purpose, tweaks, route.stops, route.turnsBack, route.route.distanceM, route.route.durationS, route.route.ascentM,
            highlight = route.anchors.firstOrNull()?.name, gira = gira,
        )
        state.value = listOf(saved) + state.value
        return saved.id
    }

    override suspend fun remove(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }
}

class FakeGiraRepository(var stations: List<GiraStation> = emptyList()) : GiraRepository {
    override suspend fun stations(): List<GiraStation> = stations
}

class FakeGpxFiles : GpxFiles {
    val written = mutableMapOf<String, String>()
    val files = mutableMapOf<String, String>()
    override suspend fun write(uri: String, gpx: String) {
        written[uri] = gpx
    }
    override suspend fun read(uri: String): String = files[uri] ?: throw java.io.IOException("No file $uri")
}

/** A clock the test moves by hand. */
class MutableClock(var millis: Long = 1_761_490_800_000L) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
    override fun instant(): java.time.Instant = java.time.Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
}
