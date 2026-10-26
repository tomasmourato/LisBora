package pt.lisbora.routing.io

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Buffered little-endian writer for LisBora's binary data files (DataOutputStream is big-endian). */
internal class LittleEndianWriter(private val output: OutputStream) {
    private val buffer = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

    private fun ensure(bytes: Int) {
        if (buffer.remaining() < bytes) drain()
    }

    private fun drain() {
        output.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    fun int(value: Int) { ensure(4); buffer.putInt(value) }
    fun double(value: Double) { ensure(8); buffer.putDouble(value) }
    fun ints(values: IntArray) = values.forEach(::int)
    fun bytes(values: ByteArray) = values.forEach { ensure(1); buffer.put(it) }
    fun shorts(values: ShortArray) = values.forEach { ensure(2); buffer.putShort(it) }
    fun floats(values: FloatArray) = values.forEach { ensure(4); buffer.putFloat(it) }
    fun longs(values: LongArray) = values.forEach { ensure(8); buffer.putLong(it) }

    /** An i32 byte length followed by UTF-8. */
    fun string(value: String) {
        val encoded = value.encodeToByteArray()
        int(encoded.size)
        bytes(encoded)
    }

    fun flush() {
        drain()
        output.flush()
    }
}

internal fun ByteBuffer.count(): Int = int.also { if (it < 0) throw IOException("Negative count in data file") }

internal fun ByteBuffer.string(): String {
    val length = count()
    val bytes = ByteArray(length)
    get(bytes)
    return bytes.decodeToString()
}

internal fun ByteBuffer.ints(size: Int) = IntArray(size).also { asIntBuffer().get(it); advance(size * 4) }
internal fun ByteBuffer.shorts(size: Int) = ShortArray(size).also { asShortBuffer().get(it); advance(size * 2) }
internal fun ByteBuffer.floats(size: Int) = FloatArray(size).also { asFloatBuffer().get(it); advance(size * 4) }
internal fun ByteBuffer.longs(size: Int) = LongArray(size).also { asLongBuffer().get(it); advance(size * 8) }
internal fun ByteBuffer.bytes(size: Int) = ByteArray(size).also { get(it) }

private fun ByteBuffer.advance(bytes: Int) {
    position(position() + bytes)
}
