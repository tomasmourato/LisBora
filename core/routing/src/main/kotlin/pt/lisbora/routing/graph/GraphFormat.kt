package pt.lisbora.routing.graph

import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.io.LittleEndianWriter
import pt.lisbora.routing.io.count
import pt.lisbora.routing.io.floats
import pt.lisbora.routing.io.ints
import pt.lisbora.routing.io.longs
import pt.lisbora.routing.io.shorts
import pt.lisbora.routing.io.string
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary `graph.lbg` format (little-endian):
 *
 * ```
 * "LBGR" formatVersion:i32 dataVersion:str bounds:4×f64
 * nodeCount edgeCount pillarCount nameCount restrictionCount  (i32 each)
 * nodeLat[i32] nodeLon[i32] nodeEle[i16]
 * edgeBase[i32] edgeAdj[i32] edgeLength[f32] edgeFlags[i64] edgeName[i32] geometryStart[i32 × (edgeCount+1)]
 * pillarLat[i32] pillarLon[i32] pillarEle[i16]
 * names[str] restrictionFrom[i32] restrictionVia[i32] restrictionTo[i32]
 * "LBGE"
 * ```
 * Strings are an i32 byte length followed by UTF-8. Bump [FORMAT_VERSION] on any incompatible change.
 */
object GraphFormat {

    const val FORMAT_VERSION = 1
    const val FILE_NAME = "graph.lbg"
    private const val MAGIC = 0x5247424C // "LBGR"
    private const val END_MAGIC = 0x4547424C // "LBGE"

    fun write(data: GraphData, output: OutputStream) {
        val w = LittleEndianWriter(output)
        w.int(MAGIC)
        w.int(FORMAT_VERSION)
        w.string(data.dataVersion)
        with(data.bounds) { w.double(west); w.double(south); w.double(east); w.double(north) }
        w.int(data.nodeCount)
        w.int(data.edgeCount)
        w.int(data.pillarCount)
        w.int(data.names.size)
        w.int(data.restrictionCount)
        w.ints(data.nodeLat); w.ints(data.nodeLon); w.shorts(data.nodeEle)
        w.ints(data.edgeBase); w.ints(data.edgeAdj); w.floats(data.edgeLength); w.longs(data.edgeFlags)
        w.ints(data.edgeName); w.ints(data.geometryStart)
        w.ints(data.pillarLat); w.ints(data.pillarLon); w.shorts(data.pillarEle)
        data.names.forEach(w::string)
        w.ints(data.restrictionFrom); w.ints(data.restrictionVia); w.ints(data.restrictionTo)
        w.int(END_MAGIC)
        w.flush()
    }

    fun toBytes(data: GraphData): ByteArray = ByteArrayOutputStream().also { write(data, it) }.toByteArray()

    /** Reads a whole graph; throws [IOException] on a foreign, truncated or newer-format file. */
    fun read(input: InputStream): GraphData = read(input.readBytes())

    fun read(bytes: ByteArray): GraphData {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            if (buffer.int != MAGIC) throw IOException("Not a LisBora graph file")
            val version = buffer.int
            if (version != FORMAT_VERSION) throw IOException("Unsupported graph format $version (expected $FORMAT_VERSION)")
            val dataVersion = buffer.string()
            val bounds = GeoBounds(west = buffer.double, south = buffer.double, east = buffer.double, north = buffer.double)
            val nodes = buffer.count()
            val edges = buffer.count()
            val pillars = buffer.count()
            val nameCount = buffer.count()
            val restrictions = buffer.count()
            val nodeLat = buffer.ints(nodes)
            val nodeLon = buffer.ints(nodes)
            val nodeEle = buffer.shorts(nodes)
            val edgeBase = buffer.ints(edges)
            val edgeAdj = buffer.ints(edges)
            val edgeLength = buffer.floats(edges)
            val edgeFlags = buffer.longs(edges)
            val edgeName = buffer.ints(edges)
            val geometryStart = buffer.ints(edges + 1)
            val pillarLat = buffer.ints(pillars)
            val pillarLon = buffer.ints(pillars)
            val pillarEle = buffer.shorts(pillars)
            val names = List(nameCount) { buffer.string() }
            val restrictionFrom = buffer.ints(restrictions)
            val restrictionVia = buffer.ints(restrictions)
            val restrictionTo = buffer.ints(restrictions)
            if (buffer.int != END_MAGIC) throw IOException("Graph file is corrupt (bad end marker)")
            return GraphData(
                dataVersion, bounds, nodeLat, nodeLon, nodeEle, edgeBase, edgeAdj, edgeLength, edgeFlags, edgeName,
                geometryStart, pillarLat, pillarLon, pillarEle, names, restrictionFrom, restrictionVia, restrictionTo,
            )
        } catch (e: BufferUnderflowException) {
            throw IOException("Graph file is truncated", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Graph file is inconsistent: ${e.message}", e)
        }
    }
}
