package pt.lisbora.routing.weighting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BikeWeightingTest {

    @Test
    fun `cobbled descents cost more the steeper they get, smooth ones only when very steep`() {
        assertEquals(1.0, BikeWeighting.descentFactor(0.05, rough = true), 1e-9)
        assertEquals(1.6, BikeWeighting.descentFactor(0.09, rough = true), 1e-9)
        assertEquals(2.2, BikeWeighting.descentFactor(0.12, rough = true), 1e-9)
        assertEquals(14.2, BikeWeighting.descentFactor(0.18, rough = true), 1e-9)
        assertEquals(1.0, BikeWeighting.descentFactor(0.12, rough = false), 1e-9)
        assertEquals(1.9, BikeWeighting.descentFactor(0.18, rough = false), 1e-9)
        // Per metre, a short 18% cobbled street must cost more than a 9% one four times as long.
        assertTrue(BikeWeighting.descentFactor(0.18, rough = true) > 4 * BikeWeighting.descentFactor(0.09, rough = true))
    }
}
