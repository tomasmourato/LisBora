package pt.lisbora.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FormattingTest {

    @Test
    fun `lengths use metres below 1 km and a decimal comma above`() {
        assertEquals("157 m", formatLength(156.68))
        assertEquals("1,9 km", formatLength(1_904.0))
    }

    @Test
    fun `kilometres use a decimal comma`() {
        assertEquals("118,9 km", formatKm(118.94))
    }

    @Test
    fun `dates are shown in Lisbon time`() {
        assertEquals("23/10/2026", formatDate(Instant.parse("2026-10-23T09:14:09Z")))
        // Lisbon is UTC+1 in summer, so 23:30 UTC on 31 July is already 1 August there.
        assertEquals("01/08/2026", formatDate(Instant.parse("2026-07-31T23:30:00Z")))
    }
}
