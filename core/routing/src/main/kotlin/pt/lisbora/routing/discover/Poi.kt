package pt.lisbora.routing.discover

import pt.lisbora.routing.geo.GeoPoint
import kotlin.math.cos
import kotlin.math.floor

/**
 * Kinds of points of interest: shown on the map and used as Descobrir destinations.
 * [code] is stored in `pois.geojson`: never rename one.
 */
enum class PoiKind(val code: String) {
    VIEWPOINT("viewpoint"),
    PARK("park"),
    PLAYGROUND("playground"),
    BEACH("beach"),
    MUSEUM("museum"),
    MONUMENT("monument"),
    ATTRACTION("attraction"),
    DRINKING_WATER("drinking_water"),
    BIKE_PARKING("bike_parking"),

    /** Bike-share station (GIRA). */
    BIKE_SHARE("bike_share"),
    BIKE_SHOP("bike_shop"),

    /** Public pump and tools (`amenity=bicycle_repair_station`). */
    BIKE_REPAIR("bike_repair");

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: String): PoiKind? = byCode[code]
    }
}

/** A point of interest from OpenStreetMap. [importance] (0..100) ranks the well-known ones first. */
data class Poi(val kind: PoiKind, val name: String?, val point: GeoPoint, val importance: Int = 0)

/** Points of interest in a grid of ~[CELL_M] cells, for "what is near this route". */
class PoiIndex(val pois: List<Poi>) {

    private val cosLat = cos(Math.toRadians(pois.firstOrNull()?.point?.lat ?: LISBON_LAT))
    private val cells: Map<Long, List<Int>> = pois.indices.groupBy { cellOf(pois[it].point) }

    /** POIs within [radiusM] of [point] (radius at most [CELL_M]). */
    fun near(point: GeoPoint, radiusM: Double): List<Poi> {
        require(radiusM <= CELL_M) { "radius larger than a cell: $radiusM" }
        val (row, col) = rowCol(point)
        val result = mutableListOf<Poi>()
        for (dr in -1..1) for (dc in -1..1) {
            cells[key(row + dr, col + dc)]?.forEach { i ->
                if (pois[i].point.distanceTo(point) <= radiusM) result += pois[i]
            }
        }
        return result
    }

    /** POIs within [radiusM] of any of [points] (each once, in order of first encounter). */
    fun along(points: List<GeoPoint>, radiusM: Double, accept: (Poi) -> Boolean = { true }): List<Poi> {
        val seen = LinkedHashSet<Poi>()
        points.forEach { p -> near(p, radiusM).filterTo(seen, accept) }
        return seen.toList()
    }

    private fun rowCol(point: GeoPoint): Pair<Int, Int> =
        floor(point.lat * METRES_PER_DEGREE / CELL_M).toInt() to floor(point.lon * METRES_PER_DEGREE * cosLat / CELL_M).toInt()

    private fun cellOf(point: GeoPoint): Long = rowCol(point).let { (r, c) -> key(r, c) }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)

    companion object {
        const val CELL_M = 200.0
        private const val LISBON_LAT = 38.72
        private const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M
    }
}
