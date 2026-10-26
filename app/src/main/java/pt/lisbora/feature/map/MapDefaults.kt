package pt.lisbora.feature.map

import pt.lisbora.routing.geo.GeoPoint

object MapDefaults {
    /** Initial camera target (central Lisbon) until the user's location is known. */
    val LisbonCenter = GeoPoint(lat = 38.7223, lon = -9.1393)

    const val INITIAL_ZOOM = 12.5
    const val MIN_ZOOM = 10.5

    /** The basemap stops at z15; MapLibre overzooms vector tiles up to here. */
    const val MAX_ZOOM = 19.0
    const val LOCATION_ZOOM = 15.5

    /** Close enough to see the next junction while riding. */
    const val NAVIGATION_ZOOM = 16.5

    /** Tilted while riding, so the road ahead takes most of the map. */
    const val NAVIGATION_TILT = 45.0
}
