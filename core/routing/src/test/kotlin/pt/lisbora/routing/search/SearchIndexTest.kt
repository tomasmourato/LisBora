package pt.lisbora.routing.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pt.lisbora.routing.geo.GeoPoint
import java.io.IOException

class SearchIndexTest {

    private val baixa = GeoPoint(38.7105, -9.1376)
    private val belem = GeoPoint(38.6970, -9.2060)
    private val almada = GeoPoint(38.6790, -9.1570)

    private fun index(build: SearchIndexBuilder.() -> Unit): SearchIndex =
        SearchIndex(SearchIndexBuilder().apply(build).build("test"))

    private fun SearchIndex.names(query: String, near: GeoPoint? = null) = search(query, near).map { it.place.name }

    @Test
    fun `normalises accents, case, punctuation and ordinals`() {
        assertEquals("praca do comercio", SearchText.normalize("Praça do  Comércio!"))
        assertEquals(listOf("rua", "1", "o", "de", "maio"), SearchText.tokens("Rua 1º de Maio"))
        assertEquals(listOf("calcada", "de", "santana"), SearchText.tokens("Calçada de Sant'Ana"))
        assertTrue(SearchText.indexTokens("Calçada de Sant'Ana").containsAll(listOf("santana", "sant", "ana")))
        assertEquals(listOf("linda", "a", "velha"), SearchText.tokens("Linda-a-Velha"))
    }

    @Test
    fun `matches word prefixes regardless of accents and abbreviations`() {
        val idx = index {
            add("Praça do Comércio", PlaceCategory.STREET, baixa)
            add("Rua Augusta", PlaceCategory.STREET, baixa)
            add("Largo do Carmo", PlaceCategory.STREET, baixa)
            add("Avenida da Liberdade", PlaceCategory.STREET, baixa)
        }
        assertEquals(listOf("Praça do Comércio"), idx.names("praca comer"))
        assertEquals(listOf("Rua Augusta"), idx.names("R. Augusta"))
        assertEquals(listOf("Largo do Carmo"), idx.names("lg carmo"))
        assertEquals(listOf("Avenida da Liberdade"), idx.names("av liberdade"))
        // Stopwords are optional, but every other word must match.
        assertEquals(listOf("Largo do Carmo"), idx.names("largo de carmo"))
        assertTrue(idx.names("rua carmo").isEmpty())
        assertTrue(idx.names("   ").isEmpty())
    }

    @Test
    fun `prefers close matches, then nearer places`() {
        val idx = index {
            add("Rua da Liberdade", PlaceCategory.STREET, almada)
            add("Rua da Liberdade", PlaceCategory.STREET, belem)
            add("Avenida da Liberdade", PlaceCategory.STREET, baixa, importance = 80)
            add("Travessa da Rua da Liberdade", PlaceCategory.STREET, baixa)
        }
        // "rua liberdade": the streets whose name starts with "Rua" beat the travessa.
        val hits = idx.search("rua liberdade", near = belem)
        assertEquals(listOf("Rua da Liberdade", "Rua da Liberdade", "Travessa da Rua da Liberdade"), hits.map { it.place.name })
        assertEquals(belem, hits.first().place.point)
        assertEquals(almada, idx.search("rua liberdade", near = almada).first().place.point)
        assertEquals("Avenida da Liberdade", idx.names("liberdade", near = baixa).first())
    }

    @Test
    fun `addresses only show up when a number is typed`() {
        val idx = index {
            add("Rua Augusta", PlaceCategory.STREET, baixa)
            add("Rua Augusta, 24", PlaceCategory.ADDRESS, baixa)
            add("Rua Augusta, 240", PlaceCategory.ADDRESS, baixa)
        }
        assertEquals(listOf("Rua Augusta"), idx.names("rua augusta"))
        assertEquals("Rua Augusta, 24", idx.names("rua augusta 24").first())
    }

    @Test
    fun `keeps one hit per place and limits the list`() {
        val idx = index {
            add("Jardim da Estrela", PlaceCategory.PARK, GeoPoint(38.7135, -9.1590))
            add("Jardim da Estrela", PlaceCategory.PARK, GeoPoint(38.7137, -9.1595))
            repeat(30) { add("Café $it", PlaceCategory.FOOD, baixa) }
        }
        assertEquals(1, idx.search("estrela").size)
        assertEquals(5, idx.search("cafe", limit = 5).size)
    }

    @Test
    fun `generic words favour the right kind of place`() {
        val idx = index {
            add("Farmácia Gare do Oriente", PlaceCategory.HEALTH, GeoPoint(38.7670, -9.0985))
            add("Oriente", PlaceCategory.TRAIN_STATION, GeoPoint(38.7678, -9.0990), importance = 60)
            add("Oriente", PlaceCategory.METRO_STATION, GeoPoint(38.7679, -9.0992), importance = 55)
            add("Baixa-Chiado", PlaceCategory.METRO_STATION, baixa)
            add("Rua do Metro", PlaceCategory.STREET, almada)
        }
        val hits = idx.search("gare do oriente")
        // Train and metro stations of the same name are both kept; the pharmacy comes after them.
        assertEquals(listOf(PlaceCategory.TRAIN_STATION, PlaceCategory.METRO_STATION, PlaceCategory.HEALTH), hits.map { it.place.category })
        assertEquals(PlaceCategory.METRO_STATION, idx.search("metro oriente").first().place.category)
        // Typed alone, a generic word lists places of its kind even when their name lacks it.
        assertTrue("Baixa-Chiado" in idx.names("metro"))
    }

    @Test
    fun `alternative names match but the main name is shown`() {
        val idx = index {
            add("LX Factory", PlaceCategory.OTHER, GeoPoint(38.7034, -9.1786), altNames = listOf("LxFactory"))
            add("Ponte 25 de Abril", PlaceCategory.OTHER, GeoPoint(38.6900, -9.1770), altNames = listOf("Ponte Salazar"))
        }
        assertEquals(listOf("LX Factory"), idx.names("lxfactory"))
        assertEquals(listOf("Ponte 25 de Abril"), idx.names("ponte salazar"))
    }

    @Test
    fun `finds the nearest place of the wanted kind`() {
        val idx = index {
            add("Rua Augusta, 24", PlaceCategory.ADDRESS, GeoPoint(38.7101, -9.1370))
            add("Rua Augusta", PlaceCategory.STREET, GeoPoint(38.7110, -9.1375))
            add("Torre de Belém", PlaceCategory.MONUMENT, belem)
        }
        val hit = idx.nearest(GeoPoint(38.7102, -9.1371), maxDistanceM = 50.0) { it == PlaceCategory.ADDRESS }
        assertEquals("Rua Augusta, 24", hit?.place?.name)
        assertNull(idx.nearest(GeoPoint(38.7300, -9.1500), maxDistanceM = 100.0))
        assertEquals("Torre de Belém", idx.nearest(GeoPoint(38.6975, -9.2058), maxDistanceM = 200.0)?.place?.name)
    }

    @Test
    fun `round-trips through the binary format`() {
        val data = SearchIndexBuilder().apply {
            add("Museu Calouste Gulbenkian", PlaceCategory.MUSEUM, GeoPoint(38.7375, -9.1545), context = "Avenidas Novas, Lisboa", importance = 70)
            add("Oceanário de Lisboa", PlaceCategory.ATTRACTION, GeoPoint(38.7635, -9.0937), context = "Parque das Nações, Lisboa")
        }.build("20261025")
        val copy = SearchIndexFormat.read(SearchIndexFormat.toBytes(data))
        assertEquals(data.dataVersion, copy.dataVersion)
        assertEquals(data.names, copy.names)
        assertEquals(data.tokens, copy.tokens)
        assertEquals(data.contexts, copy.contexts)
        val hit = SearchIndex(copy).search("gulbenk").single()
        assertEquals(PlaceCategory.MUSEUM, hit.place.category)
        assertEquals("Avenidas Novas, Lisboa", hit.place.context)
        assertEquals(38.7375, hit.place.point.lat, 1e-7)

        val bytes = SearchIndexFormat.toBytes(data)
        assertThrows(IOException::class.java) { SearchIndexFormat.read(bytes.copyOf(bytes.size - 10)) }
        assertThrows(IOException::class.java) { SearchIndexFormat.read(ByteArray(16)) }
    }
}
