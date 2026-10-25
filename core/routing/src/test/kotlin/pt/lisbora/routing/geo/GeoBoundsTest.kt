package pt.lisbora.routing.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.lisbora.routing.model.CycleInfraClass

class GeoBoundsTest {

    @Test
    fun `service area covers central Lisbon, Belem and Cacilhas but not Sintra`() {
        assertTrue(GeoPoint(38.7223, -9.1393) in ServiceArea.BOUNDS)
        assertTrue(GeoPoint(38.6916, -9.2160) in ServiceArea.BOUNDS)
        assertTrue(GeoPoint(38.6866, -9.1463) in ServiceArea.BOUNDS)
        assertFalse(GeoPoint(38.8029, -9.3817) in ServiceArea.BOUNDS)
    }

    @Test
    fun `bbox string uses west,south,east,north order`() {
        assertEquals("-9.3,38.655,-9.05,38.83", ServiceArea.BOUNDS.toBboxString())
    }

    @Test
    fun `rejects inverted bounds`() {
        assertThrows<IllegalArgumentException> { GeoBounds(west = 1.0, south = 0.0, east = 0.0, north = 1.0) }
    }

    @Test
    fun `infra class ids round-trip`() {
        CycleInfraClass.entries.forEach { assertEquals(it, CycleInfraClass.fromId(it.id)) }
        assertNull(CycleInfraClass.fromId("unknown"))
    }
}
