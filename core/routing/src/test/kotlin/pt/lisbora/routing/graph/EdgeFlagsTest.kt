package pt.lisbora.routing.graph

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.lisbora.routing.model.CycleInfraClass

class EdgeFlagsTest {

    @Test
    fun `every field round-trips`() {
        val flags = EdgeFlags.of(
            forward = TravelMode.RIDE,
            backward = TravelMode.PUSH,
            infra = EdgeInfra.SHARED_WITH_PEDESTRIANS,
            road = RoadKind.FERRY,
            surface = SurfaceKind.COBBLESTONE,
            tram = true,
            bridge = true,
            tunnel = false,
            roundabout = true,
            trafficSignals = true,
            stress = 3,
            maxSpeedKmh = 50,
            lanes = 2,
            riverside = true,
            green = false,
            viewpoint = true,
            lisbon = true,
        )
        assertEquals(TravelMode.RIDE, flags.mode(reverse = false))
        assertEquals(TravelMode.PUSH, flags.mode(reverse = true))
        assertEquals(EdgeInfra.SHARED_WITH_PEDESTRIANS, flags.infra)
        assertEquals(RoadKind.FERRY, flags.road)
        assertEquals(SurfaceKind.COBBLESTONE, flags.surface)
        assertTrue(flags.tram && flags.bridge && flags.roundabout && flags.trafficSignals && flags.isFerry)
        assertFalse(flags.tunnel || flags.isSteps)
        assertEquals(3, flags.stress)
        assertEquals(50, flags.maxSpeedKmh)
        assertEquals(2, flags.lanes)
        assertTrue(flags.riverside && flags.viewpoint && flags.lisbon)
        assertFalse(flags.green)
    }

    @Test
    fun `enum values fit their bit fields`() {
        // Last value of every enum, plus maximum numeric fields, must not bleed into neighbours.
        val flags = EdgeFlags.of(
            forward = TravelMode.entries.last(),
            backward = TravelMode.entries.last(),
            infra = EdgeInfra.entries.last(),
            road = RoadKind.entries.last(),
            surface = SurfaceKind.entries.last(),
            stress = 4,
            maxSpeedKmh = EdgeFlags.MAX_SPEED_KMH,
            lanes = EdgeFlags.MAX_LANES,
        )
        assertEquals(EdgeInfra.entries.last(), flags.infra)
        assertEquals(RoadKind.entries.last(), flags.road)
        assertEquals(SurfaceKind.entries.last(), flags.surface)
        assertFalse(flags.tram)
        assertEquals(4, flags.stress)
        assertEquals(EdgeFlags.MAX_SPEED_KMH, flags.maxSpeedKmh)
        assertEquals(EdgeFlags.MAX_LANES, flags.lanes)
        assertFalse(flags.riverside || flags.green || flags.viewpoint || flags.lisbon)
        assertEquals(0L, flags.bits ushr 31)
    }

    @Test
    fun `reserved bits are ignored`() {
        val flags = EdgeFlags.of(TravelMode.RIDE, TravelMode.NONE, stress = 2)
        val withReserved = EdgeFlags(flags.bits or (0xFFL shl 40))
        assertEquals(flags.toString(), withReserved.toString())
    }

    @Test
    fun `infra maps to CML classes`() {
        CycleInfraClass.entries.forEach { assertEquals(it, EdgeInfra.of(it).cmlClass) }
        assertEquals(null, EdgeInfra.OSM_CYCLEWAY.cmlClass)
    }

    @Test
    fun `stress outside 1 to 4 is rejected`() {
        assertThrows<IllegalArgumentException> { EdgeFlags.of(TravelMode.RIDE, TravelMode.RIDE, stress = 5) }
    }
}
