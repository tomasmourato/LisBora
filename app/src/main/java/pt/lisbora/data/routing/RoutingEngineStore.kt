package pt.lisbora.data.routing

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.datapack.DataPackStore
import pt.lisbora.di.DefaultDispatcher
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import javax.inject.Inject
import javax.inject.Singleton

/** The routing engine over the installed graph (~8 MB), read once and shared by A→B and Descobrir. */
@Singleton
class RoutingEngineStore @Inject constructor(
    private val packs: DataPackStore,
    @param:DefaultDispatcher private val compute: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var engine: RoutingEngine? = null

    suspend fun engine(): RoutingEngine = mutex.withLock {
        engine ?: run {
            val file = packs.pack().file(DataPackInstaller.GRAPH_FILE)
                ?: throw RoutingUnavailableException("Data pack has no ${DataPackInstaller.GRAPH_FILE}")
            withContext(compute) {
                val started = System.nanoTime()
                RoutingEngine(RoutingGraph(file.inputStream().buffered().use(GraphFormat::read))).also {
                    Log.i(TAG, "Graph loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
        }.also { engine = it }
    }

    private companion object {
        const val TAG = "LisBoraRouting"
    }
}
