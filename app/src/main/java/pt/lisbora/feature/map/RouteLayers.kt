package pt.lisbora.feature.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.feature.planner.RouteColorMode
import pt.lisbora.feature.planner.RouteOption
import pt.lisbora.routing.HazardKind
import pt.lisbora.routing.Route
import pt.lisbora.routing.RouteAnalysis
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.graph.TravelMode
import kotlin.math.abs

/** Ids of the route sources and layers LisBora adds to the style (see [MapStyleComposer]). */
object RouteLayers {
    const val SOURCE = "route"
    const val MARKER_SOURCE = "route-markers"
    const val ALT_CASING = "route-alt-casing"
    const val ALT = "route-alt"
    const val SELECTED_CASING = "route-selected-casing"
    const val SELECTED = "route-selected"
    const val SELECTED_DASHED = "route-selected-dashed"
    const val MARKERS = "route-markers"

    /** Feature property naming the alternative's index, used when the user taps a grey route. */
    const val INDEX_PROPERTY = "index"
}

/** Colours of routes and markers, light and dark. Infrastructure colours come from [CycleNetworkPalette]. */
data class RoutePalette(
    val network: CycleNetworkPalette,
    val noInfra: String,
    val osmCycleway: String,
    val alternative: String,
    /** Grade buckets: < 3 %, 3–6 %, 6–10 %, ≥ 10 %. */
    val grade: List<String>,
    /** Traffic stress 1..4. */
    val stress: List<String>,
    val walk: String,
    val ferry: String,
    val origin: String,
    val destination: String,
    val pin: String,
    val hazards: Map<HazardKind, String>,
) {
    fun hazardColor(kind: HazardKind): String = hazards.getValue(kind)

    fun infraColor(infra: EdgeInfra): String = when (infra) {
        EdgeInfra.NONE -> noInfra
        EdgeInfra.OSM_CYCLEWAY -> osmCycleway
        else -> network.style(infra.cmlClass!!).color
    }

    companion object {
        val Light = RoutePalette(
            network = CycleNetworkPalette.Light,
            noInfra = "#3D4A5C",
            osmCycleway = "#1C9C7C",
            alternative = "#8E97A6",
            grade = listOf("#2E9D5B", "#D9B300", "#E07B00", "#C62828"),
            stress = listOf("#2E9D5B", "#D9B300", "#E07B00", "#C62828"),
            walk = "#6B7280",
            ferry = "#1E6FD9",
            origin = "#0B7A4B",
            destination = "#C62828",
            pin = "#7C3AED",
            hazards = mapOf(
                HazardKind.TRAM_TRACKS to "#E07B00",
                HazardKind.COBBLES to "#8B5A2B",
                HazardKind.BUSY_ROAD to "#C62828",
                HazardKind.STEEP_DESCENT to "#7C3AED",
                HazardKind.STEEP_CLIMB to "#B38F00",
                HazardKind.WALK to "#6B7280",
                HazardKind.FERRY to "#1E6FD9",
            ),
        )

        val Dark = RoutePalette(
            network = CycleNetworkPalette.Dark,
            noInfra = "#D4DCE6",
            osmCycleway = "#4FD1B0",
            alternative = "#6B7484",
            grade = listOf("#5FD38A", "#F2D24B", "#FF9F43", "#FF6B6B"),
            stress = listOf("#5FD38A", "#F2D24B", "#FF9F43", "#FF6B6B"),
            walk = "#A0A8B5",
            ferry = "#6CA8F0",
            origin = "#3DDC84",
            destination = "#FF6B6B",
            pin = "#C4B5FD",
            hazards = mapOf(
                HazardKind.TRAM_TRACKS to "#FF9F43",
                HazardKind.COBBLES to "#C99A6B",
                HazardKind.BUSY_ROAD to "#FF6B6B",
                HazardKind.STEEP_DESCENT to "#C4B5FD",
                HazardKind.STEEP_CLIMB to "#F2D24B",
                HazardKind.WALK to "#A0A8B5",
                HazardKind.FERRY to "#6CA8F0",
            ),
        )

        /** Bucket of an absolute grade (rise / run) for colouring: 0..3. */
        fun gradeBucket(grade: Double): Int = when {
            abs(grade) < 0.03 -> 0
            abs(grade) < 0.06 -> 1
            abs(grade) < 0.10 -> 2
            else -> 3
        }
    }
}

/** A point drawn over the map: the trip's ends, a dropped pin or the start of a hazard. */
data class MapMarker(val point: GeoPoint, val kind: Kind, val hazard: HazardKind? = null) {
    enum class Kind { ORIGIN, DESTINATION, PIN, HAZARD }
}

/** GeoJSON for the route and marker sources. Pure, so it is unit-tested on the JVM. */
object RouteGeoJson {

    val EMPTY: String = featureCollection(emptyList()).toString()

    /** Grey alternatives (with their index) under the selected route, split into coloured runs. */
    fun routes(options: List<RouteOption>, selected: Int, mode: RouteColorMode, palette: RoutePalette): String {
        val features = mutableListOf<JsonObject>()
        options.forEachIndexed { i, option ->
            if (i != selected) features += line(option.route.points, palette.alternative, "alt", dashed = false, index = i)
        }
        options.getOrNull(selected)?.let { option ->
            runs(option.route, mode, palette).forEach { run ->
                features += line(option.route.points.subList(run.from, run.to + 1), run.color, "selected", run.dashed, index = selected)
            }
        }
        return featureCollection(features).toString()
    }

    fun markers(markers: List<MapMarker>, palette: RoutePalette): String = featureCollection(
        markers.map { m ->
            val (color, radius) = when (m.kind) {
                MapMarker.Kind.ORIGIN -> palette.origin to 8
                MapMarker.Kind.DESTINATION -> palette.destination to 9
                MapMarker.Kind.PIN -> palette.pin to 9
                MapMarker.Kind.HAZARD -> palette.hazardColor(m.hazard ?: HazardKind.TRAM_TRACKS) to 5
            }
            buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("kind", m.kind.name.lowercase())
                    put("color", color)
                    put("radius", radius)
                }
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(JsonPrimitive(m.point.lon)); add(JsonPrimitive(m.point.lat)) }
                }
            }
        },
    ).toString()

    fun hazardMarkers(hazards: List<RouteHazard>): List<MapMarker> = hazards.map { MapMarker(it.point, MapMarker.Kind.HAZARD, it.kind) }

    /** A stretch of the route between point indexes [from] and [to] drawn in one colour. */
    internal data class Run(val from: Int, val to: Int, val color: String, val dashed: Boolean)

    internal fun runs(route: Route, mode: RouteColorMode, palette: RoutePalette): List<Run> {
        val last = route.points.lastIndex
        if (last < 1) return emptyList()
        // Colour and dash of each segment (point i to i + 1), then merged into runs.
        val colors = arrayOfNulls<String>(last)
        val dashed = BooleanArray(last)
        val grades = if (mode == RouteColorMode.GRADE) RouteAnalysis.grades(route) else null
        route.legs.forEachIndexed { l, leg ->
            val end = route.legs.getOrNull(l + 1)?.firstPoint ?: last
            val walk = leg.mode == TravelMode.PUSH
            val ferry = leg.flags.isFerry
            for (i in leg.firstPoint until end) {
                colors[i] = when {
                    ferry -> palette.ferry
                    mode == RouteColorMode.INFRA -> if (walk) palette.walk else palette.infraColor(leg.infra)
                    mode == RouteColorMode.STRESS -> if (walk) palette.walk else palette.stress[leg.flags.stress - 1]
                    else -> palette.grade[RoutePalette.gradeBucket(grades!![i])]
                }
                dashed[i] = walk || ferry
            }
        }
        val runs = mutableListOf<Run>()
        var start = 0
        for (i in 1..last) {
            if (i == last || colors[i] != colors[start] || dashed[i] != dashed[start]) {
                runs += Run(start, i, colors[start] ?: palette.noInfra, dashed[start])
                start = i
            }
        }
        return runs
    }

    private fun line(points: List<GeoPoint>, color: String, layer: String, dashed: Boolean, index: Int) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") {
            put("layer", layer)
            put("color", color)
            put("dashed", dashed)
            put(RouteLayers.INDEX_PROPERTY, index)
        }
        putJsonObject("geometry") {
            put("type", "LineString")
            putJsonArray("coordinates") {
                points.forEach { p -> addJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) } }
            }
        }
    }

    private fun featureCollection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }
}
