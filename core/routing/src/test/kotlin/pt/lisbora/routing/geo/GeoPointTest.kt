package pt.lisbora.routing.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeoPointTest {

    private val marquesDePombal = GeoPoint(38.7253, -9.1500)
    private val caisDoSodre = GeoPoint(38.7060, -9.1446)

    @Test
    fun `distance to itself is zero`() {
        assertEquals(0.0, marquesDePombal.distanceTo(marquesDePombal), 1e-9)
    }

    @Test
    fun `distance between two Lisbon landmarks is about 2_2 km`() {
        assertEquals(2_197.0, marquesDePombal.distanceTo(caisDoSodre), 15.0)
    }

    @Test
    fun `distance is symmetric`() {
        assertEquals(
            marquesDePombal.distanceTo(caisDoSodre),
            caisDoSodre.distanceTo(marquesDePombal),
            1e-9,
        )
    }

    @Test
    fun `rejects out of range coordinates`() {
        assertThrows<IllegalArgumentException> { GeoPoint(91.0, 0.0) }
        assertThrows<IllegalArgumentException> { GeoPoint(0.0, -181.0) }
    }
}
