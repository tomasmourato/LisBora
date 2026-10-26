package pt.lisbora.routing.io

import pt.lisbora.routing.Route
import pt.lisbora.routing.geo.GeoPoint
import java.time.Instant
import java.util.Locale

/** A recorded position: where, when (epoch milliseconds) and, when known, at what elevation. */
data class TrackPoint(val point: GeoPoint, val timeMs: Long? = null, val elevation: Double? = null)

/** GPX 1.1, the format other cycling apps and GPS units read: a ride as a track, a planned route as a route. */
object Gpx {

    /** A GPX file with the [track] ridden (if any) and the planned [route] (if any), named [name]. */
    fun write(name: String, track: List<TrackPoint> = emptyList(), route: Route? = null): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="LisBora" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>").append(escape(name)).append("</name></metadata>\n")
        if (route != null && route.points.isNotEmpty()) {
            append("  <rte><name>").append(escape(name)).append("</name>\n")
            route.points.forEachIndexed { i, p -> point("rtept", p, route.elevations.getOrNull(i), null, indent = "    ") }
            append("  </rte>\n")
        }
        if (track.isNotEmpty()) {
            append("  <trk><name>").append(escape(name)).append("</name><trkseg>\n")
            track.forEach { point("trkpt", it.point, it.elevation, it.timeMs, indent = "    ") }
            append("  </trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    private fun StringBuilder.point(tag: String, p: GeoPoint, elevation: Double?, timeMs: Long?, indent: String) {
        append(indent).append('<').append(tag)
            .append(" lat=\"").append(String.format(Locale.ROOT, "%.6f", p.lat))
            .append("\" lon=\"").append(String.format(Locale.ROOT, "%.6f", p.lon)).append('"')
        if (elevation == null && timeMs == null) {
            append("/>\n")
            return
        }
        append('>')
        elevation?.let { append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
        timeMs?.let { append("<time>").append(Instant.ofEpochMilli(it)).append("</time>") }
        append("</").append(tag).append(">\n")
    }

    /**
     * The points of a GPX file, in order: its track points, else its route points, else its waypoints.
     * Tolerant of attribute order and namespaces; points with bad coordinates are skipped.
     */
    fun parse(xml: String): List<GeoPoint> {
        for (tag in listOf("trkpt", "rtept", "wpt")) {
            val points = Regex("""<(?:\w+:)?$tag\b([^>]*)>""").findAll(xml).mapNotNull { match ->
                val attributes = match.groupValues[1]
                val lat = attribute(attributes, "lat")?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = attribute(attributes, "lon")?.toDoubleOrNull() ?: return@mapNotNull null
                if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
            }.toList()
            if (points.isNotEmpty()) return points
        }
        return emptyList()
    }

    /** The file's name for the ride (its metadata, track or route name), or null. */
    fun name(xml: String): String? =
        Regex("""<(?:\w+:)?name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</(?:\w+:)?name>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    private fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun attribute(attributes: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").find(attributes)?.groupValues?.get(1)

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
