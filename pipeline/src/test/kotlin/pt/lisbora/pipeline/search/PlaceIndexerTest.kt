package pt.lisbora.pipeline.search

import com.graphhopper.reader.ReaderNode
import com.graphhopper.reader.ReaderWay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.routing.search.SearchIndex

class PlaceIndexerTest {

    private val bounds = GeoBounds(-9.30, 38.655, -9.05, 38.83)

    private fun node(vararg tags: Pair<String, String>) = ReaderNode(1, 38.71, -9.14).apply { tags.forEach { (k, v) -> setTag(k, v) } }

    @Test
    fun `maps OSM tags to categories and leaves out what nobody searches for`() {
        assertEquals(PlaceCategory.METRO_STATION, PlaceTags.category(node("railway" to "station", "station" to "subway", "name" to "Baixa-Chiado")))
        assertEquals(PlaceCategory.TRAIN_STATION, PlaceTags.category(node("building" to "train_station", "name" to "Gare do Oriente")))
        assertEquals(PlaceCategory.VIEWPOINT, PlaceTags.category(node("tourism" to "viewpoint")))
        assertEquals(PlaceCategory.SHOP, PlaceTags.category(node("shop" to "gift")))
        assertEquals(PlaceCategory.OTHER, PlaceTags.category(node("landuse" to "retail", "wikidata" to "Q112628482")))
        assertNull(PlaceTags.category(node("amenity" to "parking", "name" to "Parque do Oceanário")))
        assertNull(PlaceTags.category(node("amenity" to "cafe", "disused" to "yes")))
        assertNull(PlaceTags.category(node("landuse" to "residential")))
        assertEquals(listOf("Gare do Oriente", "Gare Intermodal de Lisboa"), PlaceTags.altNames(node("name" to "Oriente", "alt_name" to "Gare do Oriente;Gare Intermodal de Lisboa")))
        assertTrue(PlaceTags.isSearchableStreet(ReaderWay(1).apply { setTag("highway", "residential"); setTag("name", "Rua A") }))
        assertTrue(!PlaceTags.isSearchableStreet(ReaderWay(2).apply { setTag("highway", "primary_link"); setTag("name", "Acesso") }))
    }

    @Test
    fun `joins boundary ways into closed rings whatever their direction`() {
        val rings = RingAssembler.assemble(listOf(longArrayOf(1, 2, 3), longArrayOf(5, 4, 3), longArrayOf(5, 6, 1), longArrayOf(10, 11)))
        assertEquals(1, rings.size)
        assertEquals(listOf(1L, 2, 3, 4, 5, 6, 1), rings.single().toList())
        assertTrue(RingAssembler.assemble(listOf(longArrayOf(1, 2, 3), longArrayOf(3, 4))).isEmpty())
    }

    @Test
    fun `merges pieces of a street but keeps namesakes in other neighbourhoods apart`() {
        fun way(name: String, ids: LongArray, vararg points: Pair<Double, Double>) =
            StreetWay(name, "residential", ids, points.map { (lat, lon) -> GeoPoint(lat, lon) })
        val clusters = StreetClusters.cluster(listOf(
            way("Rua da Liberdade", longArrayOf(1, 2), 38.7000 to -9.1500, 38.7000 to -9.1480),
            way("Rua da Liberdade", longArrayOf(2, 3), 38.7000 to -9.1480, 38.7000 to -9.1460),
            way("Rua da Liberdade", longArrayOf(7, 8), 38.6800 to -9.1600, 38.6800 to -9.1590),
            way("Rua Augusta", longArrayOf(4, 5), 38.7100 to -9.1370, 38.7110 to -9.1370),
        ))
        assertEquals(3, clusters.size)
        val main = clusters.filter { it.name == "Rua da Liberdade" }.maxBy { it.lengthM }
        assertEquals(347.0, main.lengthM, 5.0)
        assertEquals(38.7000, main.point.lat, 1e-6)
        assertEquals(-9.1480, main.point.lon, 1e-6)
    }

    private fun square(south: Double, west: Double, size: Double) = listOf(
        GeoPoint(south, west), GeoPoint(south, west + size), GeoPoint(south + size, west + size), GeoPoint(south + size, west), GeoPoint(south, west),
    )

    @Test
    fun `context names the parish and the municipality`() {
        val lookup = AdminLookup(listOf(
            AdminArea("Lisboa", 7, listOf(square(38.69, -9.23, 0.10))),
            AdminArea("União das Freguesias de Santa Maria Maior", 8, listOf(square(38.705, -9.145, 0.01))),
        ))
        assertEquals("Santa Maria Maior, Lisboa", lookup.context(GeoPoint(38.71, -9.14)))
        assertEquals("Lisboa", lookup.context(GeoPoint(38.75, -9.20)))
        assertEquals("Santa Maria Maior", lookup.context(GeoPoint(38.71, -9.14), exclude = "Lisboa"))
        assertNull(lookup.context(GeoPoint(38.60, -9.00)))
    }

    @Test
    fun `indexes streets, places, addresses and parishes with context`() {
        val places = OsmPlaces(
            streets = listOf(StreetWay("Rua Augusta", "pedestrian", longArrayOf(1, 2), listOf(GeoPoint(38.7100, -9.1370), GeoPoint(38.7110, -9.1372)))),
            pois = listOf(OsmPoi("Arco da Rua Augusta", PlaceCategory.MONUMENT, GeoPoint(38.7087, -9.1366), 60)),
            addresses = listOf(OsmAddress("Rua Augusta", "24", GeoPoint(38.7101, -9.1371))),
            places = listOf(OsmPlaceNode("Baixa", "quarter", GeoPoint(38.7110, -9.1380))),
            adminAreas = listOf(
                AdminArea("Lisboa", 7, listOf(square(38.69, -9.23, 0.10))),
                AdminArea("Santa Maria Maior", 8, listOf(square(38.705, -9.145, 0.01))),
            ),
            dataDate = null,
        )
        val (builder, stats) = PlaceIndexer(bounds).index(places)
        val index = SearchIndex(builder.build("test"))
        assertEquals(1, stats.streets)
        assertEquals("Santa Maria Maior, Lisboa", index.search("rua augusta").first().place.context)
        assertEquals("Rua Augusta, 24", index.search("rua augusta 24").first().place.name)
        assertEquals(PlaceCategory.NEIGHBOURHOOD, index.search("santa maria maior").single().place.category)
        // Lisboa has no place node here, so the municipality itself becomes a locality.
        assertEquals(PlaceCategory.LOCALITY, index.search("lisboa").single().place.category)
    }

    @Test
    fun `reference searches report the rank of the expected place`() {
        val config = GoldenSearchConfig.parse(
            """{ "near": [38.72, -9.14], "searches": [
                 { "query": "augusta", "expectName": "Rua Augusta", "at": [38.7105, -9.1371], "radiusM": 300, "maxRank": 1 },
                 { "query": "estrela", "expectName": "Jardim", "at": [38.71, -9.16] } ] }""",
        )
        val index = SearchIndex(pt.lisbora.routing.search.SearchIndexBuilder().apply {
            add("Rua Augusta", PlaceCategory.STREET, GeoPoint(38.7105, -9.1371))
        }.build("test"))
        val results = config.run(index)
        assertEquals(listOf(true, false), results.map { it.passed })
        assertEquals(1, results[0].rank)
        assertEquals(3, config.searches[1].maxRank)
    }
}
