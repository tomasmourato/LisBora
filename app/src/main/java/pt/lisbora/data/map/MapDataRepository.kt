package pt.lisbora.data.map

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.lisbora.data.datapack.AssetSource
import pt.lisbora.data.datapack.CycleNetworkMeta
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.di.IoDispatcher
import pt.lisbora.feature.map.CycleNetworkPalette
import pt.lisbora.feature.map.MapStyleComposer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the map screen needs: ready-to-use styles plus the cycle network summary for the legend. */
data class MapData(
    val lightStyle: String,
    val darkStyle: String,
    val cycleNetworkMeta: CycleNetworkMeta,
    val hasBasemap: Boolean,
)

interface MapDataRepository {
    suspend fun load(): MapData
}

/** Installs the data pack bundled in the APK and composes the light/dark styles over it. Loaded once. */
@Singleton
class BundledMapDataRepository @Inject constructor(
    private val installer: DataPackInstaller,
    private val assets: AssetSource,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : MapDataRepository {

    private val mutex = Mutex()
    private var cached: MapData? = null

    override suspend fun load(): MapData = mutex.withLock {
        cached ?: withContext(io) { build() }.also { cached = it }
    }

    private fun build(): MapData {
        val pack = installer.install()
        val cycleNetwork = pack.file(DataPackInstaller.CYCLE_NETWORK_FILE)
            ?: throw IOException("Data pack has no ${DataPackInstaller.CYCLE_NETWORK_FILE}")
        val meta = pack.file(DataPackInstaller.CYCLE_NETWORK_META_FILE)
            ?.let { CycleNetworkMeta.parse(it.readText()) }
            ?: CycleNetworkMeta(attribution = "", sourceLastEdit = null, lengthKmByClass = emptyMap())
        val basemap = pack.file(DataPackInstaller.BASEMAP_FILE)

        fun style(asset: String, palette: CycleNetworkPalette) = MapStyleComposer.compose(
            baseStyle = assets.open(asset).bufferedReader().use { it.readText() },
            basemapPath = basemap?.absolutePath,
            cycleNetworkPath = cycleNetwork.absolutePath,
            cycleAttribution = meta.attribution,
            palette = palette,
        )
        return MapData(
            lightStyle = style("map/style-light.json", CycleNetworkPalette.Light),
            darkStyle = style("map/style-dark.json", CycleNetworkPalette.Dark),
            cycleNetworkMeta = meta,
            hasBasemap = basemap != null,
        )
    }
}
