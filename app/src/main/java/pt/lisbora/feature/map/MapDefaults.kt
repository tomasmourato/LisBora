package pt.lisbora.feature.map

import pt.lisbora.routing.geo.GeoPoint

object MapDefaults {
    /** Initial camera target (central Lisbon) until the user's location is known. */
    val LisbonCenter = GeoPoint(lat = 38.7223, lon = -9.1393)
}
