package pt.lisbora.data.gira

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.lisbora.routing.geo.GeoPoint
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class LiveGiraTest {

    /** Two stations as Firestore returns them (one closed), plus the trailing read-time entry it sends. */
    private val response = """
        [
          {"document": {"name": "projects/p/databases/(default)/documents/docking-stations/4545", "fields": {
            "DockingStationId": {"integerValue": "4545"}, "Name": {"stringValue": "253 - Avenida da Universidade Técnica / FMV"},
            "AvailableBikes": {"integerValue": "0"}, "FreeDocks": {"integerValue": "0"}, "DockLimit": {"integerValue": "32"},
            "Location": {"geoPointValue": {"latitude": 38.7140007019043, "longitude": -9.19324016571045}},
            "IsActive": {"booleanValue": true}, "ServiceStatus": {"stringValue": "UNAVAILABLE_BY_OPERATOR"},
            "Tenant": {"stringValue": "P1/EML/EML/"}}}, "readTime": "2026-10-26T15:00:00Z"},
          {"document": {"name": "projects/p/databases/(default)/documents/docking-stations/4001", "fields": {
            "Name": {"stringValue": "101 - Alameda dos Oceanos / Rua dos Argonautas"},
            "AvailableBikes": {"integerValue": "7"}, "FreeDocks": {"integerValue": "13"}, "DockLimit": {"integerValue": "20"},
            "Location": {"geoPointValue": {"latitude": 38.7552, "longitude": -9.0947}},
            "IsActive": {"booleanValue": true}, "ServiceStatus": {"stringValue": "AVAILABLE"}}}, "readTime": "2026-10-26T15:00:00Z"},
          {"readTime": "2026-10-26T15:00:00Z"}
        ]
    """.trimIndent()

    private val oceanos = GiraStation(
        number = 101, name = "101 - Alameda dos Oceanos / Rua dos Argonautas", point = GeoPoint(38.7552, -9.0947),
        bikes = 7, freeDocks = 13, docks = 20, place = "Alameda dos Oceanos / Rua dos Argonautas", inService = true,
    )

    @Test
    fun `stations are read with their number, place, counts and whether they are open`() {
        val (closed, open) = GiraFirestore.parse(response)
        assertEquals(oceanos, open)
        assertEquals(253, closed.number)
        assertEquals(32, closed.docks)
        assertFalse(closed.inService)
        assertFalse(closed.hasBike || closed.hasFreeDock)
        assertTrue(open.isLive && open.hasBike && open.hasFreeDock)
    }

    @Test
    fun `live stations are kept for a minute, and the bundled ones stand in when offline`() = runTest {
        val bundled = GiraStation(101, "Bicicletas Gira Estação 101", GeoPoint(38.7552, -9.0947))
        var calls = 0
        var online = true
        val feed = GiraFeed {
            calls++
            if (online) response else throw IOException("no network")
        }
        val clock = MutableClock()
        val repository = LiveGiraRepository(FakeOffline(listOf(bundled)), feed, clock)

        assertEquals(oceanos, repository.stations()[1])
        clock.millis += 30_000
        repository.stations()
        assertEquals(1, calls)

        // Past a minute it asks again; offline, the last live data is better than none.
        online = false
        clock.millis += LiveGiraRepository.MAX_AGE_MS
        assertEquals(oceanos, repository.stations()[1])
        assertEquals(2, calls)

        val fresh = LiveGiraRepository(FakeOffline(listOf(bundled)), feed, clock)
        val offline = fresh.stations().single()
        assertEquals(bundled, offline)
        assertNull(offline.bikes)
    }

    private class FakeOffline(private val stations: List<GiraStation>) : GiraRepository {
        override suspend fun stations() = stations
    }

    private class MutableClock(var millis: Long = 1_000_000L) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }
}
