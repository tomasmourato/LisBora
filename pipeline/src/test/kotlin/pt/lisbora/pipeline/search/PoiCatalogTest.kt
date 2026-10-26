package pt.lisbora.pipeline.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pt.lisbora.routing.discover.Poi
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.PlaceCategory

class PoiCatalogTest {

    private val miradouro = GeoPoint(38.7115, -9.1310)

    private fun places(pois: List<OsmPoi>, amenities: List<Poi> = emptyList()) =
        OsmPlaces(emptyList(), pois, emptyList(), emptyList(), emptyList(), dataDate = null, amenities = amenities)

    @Test
    fun `named places keep the kinds of the map layer, each place once`() {
        val node = OsmPoi("Miradouro de Santa Luzia", PlaceCategory.VIEWPOINT, miradouro, importance = 70)
        // The same viewpoint mapped as an area too, 60 m away, with less importance.
        val area = OsmPoi("Miradouro de Santa Luzia", PlaceCategory.VIEWPOINT, offset(miradouro, 60.0), importance = 40)
        val cafe = OsmPoi("Café", PlaceCategory.FOOD, offset(miradouro, 100.0), importance = 10)
        val museum = OsmPoi("Museu de Artes Decorativas", PlaceCategory.MUSEUM, offset(miradouro, 120.0), importance = 50)

        val pois = PoiCatalog.collect(places(listOf(area, cafe, node, museum)))
        assertEquals(
            listOf(Poi(PoiKind.VIEWPOINT, node.name, node.point, 70), Poi(PoiKind.MUSEUM, museum.name, museum.point, 50)),
            pois,
        )
    }

    @Test
    fun `street furniture a few metres apart is one, a named place wins over an unnamed one`() {
        val water = listOf(
            Poi(PoiKind.DRINKING_WATER, null, miradouro),
            Poi(PoiKind.DRINKING_WATER, null, offset(miradouro, 3.0)),
            Poi(PoiKind.DRINKING_WATER, null, offset(miradouro, 50.0)),
        )
        val gira = OsmPoi("GIRA 101", PlaceCategory.BIKE_SHARE, miradouro, importance = 20)
        val unnamedGira = Poi(PoiKind.BIKE_SHARE, null, offset(miradouro, 2.0))

        val pois = PoiCatalog.collect(places(listOf(gira), water + unnamedGira))
        assertEquals(2, pois.count { it.kind == PoiKind.DRINKING_WATER })
        assertEquals(listOf("GIRA 101"), pois.filter { it.kind == PoiKind.BIKE_SHARE }.map { it.name })
    }

    @Test
    fun `the GeoJSON reads back the same`() {
        val pois = listOf(
            Poi(PoiKind.PARK, "Jardim da Estrela", GeoPoint(38.713912, -9.160123), importance = 55),
            Poi(PoiKind.BIKE_PARKING, null, GeoPoint(38.7, -9.15)),
        )
        val json = PoiCatalog.geoJson(pois).toString()
        assertEquals(pois, PoiCatalog.parse(json))
        // Kinds a newer pipeline adds are skipped by older readers.
        assertEquals(pois, PoiCatalog.parse(json.replaceFirst("[", """[{"type":"Feature","properties":{"kind":"ufo"},"geometry":{"type":"Point","coordinates":[0,0]}},""")))
    }

    /** [point] moved [metres] east. */
    private fun offset(point: GeoPoint, metres: Double) =
        GeoPoint(point.lat, point.lon + metres / (GeoPoint.EARTH_RADIUS_M * Math.PI / 180 * Math.cos(Math.toRadians(point.lat))))
}
