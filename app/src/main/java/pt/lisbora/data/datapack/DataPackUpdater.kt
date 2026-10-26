package pt.lisbora.data.datapack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Clock

/** How the updater reaches the published packs. */
interface DataPackHttp {
    /** The body of [url] as text, or null when there is nothing there (404: no pack published yet). */
    @Throws(IOException::class)
    fun get(url: String): String?

    /** The body of [url] from byte [from] on when the server allows it; [Body.from] says where it really starts. */
    @Throws(IOException::class)
    fun open(url: String, from: Long): Body

    class Body(val from: Long, val stream: InputStream) : Closeable {
        override fun close() = stream.close()
    }
}

/** [DataPackHttp] over `HttpURLConnection`, following GitHub's redirects to its download servers. */
class UrlConnectionDataPackHttp(private val userAgent: String) : DataPackHttp {

    override fun get(url: String): String? {
        val connection = connect(url, from = 0)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} for $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    override fun open(url: String, from: Long): DataPackHttp.Body {
        val connection = connect(url, from)
        return when (connection.responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> DataPackHttp.Body(from, connection.inputStream)
            HttpURLConnection.HTTP_OK -> DataPackHttp.Body(0, connection.inputStream)
            else -> {
                val code = connection.responseCode
                connection.disconnect()
                throw IOException("HTTP $code for $url")
            }
        }
    }

    private fun connect(url: String, from: Long) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", userAgent)
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}

/** What the updater is doing, or what it found last, for the settings screen. */
sealed interface DataUpdateStatus {
    data object Idle : DataUpdateStatus
    data object Checking : DataUpdateStatus
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : DataUpdateStatus
    data object UpToDate : DataUpdateStatus

    /** Downloaded and checked; it takes over when the app starts again. */
    data class Ready(val dataVersion: String) : DataUpdateStatus

    /** The published data needs a newer version of the app. */
    data object NeedsAppUpdate : DataUpdateStatus
    data class Failed(val reason: Reason) : DataUpdateStatus {
        enum class Reason { NETWORK, NO_SPACE, CORRUPT }
    }
}

/**
 * Brings the data up to date without a new app version: reads the published manifest, downloads only the
 * files the phone does not have (resuming a download cut short), checks each against its size and SHA-256
 * and hands the complete pack to [DataPackStore], which switches to it at the next start.
 *
 * A pack this app cannot read (newer formats), one that is not newer than what the phone has, and one that
 * already failed here are left alone. Runs one check at a time, from the daily worker or the settings screen.
 */
class DataPackUpdater(
    private val store: DataPackStore,
    private val http: DataPackHttp,
    private val manifestUrl: String,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    private val running = Mutex()
    private val _status = MutableStateFlow<DataUpdateStatus>(DataUpdateStatus.Idle)
    val status: StateFlow<DataUpdateStatus> = _status.asStateFlow()

    /** Shows a pack already waiting for the next start (downloaded by an earlier process). */
    suspend fun refreshStatus() {
        if (_status.value != DataUpdateStatus.Idle) return
        store.state().pending?.let { _status.value = DataUpdateStatus.Ready(it.dataVersion) }
    }

    suspend fun update(): DataUpdateStatus = running.withLock {
        _status.value = DataUpdateStatus.Checking
        val result = try {
            withContext(io) { check() }
        } catch (e: CancellationException) {
            _status.value = DataUpdateStatus.Idle
            throw e
        } catch (_: IOException) {
            DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.NETWORK)
        }
        // A pack downloaded earlier is still waiting: say so rather than "up to date".
        val shown = if (result == DataUpdateStatus.UpToDate) store.state().pending?.let { DataUpdateStatus.Ready(it.dataVersion) } ?: result else result
        _status.value = shown
        shown
    }

    private suspend fun check(): DataUpdateStatus {
        val now = clock.millis()
        val remote = http.get(manifestUrl)?.let(DataPackManifest::parse)
        val state = store.state()
        if (remote == null || remote.dataVersion in state.rejected || !remote.isNewerThan(state.newest) || remote.files.isEmpty()) {
            store.updateState { it.copy(checkedAt = now) }
            return DataUpdateStatus.UpToDate
        }
        if (!remote.isSupported) {
            store.updateState { it.copy(checkedAt = now) }
            return DataUpdateStatus.NeedsAppUpdate
        }

        val storage = store.storage
        val missing = remote.files.filterNot(storage::has)
        val total = missing.sumOf { it.size }
        storage.clearStaging(missing)
        val alreadyStaged = missing.sumOf { entry -> storage.stagingFile(entry).takeIf { it.length() == entry.size }?.length() ?: storage.partFile(entry).length() }
        if (storage.root.usableSpace < total - alreadyStaged + FREE_SPACE_MARGIN_BYTES) return DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.NO_SPACE)

        var done = 0L
        for (entry in missing) {
            _status.value = DataUpdateStatus.Downloading(done, total)
            if (!download(entry) { bytes -> _status.value = DataUpdateStatus.Downloading(done + bytes, total) }) {
                return DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.CORRUPT)
            }
            done += entry.size
        }
        if (!store.commit(remote, missing, checkedAt = now)) return DataUpdateStatus.Failed(DataUpdateStatus.Failed.Reason.CORRUPT)
        return DataUpdateStatus.Ready(remote.dataVersion)
    }

    /**
     * Downloads one file into the staging folder, continuing a partial download; false when what arrived
     * does not match the manifest (it is deleted, so the next check starts it again).
     */
    private suspend fun download(entry: DataPackManifest.Entry, progress: (Long) -> Unit): Boolean {
        val storage = store.storage
        val complete = storage.stagingFile(entry)
        if (complete.length() == entry.size && sha256(complete) == entry.sha256) return true
        complete.delete()
        val part = storage.partFile(entry)
        if (part.length() > entry.size) part.delete()
        val url = URI(manifestUrl).resolve(entry.url ?: entry.path).toString()
        http.open(url, part.length()).use { body ->
            FileOutputStream(part, body.from > 0).use { output ->
                val buffer = ByteArray(1 shl 16)
                var written = body.from
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = body.stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    progress(written)
                }
            }
        }
        if (part.length() != entry.size || sha256(part) != entry.sha256) {
            part.delete()
            return false
        }
        if (!part.renameTo(complete)) throw IOException("Could not keep ${entry.path}")
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Room left for the rest of the phone after a download. */
        const val FREE_SPACE_MARGIN_BYTES = 50L * 1024 * 1024
    }
}
