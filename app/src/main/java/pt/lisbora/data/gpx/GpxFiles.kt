package pt.lisbora.data.gpx

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import pt.lisbora.di.IoDispatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** GPX files the rider chose (Storage Access Framework): rides exported, routes imported. */
interface GpxFiles {
    @Throws(IOException::class)
    suspend fun write(uri: String, gpx: String)

    /** The text of the file at [uri], up to [MAX_BYTES]. */
    @Throws(IOException::class)
    suspend fun read(uri: String): String

    companion object {
        /** A long ride with a fix a second is a few MB; anything much bigger is not a GPX to follow. */
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}

@Singleton
class ContentGpxFiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : GpxFiles {
    override suspend fun write(uri: String, gpx: String) = withContext(io) {
        val stream = context.contentResolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("Cannot open $uri")
        stream.use { it.write(gpx.toByteArray()) }
    }

    override suspend fun read(uri: String): String = withContext(io) {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot open $uri")
        stream.use { input ->
            val bytes = input.readNBytes(GpxFiles.MAX_BYTES + 1)
            if (bytes.size > GpxFiles.MAX_BYTES) throw IOException("File too large")
            String(bytes)
        }
    }
}
