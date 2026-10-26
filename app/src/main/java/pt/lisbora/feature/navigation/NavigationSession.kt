package pt.lisbora.feature.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.lisbora.data.gira.GiraRepository
import pt.lisbora.data.gira.GiraStation
import pt.lisbora.data.gira.GiraStations
import pt.lisbora.data.gpx.GpxFiles
import pt.lisbora.data.location.LocationFix
import pt.lisbora.data.location.LocationRepository
import pt.lisbora.data.routing.RoutingRepository
import pt.lisbora.data.settings.RidingSettings
import pt.lisbora.data.settings.RidingSettingsRepository
import pt.lisbora.di.ApplicationScope
import pt.lisbora.routing.HazardKind
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteResult
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.io.Gpx
import pt.lisbora.routing.io.TrackPoint
import pt.lisbora.routing.navigation.Maneuver
import pt.lisbora.routing.navigation.RouteInstructions
import pt.lisbora.routing.navigation.RouteProgress
import pt.lisbora.routing.navigation.RouteSplice
import pt.lisbora.routing.navigation.RouteTracker
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * Follows a route on the ride: position fixes about once a second, the next instruction and what comes
 * after, a new route (on the phone) after five seconds off it, and a summary at the end that also teaches
 * the app the rider's pace. On a GIRA ride it counts the trip's 45 minutes and, before they run out, names
 * the nearest station to swap bikes at and can take the rider there.
 *
 * It lives as long as the app, not the screen: the ride goes on with the screen off or the app in the
 * background, while [NavigationService] keeps the app running and speaks the instructions.
 */
@Singleton
class NavigationSession @Inject constructor(
    private val location: LocationRepository,
    private val routing: RoutingRepository,
    private val gira: GiraRepository,
    private val settings: RidingSettingsRepository,
    private val gpx: GpxFiles,
    private val clock: Clock,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private var request: NavigationRequest? = null
    private var tracker: RouteTracker? = null

    /** On a detour to a GIRA station: the route to go back to, and how far along it the rider was. */
    private var mainRoute: Route? = null
    private var mainAlongM = 0.0

    private var locationJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastRerouteMs = Long.MIN_VALUE / 2
    private var lastFix: LocationFix? = null

    /** The last fix the ridden distance was measured to (GPS jumps are left out). */
    private var lastCounted: LocationFix? = null
    private var lastAlongM = 0.0
    private var remainingFromM = 0.0

    // The ride so far.
    private val track = mutableListOf<TrackPoint>()
    private var startedMs = 0L
    private var distanceM = 0.0
    private var movingS = 0.0
    private var predictedS = 0.0
    private var climbM = 0.0

    private var stations: List<GiraStation> = emptyList()
    private var stationsFetchedMs = Long.MIN_VALUE / 2

    fun start(request: NavigationRequest) {
        stopTracking()
        this.request = request
        mainRoute = null
        lastFix = null
        lastCounted = null
        track.clear()
        startedMs = clock.millis()
        distanceM = 0.0
        movingS = 0.0
        predictedS = 0.0
        climbM = 0.0
        _uiState.value = NavigationUiState(active = true, destination = request.destination, gira = request.gira?.let { GiraNavigation(it) })
        useRoute(request.route)
        if (request.gira != null) refreshStations()
        locationJob = scope.launch { location.updates().collect(::onFix) }
    }

    /** "Terminar": the ride ends here. */
    fun onStop() = finish(arrived = false)

    fun onSummaryDismiss() {
        _uiState.value = NavigationUiState()
    }

    fun onRecenter() = _uiState.update { it.copy(followRequest = it.followRequest + 1) }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    /** Saves the ride (track and planned route) as GPX to the document at [uri]. */
    fun onExportGpx(uri: String) {
        val summary = _uiState.value.summary ?: return
        scope.launch {
            val ok = runCatching { gpx.write(uri, Gpx.write(summary.title, summary.track, summary.plannedRoute)) }.isSuccess
            _uiState.update { it.copy(message = if (ok) NavigationMessage.GPX_SAVED else NavigationMessage.GPX_FAILED) }
        }
    }

    // --- GIRA -----------------------------------------------------------------------------------

    /** "Ir até lá": a detour to the suggested station, to swap bikes before the 45 minutes are up. */
    fun onGiraGoToStation() {
        val state = _uiState.value
        val g = state.gira ?: return
        val station = g.suggestion?.station ?: return
        val from = lastFix?.point ?: return
        if (mainRoute == null) {
            mainRoute = state.route
            mainAlongM = state.progress?.alongM ?: 0.0
        }
        _uiState.update { it.copy(gira = g.copy(swapStation = station, atSwapStation = false)) }
        reroute(from, Target.To(station.point), force = true)
    }

    /** "Já troquei": a new bike, a new 45 minutes; back to the planned route after a detour. */
    fun onGiraSwapped() {
        val g = _uiState.value.gira ?: return
        val now = lastFix?.timeMs ?: clock.millis()
        _uiState.update {
            it.copy(gira = g.copy(startedAtMs = now, elapsedS = 0.0, warning = null, suggestion = null, swapStation = null, atSwapStation = false, swaps = g.swaps + 1))
        }
        val main = mainRoute ?: return
        mainRoute = null
        val from = lastFix?.point ?: return
        reroute(from, Target.Rejoin(main, mainAlongM), force = true)
    }

    // --- fixes ----------------------------------------------------------------------------------

    internal fun onFix(fix: LocationFix) {
        val tracker = tracker ?: return
        if (!_uiState.value.active) return
        record(fix)
        val moving = (fix.speedMps ?: 0.0) >= MIN_BEARING_SPEED_MPS
        val progress = tracker.update(fix.point, fix.timeMs, fix.accuracyM ?: 0.0, fix.bearing.takeIf { moving })
        if (!progress.offRoute && progress.alongM > lastAlongM) {
            predictedS += tracker.timeAt(progress.alongM) - tracker.timeAt(lastAlongM)
            climbM += max(0.0, RouteSplice.elevationAt(tracker.route, progress.alongM) - RouteSplice.elevationAt(tracker.route, lastAlongM))
            lastAlongM = progress.alongM
        }
        val g = _uiState.value.gira?.let { updateGira(it, fix, progress) }
        _uiState.update { s -> withProgress(s, progress).copy(waitingForGps = false, gira = g) }

        val onSwapDetour = g?.swapStation != null && !g.atSwapStation
        when {
            onSwapDetour && progress.arrived -> _uiState.update { it.copy(gira = g.copy(atSwapStation = true)) }
            g?.atSwapStation == true -> Unit
            progress.arrived -> finish(arrived = true)
            // Still walking to the GIRA station: the route starts there, nothing to recompute.
            g != null && !g.started -> Unit
            progress.offRoute -> reroute(fix.point, defaultTarget(progress))
        }
    }

    private fun record(fix: LocationFix) {
        track += TrackPoint(fix.point, fix.timeMs, fix.altitudeM)
        lastFix = fix
        if ((fix.accuracyM ?: 0.0) > MAX_TRACK_ACCURACY_M) return
        val last = lastCounted
        val dt = last?.let { (fix.timeMs - it.timeMs) / 1000.0 }
        if (last == null || dt == null || dt > MAX_GAP_S) {
            lastCounted = fix
            return
        }
        if (dt <= 0) return
        val d = last.point.distanceTo(fix.point)
        // A jump faster than any bike is the GPS, not the rider: measure the next fix from the last good one.
        if (d / dt > MAX_PLAUSIBLE_SPEED_MPS) return
        lastCounted = fix
        distanceM += d
        if (d / dt >= MOVING_SPEED_MPS) movingS += dt
    }

    private fun updateGira(g: GiraNavigation, fix: LocationFix, progress: RouteProgress): GiraNavigation {
        val started = g.startedAtMs
            ?: fix.timeMs.takeIf { fix.point.distanceTo(g.trip.pickUp.point) <= PICK_UP_RADIUS_M || progress.alongM > PICK_UP_RADIUS_M }
        val elapsed = started?.let { (fix.timeMs - it) / 1000.0 } ?: 0.0
        // On a detour the rest of the ride is not known: the warning stays until the swap.
        val rideLeftS = if (g.swapStation != null) null else progress.remainingS
        val warning = if (started == null || g.atSwapStation) null else GiraPeriod.warning(elapsed, rideLeftS)
        if (warning != null && clock.millis() - stationsFetchedMs >= STATIONS_MAX_AGE_MS) refreshStations()
        val suggestion = if (warning != null && g.swapStation == null) {
            GiraStations.forSwap(stations, fix.point, MAX_SWAP_DISTANCE_M)?.let { NearbyStation(it, it.point.distanceTo(fix.point)) }
        } else {
            null
        }
        return g.copy(startedAtMs = started, elapsedS = elapsed, warning = warning, suggestion = suggestion)
    }

    private fun refreshStations() {
        stationsFetchedMs = clock.millis()
        scope.launch { runCatching { gira.stations() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { stations = it } }
    }

    // --- routes ---------------------------------------------------------------------------------

    private sealed interface Target {
        /** A new route to [point]. */
        data class To(val point: GeoPoint) : Target

        /** Back onto [route] a little after [alongM], then the rest of it. */
        data class Rejoin(val route: Route, val alongM: Double) : Target
    }

    private fun defaultTarget(progress: RouteProgress): Target {
        val state = _uiState.value
        val request = request ?: return Target.To(progress.snapped)
        state.gira?.swapStation?.let { return Target.To(it.point) }
        val route = state.route ?: return Target.To(request.destination)
        return if (request.rejoin) Target.Rejoin(route, progress.alongM) else Target.To(request.destination)
    }

    private fun reroute(from: GeoPoint, target: Target, force: Boolean = false) {
        val request = request ?: return
        val now = clock.millis()
        if (!force && (rerouteJob?.isActive == true || now - lastRerouteMs < REROUTE_COOLDOWN_MS)) return
        lastRerouteMs = now
        rerouteJob?.cancel()
        _uiState.update { it.copy(rerouting = true, rerouteFailed = false) }
        rerouteJob = scope.launch {
            val route = try {
                when (target) {
                    is Target.To -> detour(from, target.point, request)
                    is Target.Rejoin -> {
                        val rejoinAt = target.alongM + REJOIN_AHEAD_M
                        if (target.route.distanceM - rejoinAt < REJOIN_AHEAD_M) {
                            detour(from, target.route.points.last(), request)
                        } else {
                            detour(from, RouteAnalysis.pointAt(target.route, rejoinAt), request)
                                ?.let { RouteSplice.join(it, RouteSplice.slice(target.route, rejoinAt)) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!_uiState.value.active) return@launch
            if (route == null) {
                _uiState.update { it.copy(rerouting = false, rerouteFailed = true) }
            } else {
                _uiState.update { it.copy(rerouting = false) }
                useRoute(route)
            }
        }
    }

    private suspend fun detour(from: GeoPoint, to: GeoPoint, request: NavigationRequest): Route? =
        (routing.route(from, to, request.preferences, alternatives = 0) as? RouteResult.Success)?.route

    /** Follows [route] from now on, from its start. */
    private fun useRoute(route: Route) {
        tracker = RouteTracker(route)
        lastAlongM = 0.0
        remainingFromM = 0.0
        val instructions = RouteInstructions.build(route)
        _uiState.update {
            it.copy(
                route = route,
                instructions = instructions,
                hazards = RouteAnalysis.hazards(route),
                remaining = route,
                progress = null,
                next = instructions.firstOrNull { i -> i.maneuver != Maneuver.DEPART },
                distanceToNextM = instructions.firstOrNull { i -> i.maneuver != Maneuver.DEPART }?.atM,
                then = null,
            )
        }
    }

    private fun withProgress(s: NavigationUiState, progress: RouteProgress): NavigationUiState {
        val along = progress.alongM
        val next = s.instructions.firstOrNull { it.atM > along + NEXT_MARGIN_M && it.maneuver != Maneuver.DEPART }
        val then = next?.let { n -> s.instructions.firstOrNull { it.atM > n.atM }?.takeIf { it.atM - n.atM <= THEN_WITHIN_M } }
        val upcoming = s.hazards.firstOrNull { it.kind in AHEAD_KINDS && it.startM > along && it.startM - along <= UPCOMING_WITHIN_M }
        val route = s.route
        val remaining = if (route != null && abs(along - remainingFromM) >= REMAINING_STEP_M) {
            remainingFromM = along
            RouteSplice.slice(route, along)
        } else {
            s.remaining
        }
        return s.copy(
            progress = progress,
            next = next,
            distanceToNextM = next?.let { it.atM - along },
            then = then,
            upcoming = upcoming,
            distanceToUpcomingM = upcoming?.let { it.startM - along },
            remaining = remaining,
        )
    }

    // --- the end ----------------------------------------------------------------------------------

    private fun finish(arrived: Boolean) {
        val request = request ?: return
        stopTracking()
        if (distanceM < MIN_SUMMARY_M && !arrived) {
            _uiState.value = NavigationUiState()
            return
        }
        val elapsedS = ((lastFix?.timeMs ?: clock.millis()) - startedMs).coerceAtLeast(0) / 1000.0
        val summary = RideSummary(
            title = request.title,
            distanceM = distanceM,
            movingS = movingS,
            elapsedS = elapsedS,
            climbM = climbM,
            arrived = arrived,
            newPersonalFactor = null,
            track = track.toList(),
            plannedRoute = request.route,
        )
        // The summary straight away (the voice says "arrived" from it); what the ride taught comes after.
        _uiState.value = NavigationUiState(summary = summary)
        if (!request.calibrates) return
        scope.launch {
            val newFactor = learnPace() ?: return@launch
            _uiState.update { s -> if (s.summary === summary) s.copy(summary = summary.copy(newPersonalFactor = newFactor)) else s }
        }
    }

    /**
     * Moves the learnt pace part of the way towards this ride's (predicted over actual moving time),
     * when the ride was long enough to say something. Returns the new pace, or null when unchanged.
     */
    private suspend fun learnPace(): Double? {
        if (distanceM < MIN_CALIBRATION_M || predictedS < MIN_CALIBRATION_S || movingS <= 0) return null
        val ratio = (predictedS / movingS).coerceIn(MIN_RATIO, MAX_RATIO)
        val old = runCatching { settings.settings.first().personalFactor }.getOrNull() ?: return null
        val learnt = (old * (1 + LEARNING_RATE * (ratio - 1))).coerceIn(RidingSettings.PERSONAL_FACTOR_RANGE)
        if (abs(learnt - old) < MIN_FACTOR_CHANGE) return null
        runCatching { settings.update { it.copy(personalFactor = learnt) } }
        return learnt
    }

    private fun stopTracking() {
        locationJob?.cancel()
        rerouteJob?.cancel()
        locationJob = null
        rerouteJob = null
    }

    companion object {
        /** Below this speed the GPS bearing is noise. */
        const val MIN_BEARING_SPEED_MPS = 2.0
        const val MOVING_SPEED_MPS = 1.0
        const val MAX_PLAUSIBLE_SPEED_MPS = 20.0
        const val MAX_GAP_S = 10.0
        const val MAX_TRACK_ACCURACY_M = 30.0
        const val REROUTE_COOLDOWN_MS = 10_000L

        /** After leaving a route that is worth keeping, it is rejoined this far ahead. */
        const val REJOIN_AHEAD_M = 250.0
        const val NEXT_MARGIN_M = 3.0
        const val THEN_WITHIN_M = 80.0
        const val UPCOMING_WITHIN_M = 300.0
        const val REMAINING_STEP_M = 20.0
        const val MIN_SUMMARY_M = 100.0

        /** A GIRA trip starts at the pick-up station (or when the ride is under way). */
        const val PICK_UP_RADIUS_M = 50.0
        const val MAX_SWAP_DISTANCE_M = 1500.0
        const val STATIONS_MAX_AGE_MS = 60_000L

        const val MIN_CALIBRATION_M = 2000.0
        const val MIN_CALIBRATION_S = 600.0
        const val LEARNING_RATE = 0.3
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.0
        const val MIN_FACTOR_CHANGE = 0.005

        val AHEAD_KINDS = setOf(
            HazardKind.STEEP_CLIMB, HazardKind.STEEP_DESCENT, HazardKind.COBBLES, HazardKind.TRAM_TRACKS,
            HazardKind.WALK, HazardKind.BUSY_ROAD, HazardKind.FERRY,
        )
    }
}
