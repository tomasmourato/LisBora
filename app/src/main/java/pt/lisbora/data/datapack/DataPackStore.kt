package pt.lisbora.data.datapack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.lisbora.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/** Installs the bundled data pack once per process and hands the same copy to the map, router and search. */
@Singleton
class DataPackStore @Inject constructor(
    private val installer: DataPackInstaller,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var installed: InstalledDataPack? = null

    suspend fun pack(): InstalledDataPack = mutex.withLock {
        installed ?: withContext(io) { installer.install() }.also { installed = it }
    }
}
