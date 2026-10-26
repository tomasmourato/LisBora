package pt.lisbora.data.gira

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.datapack.DataPackStore
import pt.lisbora.data.discover.PoiGeoJson
import pt.lisbora.di.IoDispatcher
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.geo.GeoPoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A GIRA dock station. [number] is EMEL's station number when known; [bikes], [freeDocks] and [docks]
 * are null when there is no live data (the bundled stations only say where they are). [inService] is
 * false while EMEL or the system has closed it.
 */
data class GiraStation(
    val number: Int?,
    val name: String,
    val point: GeoPoint,
    val bikes: Int? = null,
    val freeDocks: Int? = null,
    val docks: Int? = null,
    /** Where it is ("Avenida da Universidade Técnica / FMV"), when known. */
    val place: String? = null,
    val inService: Boolean = true,
) {
    val isLive: Boolean get() = bikes != null
    val hasBike: Boolean get() = inService && (bikes == null || bikes > 0)
    val hasFreeDock: Boolean get() = inService && (freeDocks == null || freeDocks > 0)
}

interface GiraRepository {
    /** GIRA stations, with bikes and free docks when a live source provides them. */
    suspend fun stations(): List<GiraStation>
}

/**
 * GIRA stations from the data pack: EMEL's list (`gira.geojson`, refreshed with each data update), or in
 * packs without it OpenStreetMap's (`amenity=bicycle_rental` of the GIRA network). Read once.
 */
@Singleton
class BundledGiraRepository @Inject constructor(
    private val packs: DataPackStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : GiraRepository {

    private val mutex = Mutex()
    private var cached: List<GiraStation>? = null

    override suspend fun stations(): List<GiraStation> = mutex.withLock {
        cached ?: run {
            val pack = packs.pack()
            withContext(io) {
                pack.file(DataPackInstaller.GIRA_FILE)?.let { GiraGeoJson.parse(it.readText()) }
                    ?: pack.file(DataPackInstaller.POIS_FILE)?.let { GiraStations.from(PoiGeoJson.parse(it.readText())) }
                    ?: emptyList()
            }
        }.also { cached = it }
    }
}

/** Reads `gira.geojson`, written by the pipeline's `gira`: where each station is and its docks, no live counts. */
internal object GiraGeoJson {
    fun parse(json: String): List<GiraStation> = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.map { element ->
        val feature = element.jsonObject
        val properties = feature["properties"]!!.jsonObject
        val (lon, lat) = feature["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.double }
        GiraStation(
            number = properties["number"]?.jsonPrimitive?.intOrNull,
            name = properties["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            point = GeoPoint(lat, lon),
            docks = properties["docks"]?.jsonPrimitive?.intOrNull,
            place = properties["place"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

object GiraStations {
    /** "Bicicletas Gira Estação 101" → 101. */
    private val NUMBER = Regex("""(\d+)\s*$""")

    /** The bike-share points of the GIRA network (other rentals, e.g. tourist shops, are left out). */
    fun from(pois: List<Poi>): List<GiraStation> = pois
        .filter { it.kind == PoiKind.BIKE_SHARE && it.name?.contains("gira", ignoreCase = true) == true }
        .map { poi ->
            val name = poi.name.orEmpty()
            GiraStation(number = NUMBER.find(name)?.groupValues?.get(1)?.toIntOrNull(), name = name, point = poi.point)
        }

    /**
     * The station nearest to [point] within [maxDistanceM] that passes [accept] (a bike to take, a dock
     * to return it to), or null.
     */
    fun nearest(stations: List<GiraStation>, point: GeoPoint, maxDistanceM: Double, accept: (GiraStation) -> Boolean = { true }): GiraStation? =
        stations.filter(accept).minByOrNull { it.point.distanceTo(point) }?.takeIf { it.point.distanceTo(point) <= maxDistanceM }

    /**
     * Where to swap bikes near [point] before a trip's 45 minutes are up: the nearest station with a free
     * dock and another bike to take, else the nearest with a free dock.
     */
    fun forSwap(stations: List<GiraStation>, point: GeoPoint, maxDistanceM: Double): GiraStation? =
        nearest(stations, point, maxDistanceM) { it.hasFreeDock && it.hasBike } ?: nearest(stations, point, maxDistanceM) { it.hasFreeDock }
}
