package pt.lisbora.data.gira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.geo.GeoPoint

class GiraStationsTest {

    private val here = GeoPoint(38.7132, -9.1376)

    @Test
    fun `only the GIRA network's bike-share points are stations, with EMEL's number`() {
        val stations = GiraStations.from(
            listOf(
                Poi(PoiKind.BIKE_SHARE, "Bicicletas Gira Estação 101", here),
                Poi(PoiKind.BIKE_SHARE, "Turispot", here),
                Poi(PoiKind.BIKE_SHARE, null, here),
                Poi(PoiKind.BIKE_PARKING, "Gira parking", here),
            ),
        )
        assertEquals(listOf(GiraStation(101, "Bicicletas Gira Estação 101", here)), stations)
    }

    @Test
    fun `EMEL's list in the data pack gives number, place and docks, without live counts`() {
        val stations = GiraGeoJson.parse(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-9.0968,38.756199]},
               "properties":{"number":101,"name":"101 - Alameda dos Oceanos","place":"Alameda dos Oceanos","docks":16}}
            ]}""".trimIndent(),
        )
        assertEquals(listOf(GiraStation(101, "101 - Alameda dos Oceanos", GeoPoint(38.756199, -9.0968), docks = 16, place = "Alameda dos Oceanos")), stations)
        assertEquals(false, stations.single().isLive)
        assertEquals(true, stations.single().hasBike && stations.single().hasFreeDock)
    }

    @Test
    fun `the nearest station that has what is needed, within reach`() {
        val near = GiraStation(1, "Gira 1", GeoPoint(38.7140, -9.1376), bikes = 0)
        val far = GiraStation(2, "Gira 2", GeoPoint(38.7200, -9.1376), bikes = 4)
        assertEquals(near, GiraStations.nearest(listOf(far, near), here, 1500.0))
        assertEquals(far, GiraStations.nearest(listOf(far, near), here, 1500.0) { it.hasBike })
        assertNull(GiraStations.nearest(listOf(far, near), here, 500.0) { it.hasBike })
    }
}
