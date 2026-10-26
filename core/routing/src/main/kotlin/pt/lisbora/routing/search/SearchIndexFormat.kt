package pt.lisbora.routing.search

import pt.lisbora.routing.io.LittleEndianWriter
import pt.lisbora.routing.io.bytes
import pt.lisbora.routing.io.count
import pt.lisbora.routing.io.ints
import pt.lisbora.routing.io.string
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary `search.lbs` format (little-endian):
 *
 * ```
 * "LBSR" formatVersion:i32 dataVersion:str
 * entryCount contextCount tokenCount postingCount  (i32 each)
 * contexts[str] names[str]
 * latE7[i32] lonE7[i32] category[i8] importance[i8] contextId[i32] wordCount[i8] firstToken[i32]
 * tokens[str] postingStart[i32 × (tokenCount+1)] postings[i32]
 * "LBSE"
 * ```
 * Strings are an i32 byte length followed by UTF-8. Bump [FORMAT_VERSION] on any incompatible change.
 */
object SearchIndexFormat {

    const val FORMAT_VERSION = 1
    const val FILE_NAME = "search.lbs"
    private const val MAGIC = 0x5253424C // "LBSR"
    private const val END_MAGIC = 0x4553424C // "LBSE"

    fun write(data: SearchIndexData, output: OutputStream) {
        val w = LittleEndianWriter(output)
        w.int(MAGIC)
        w.int(FORMAT_VERSION)
        w.string(data.dataVersion)
        w.int(data.entryCount)
        w.int(data.contexts.size)
        w.int(data.tokens.size)
        w.int(data.postings.size)
        data.contexts.forEach(w::string)
        data.names.forEach(w::string)
        w.ints(data.latE7); w.ints(data.lonE7)
        w.bytes(data.categories); w.bytes(data.importance)
        w.ints(data.contextIds); w.bytes(data.wordCounts); w.ints(data.firstTokens)
        data.tokens.forEach(w::string)
        w.ints(data.postingStart); w.ints(data.postings)
        w.int(END_MAGIC)
        w.flush()
    }

    fun toBytes(data: SearchIndexData): ByteArray = ByteArrayOutputStream().also { write(data, it) }.toByteArray()

    /** Reads a whole index; throws [IOException] on a foreign, truncated or newer-format file. */
    fun read(input: InputStream): SearchIndexData = read(input.readBytes())

    fun read(bytes: ByteArray): SearchIndexData {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            if (buffer.int != MAGIC) throw IOException("Not a LisBora search index")
            val version = buffer.int
            if (version != FORMAT_VERSION) throw IOException("Unsupported search index format $version (expected $FORMAT_VERSION)")
            val dataVersion = buffer.string()
            val entries = buffer.count()
            val contextCount = buffer.count()
            val tokenCount = buffer.count()
            val postingCount = buffer.count()
            val contexts = List(contextCount) { buffer.string() }
            val names = List(entries) { buffer.string() }
            val lat = buffer.ints(entries)
            val lon = buffer.ints(entries)
            val categories = buffer.bytes(entries)
            val importance = buffer.bytes(entries)
            val contextIds = buffer.ints(entries)
            val wordCounts = buffer.bytes(entries)
            val firstTokens = buffer.ints(entries)
            val tokens = List(tokenCount) { buffer.string() }
            val postingStart = buffer.ints(tokenCount + 1)
            val postings = buffer.ints(postingCount)
            if (buffer.int != END_MAGIC) throw IOException("Search index is corrupt (bad end marker)")
            return SearchIndexData(
                dataVersion, names, categories, lat, lon, importance, contextIds, contexts, wordCounts, firstTokens,
                tokens, postingStart, postings,
            )
        } catch (e: BufferUnderflowException) {
            throw IOException("Search index is truncated", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Search index is inconsistent: ${e.message}", e)
        }
    }
}
