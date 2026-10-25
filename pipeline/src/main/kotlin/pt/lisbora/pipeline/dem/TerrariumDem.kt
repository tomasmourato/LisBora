package pt.lisbora.pipeline.dem

import pt.lisbora.routing.geo.GeoBounds
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Fetches one map tile's bytes, or null when the server has no such tile. */
fun interface TileSource {
    fun fetch(z: Int, x: Int, y: Int): ByteArray?
}

/** Elevation in metres at a WGS84 position. */
fun interface ElevationModel {
    fun elevation(lat: Double, lon: Double): Double
}

/**
 * Digital elevation model read from Terrarium-encoded web-mercator tiles (elevation = R·256 + G + B/256 − 32768),
 * as published by Mapterhorn. For mainland Portugal those tiles come from the DGT LiDAR terrain model, so
 * streets are measured on the ground, not on rooftops. Tiles are cached on disk under [cacheDir]/z/x/y.webp and
 * sampled with bilinear interpolation.
 */
class TerrariumDem(
    private val source: TileSource,
    private val cacheDir: Path,
    private val zoom: Int = DEFAULT_ZOOM,
) : ElevationModel {

    private val tiles = HashMap<Long, FloatArray?>()
    private var tileSize = 0

    /** Downloads every tile covering [bounds] that is not cached yet; returns how many tiles cover it. */
    fun prefetch(bounds: GeoBounds, out: PrintStream): Int {
        val (x0, y0) = tileOf(bounds.north, bounds.west)
        val (x1, y1) = tileOf(bounds.south, bounds.east)
        var downloaded = 0
        for (x in x0..x1) for (y in y0..y1) {
            if (!tileFile(x, y).exists() && !missingFile(x, y).exists()) {
                cache(x, y)
                downloaded++
            }
        }
        val total = (x1 - x0 + 1) * (y1 - y0 + 1)
        out.println("DEM: $total mosaicos z$zoom ($downloaded descarregados agora)")
        return total
    }

    override fun elevation(lat: Double, lon: Double): Double {
        val size = ensureTileSize()
        // Global pixel coordinates; pixel centres sit at +0.5.
        val px = mercatorX(lon) * size - 0.5
        val py = mercatorY(lat) * size - 0.5
        val x0 = floor(px).toLong()
        val y0 = floor(py).toLong()
        val fx = px - x0
        val fy = py - y0
        val top = pixel(x0, y0) * (1 - fx) + pixel(x0 + 1, y0) * fx
        val bottom = pixel(x0, y0 + 1) * (1 - fx) + pixel(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    private fun pixel(gx: Long, gy: Long): Double {
        val size = tileSize
        val tile = tile((gx / size).toInt(), (gy / size).toInt())
            ?: throw IOException("No elevation tile z$zoom/${gx / size}/${gy / size}")
        return tile[((gy % size) * size + gx % size).toInt()].toDouble()
    }

    private fun tile(x: Int, y: Int): FloatArray? {
        val key = (x.toLong() shl 32) or y.toLong()
        return tiles.getOrPut(key) {
            if (missingFile(x, y).exists()) return@getOrPut null
            val file = tileFile(x, y)
            if (!file.exists()) cache(x, y)
            if (file.exists()) decode(file.readBytes()) else null
        }
    }

    private fun ensureTileSize(): Int {
        if (tileSize == 0) {
            val cached = Files.walk(cacheDir.resolve(zoom.toString())).use { paths ->
                paths.filter { it.toString().endsWith(".webp") }.findFirst().orElse(null)
            } ?: throw IOException("DEM cache is empty; call prefetch first")
            tileSize = decodeImage(cached.readBytes()).width
        }
        return tileSize
    }

    private fun cache(x: Int, y: Int) {
        val bytes = source.fetch(zoom, x, y)
        val target = if (bytes != null) tileFile(x, y) else missingFile(x, y)
        Files.createDirectories(target.parent)
        Files.write(target, bytes ?: ByteArray(0))
    }

    private fun decode(bytes: ByteArray): FloatArray {
        val image = decodeImage(bytes)
        val size = image.width
        if (tileSize == 0) tileSize = size
        if (image.height != size || size != tileSize) throw IOException("Unexpected DEM tile size ${image.width}×${image.height}")
        val rgb = image.getRGB(0, 0, size, size, null, 0, size)
        return FloatArray(rgb.size) { terrarium(rgb[it]).toFloat() }
    }

    private fun decodeImage(bytes: ByteArray) =
        ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("Cannot decode DEM tile (WebP reader missing?)")

    private fun tileFile(x: Int, y: Int): Path = cacheDir.resolve("$zoom/$x/$y.webp")
    private fun missingFile(x: Int, y: Int): Path = cacheDir.resolve("$zoom/$x/$y.missing")

    private fun tileOf(lat: Double, lon: Double): Pair<Int, Int> =
        floor(mercatorX(lon)).toInt() to floor(mercatorY(lat)).toInt()

    private fun mercatorX(lon: Double): Double = (lon + 180) / 360 * (1 shl zoom)

    private fun mercatorY(lat: Double): Double {
        val radians = Math.toRadians(lat)
        return (1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * (1 shl zoom)
    }

    companion object {
        const val DEFAULT_ZOOM = 14
        const val DEFAULT_CACHE_DIR = "pipeline/work/dem"
        const val TILE_URL = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"
        const val ATTRIBUTION = "Elevação: Mapterhorn, com dados da Direção-Geral do Território (LiDAR, CC BY 4.0) " +
            "e Copernicus GLO-30 (© DLR e.V. 2010-2014 e © Airbus Defence and Space GmbH 2014-2018, " +
            "fornecido no âmbito do COPERNICUS pela União Europeia e a ESA)"

        /** Decodes one Terrarium RGB pixel (alpha ignored) into metres. */
        fun terrarium(rgb: Int): Double {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return r * 256.0 + g + b / 256.0 - 32768
        }
    }
}
