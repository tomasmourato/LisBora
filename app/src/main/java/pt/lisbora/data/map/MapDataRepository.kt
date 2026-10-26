package pt.lisbora.data.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pt.lisbora.data.datapack.AssetSource
import pt.lisbora.data.datapack.CycleNetworkMeta
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.datapack.DataPackStore
import pt.lisbora.data.datapack.InstalledDataPack
import pt.lisbora.di.IoDispatcher
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.MapStyleComposer
import pt.lisbora.feature.map.PoiPalette
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the map screen needs: ready-to-use styles plus the cycle network summary for the legend. */
data class MapData(
    val lightStyle: String,
    val darkStyle: String,
    val cycleNetworkMeta: CycleNetworkMeta,
    val hasBasemap: Boolean,
    /** The data pack has points of interest, so the legend offers their layers. */
    val hasPois: Boolean = false,
)

interface MapDataRepository {
    suspend fun load(): MapData
}

/** Installs the data pack bundled in the APK and composes the light/dark styles over it. Loaded once. */
@Singleton
class BundledMapDataRepository @Inject constructor(
    private val packs: DataPackStore,
    private val assets: AssetSource,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MapDataRepository {

    private val mutex = Mutex()
    private var cached: MapData? = null

    override suspend fun load(): MapData = mutex.withLock {
        cached ?: packs.pack().let { pack ->
            try {
                withContext(io) { build(pack) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                packs.reportBroken()
                throw e
            }
        }.also { cached = it }
    }

    private fun build(pack: InstalledDataPack): MapData {
        val cycleNetwork = pack.file(DataPackInstaller.CYCLE_NETWORK_FILE)
            ?: throw IOException("Data pack has no ${DataPackInstaller.CYCLE_NETWORK_FILE}")
        val meta = pack.file(DataPackInstaller.CYCLE_NETWORK_META_FILE)
            ?.let { CycleNetworkMeta.parse(it.readText()) }
            ?: CycleNetworkMeta(attribution = "", sourceLastEdit = null, lengthKmByClass = emptyMap())
        val basemap = pack.file(DataPackInstaller.BASEMAP_FILE)
        val pois = pack.file(DataPackInstaller.POIS_FILE)
        val gira = pack.file(DataPackInstaller.GIRA_FILE)
        val elevationAttribution = pack.file(DataPackInstaller.GRAPH_META_FILE)
            ?.let { runCatching { GraphMeta.elevationAttribution(it.readText()) }.getOrNull() }

        fun style(asset: String, palette: CycleNetworkPalette, poiPalette: PoiPalette) = MapStyleComposer.compose(
            baseStyle = assets.open(asset).bufferedReader().use { it.readText() },
            basemapPath = basemap?.absolutePath,
            cycleNetworkPath = cycleNetwork.absolutePath,
            cycleAttribution = meta.attribution,
            palette = palette,
            routeAttribution = elevationAttribution,
            poisPath = pois?.absolutePath,
            poiPalette = poiPalette,
            giraPath = gira?.absolutePath,
        )
        return MapData(
            lightStyle = style("map/style-light.json", CycleNetworkPalette.Light, PoiPalette.Light),
            darkStyle = style("map/style-dark.json", CycleNetworkPalette.Dark, PoiPalette.Dark),
            cycleNetworkMeta = meta,
            hasBasemap = basemap != null,
            hasPois = pois != null,
        )
    }
}

/** Reads what the map needs from `graph.meta.json`, written by the pipeline's `build`. */
internal object GraphMeta {
    /** Credit for the elevation data behind the route profiles (Mapterhorn, DGT, Copernicus). */
    fun elevationAttribution(json: String): String? =
        Json.parseToJsonElement(json).jsonObject["sources"]?.jsonObject
            ?.get("elevation")?.jsonObject?.get("attribution")?.jsonPrimitive?.contentOrNull
}
