package pt.lisbora.pipeline.dem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp

class TerrariumDemTest {

    @Test
    fun `decodes terrarium pixels`() {
        assertEquals(0.0, TerrariumDem.terrarium(rgb(128, 0, 0)), 1e-9)
        assertEquals(14.5, TerrariumDem.terrarium(rgb(128, 14, 128)), 1e-9)
        assertEquals(-10.0, TerrariumDem.terrarium(rgb(127, 246, 0)), 1e-9)
    }

    @Test
    fun `samples tiles bilinearly and caches them on disk`(@TempDir cache: Path) {
        val zoom = 2
        val size = 4
        var fetches = 0
        // Tile (1, 1): elevation = 10 × column of the pixel.
        val source = TileSource { z, x, y ->
            fetches++
            if (z != zoom || x != 1 || y != 1) return@TileSource null
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            for (py in 0 until size) for (px in 0 until size) image.setRGB(px, py, encode(10.0 * px))
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
        val dem = TerrariumDem(source, cache, zoom)
        // Pixel column 1.5 of tile x = 1 is halfway between columns 1 and 2: 15 m.
        val lon = (1 + 2.0 / size) / (1 shl zoom) * 360 - 180
        val lat = tileLat(zoom, 1 + 1.5 / size)
        dem.prefetch(pt.lisbora.routing.geo.GeoBounds(lon - 1, lat - 1, lon + 1, lat + 1), PrintStream(ByteArrayOutputStream()))
        assertEquals(15.0, dem.elevation(lat, lon), 0.5)
        assertEquals(true, cache.resolve("$zoom/1/1.webp").exists())

        val before = fetches
        TerrariumDem(source, cache, zoom).elevation(lat, lon)
        assertEquals(before, fetches)
    }

    private fun tileLat(zoom: Int, y: Double): Double {
        val n = PI - 2 * PI * y / (1 shl zoom)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private fun encode(metres: Double): Int {
        val value = metres + 32768
        val r = (value / 256).toInt()
        val g = (value - r * 256).toInt()
        val b = ((value - r * 256 - g) * 256).toInt()
        return rgb(r, g, b)
    }
}
