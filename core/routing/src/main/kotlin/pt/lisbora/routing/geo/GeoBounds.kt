package pt.lisbora.routing.geo

/** Axis-aligned WGS84 bounding box in decimal degrees. */
data class GeoBounds(val west: Double, val south: Double, val east: Double, val north: Double) {

    init {
        require(west < east) { "west ($west) must be < east ($east)" }
        require(south < north) { "south ($south) must be < north ($north)" }
    }

    val center: GeoPoint get() = GeoPoint((south + north) / 2, (west + east) / 2)

    operator fun contains(point: GeoPoint): Boolean =
        point.lon in west..east && point.lat in south..north

    /** Comma-separated `west,south,east,north`, the order used by pmtiles and most tools. */
    fun toBboxString(): String = "$west,$south,$east,$north"
}

/** The area LisBora covers: Lisbon plus a margin (Algés, Oeiras riverside, Amadora, Odivelas, Loures south, Almada riverside). */
object ServiceArea {
    val BOUNDS = GeoBounds(west = -9.30, south = 38.655, east = -9.05, north = 38.83)
}
