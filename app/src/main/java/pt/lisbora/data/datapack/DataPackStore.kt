package pt.lisbora.data.datapack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.lisbora.di.IoDispatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the data pack once per process and hands the same copy to the map, router and search. It
 * also owns the pack state on disk, so the updater never changes it while the pack is being chosen.
 */
@Singleton
class DataPackStore @Inject constructor(
    private val installer: DataPackInstaller,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var installed: InstalledDataPack? = null

    val storage: DataPackStorage get() = installer.storage

    suspend fun pack(): InstalledDataPack = mutex.withLock {
        installed ?: withContext(io) { installer.install() }.also { installed = it }
    }

    suspend fun state(): PackState = mutex.withLock { withContext(io) { storage.readState() } }

    /** Updates the state on disk; [change] runs with nothing else touching the packs. */
    suspend fun updateState(change: (PackState) -> PackState): PackState = mutex.withLock {
        withContext(io) { change(storage.readState()).also(storage::writeState) }
    }

    /**
     * Moves a downloaded pack's files into place and keeps it for the next start. Returns false when one of
     * its files went missing meanwhile (then nothing changes and the next check downloads it again).
     */
    suspend fun commit(pack: DataPackManifest, downloaded: List<DataPackManifest.Entry>, checkedAt: Long): Boolean = mutex.withLock {
        withContext(io) {
            downloaded.forEach(storage::commit)
            if (!storage.isComplete(pack)) return@withContext false
            storage.writeState(storage.readState().copy(pending = pack, checkedAt = checkedAt))
            true
        }
    }

    /**
     * A file of the pack in use could not be read: the next start goes back to the pack before it. A pack
     * bundled in the APK has none before it, so this only helps after an update; it is harmless otherwise.
     */
    suspend fun reportBroken() {
        val version = installed?.manifest?.dataVersion ?: return
        try {
            updateState { state -> if (state.active?.dataVersion == version && state.previous != null) state.copy(broken = true) else state }
        } catch (_: IOException) {
            // Nothing more to do: the error is already on screen.
        }
    }
}
