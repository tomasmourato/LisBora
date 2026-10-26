package pt.lisbora.pipeline.search

import com.graphhopper.reader.ReaderElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.PlaceCategory
import kotlin.math.roundToLong

/**
 * The points of interest of `pois.geojson` (map layer and Descobrir): named places from the search scan
 * whose category has a [PoiKind], plus street furniture for cyclists that is rarely named.
 */
object PoiCatalog {

    const val FILE_NAME = "pois.geojson"

    /** Kinds collected whether or not they have a name. */
    fun unnamedKind(e: ReaderElement): PoiKind? {
        val amenity = e.getTag("amenity")
        return when {
            amenity == "drinking_water" || (amenity == "fountain" && e.hasTag("drinking_water", "yes")) -> PoiKind.DRINKING_WATER
            amenity == "bicycle_parking" -> PoiKind.BIKE_PARKING
            amenity == "bicycle_repair_station" -> PoiKind.BIKE_REPAIR
            e.hasTag("leisure", "playground") -> PoiKind.PLAYGROUND
            amenity == "bicycle_rental" && PlaceTags.name(e) == null -> PoiKind.BIKE_SHARE
            else -> null
        }
    }

    fun kindOf(category: PlaceCategory): PoiKind? = when (category) {
        PlaceCategory.VIEWPOINT -> PoiKind.VIEWPOINT
        PlaceCategory.PARK -> PoiKind.PARK
        PlaceCategory.BEACH -> PoiKind.BEACH
        PlaceCategory.MUSEUM -> PoiKind.MUSEUM
        PlaceCategory.MONUMENT -> PoiKind.MONUMENT
        PlaceCategory.ATTRACTION -> PoiKind.ATTRACTION
        PlaceCategory.BIKE_SHARE -> PoiKind.BIKE_SHARE
        PlaceCategory.BIKE_SHOP -> PoiKind.BIKE_SHOP
        else -> null
    }

    /**
     * Named places and street furniture together, without duplicates: the same kind a few metres apart,
     * or the same kind and name nearby (a place mapped both as a node and as an area).
     */
    fun collect(places: OsmPlaces): List<Poi> {
        val named = places.pois.mapNotNull { p -> kindOf(p.category)?.let { Poi(it, p.name, p.point, p.importance) } }
        val all = (named + places.amenities).sortedWith(compareByDescending<Poi> { it.name != null }.thenByDescending { it.importance })
        val kept = mutableListOf<Poi>()
        val byCell = HashMap<Triple<PoiKind, Long, Long>, MutableList<Poi>>()
        val byName = HashMap<Pair<PoiKind, String?>, MutableList<Poi>>()
        all.forEach { poi ->
            val cell = Triple(poi.kind, (poi.point.lat * CELL_PER_DEGREE).roundToLong(), (poi.point.lon * CELL_PER_DEGREE).roundToLong())
            val near = (-1..1).flatMap { dy -> (-1..1).mapNotNull { dx -> byCell[Triple(cell.first, cell.second + dy, cell.third + dx)] } }.flatten()
            if (near.any { it.point.distanceTo(poi.point) < DUPLICATE_M }) return@forEach
            val key = poi.kind to poi.name
            if (poi.name != null && byName[key].orEmpty().any { it.point.distanceTo(poi.point) < SAME_NAME_M }) return@forEach
            byCell.getOrPut(cell) { mutableListOf() } += poi
            if (poi.name != null) byName.getOrPut(key) { mutableListOf() } += poi
            kept += poi
        }
        return kept.sortedWith(compareBy({ it.kind.ordinal }, { -it.importance }, { it.name ?: "" }))
    }

    fun geoJson(pois: List<Poi>): JsonObject = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            pois.forEach { poi ->
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("properties") {
                            put("kind", poi.kind.code)
                            poi.name?.let { put("name", it) }
                            if (poi.importance > 0) put("importance", poi.importance)
                        }
                        putJsonObject("geometry") {
                            put("type", "Point")
                            putJsonArray("coordinates") {
                                add(JsonPrimitive(round6(poi.point.lon)))
                                add(JsonPrimitive(round6(poi.point.lat)))
                            }
                        }
                    },
                )
            }
        }
    }

    /** Reads a `pois.geojson` written by [geoJson]; features of unknown kinds are skipped. */
    fun parse(json: String): List<Poi> = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.mapNotNull { element ->
        val feature = element.jsonObject
        val properties = feature["properties"]!!.jsonObject
        val kind = PoiKind.fromCode(properties["kind"]!!.jsonPrimitive.content) ?: return@mapNotNull null
        val (lon, lat) = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.double }
        Poi(kind, properties["name"]?.jsonPrimitive?.contentOrNull, GeoPoint(lat, lon), properties["importance"]?.jsonPrimitive?.intOrNull ?: 0)
    }

    private fun round6(value: Double) = (value * 1e6).roundToLong() / 1e6

    private const val DUPLICATE_M = 8.0
    private const val SAME_NAME_M = 250.0
    private const val CELL_PER_DEGREE = 5_000.0
}
