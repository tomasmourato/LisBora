package pt.lisbora.routing.graph

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.lisbora.routing.TestGraphBuilder
import java.io.IOException

class GraphFormatTest {

    private fun sample(): GraphData = TestGraphBuilder().run {
        val a = node(0.0, 0.0, 10.0)
        val b = node(100.0, 0.0, 12.5)
        val c = node(100.0, 100.0, 20.0)
        val ab = edge(a, b, infra = EdgeInfra.SEGREGATED_TRACK, name = "Avenida da Liberdade")
        val bc = edge(b, c, backward = TravelMode.PUSH, name = "Calçada da Glória", pillars = listOf(Triple(110.0, 50.0, 16.0)))
        edge(c, a, road = RoadKind.PRIMARY, stress = 4)
        restrict(ab, b, bc)
        buildData()
    }

    @Test
    fun `write then read restores every array`() {
        val original = sample()
        val restored = GraphFormat.read(GraphFormat.toBytes(original))

        assertEquals(original.dataVersion, restored.dataVersion)
        assertEquals(original.bounds, restored.bounds)
        assertArrayEquals(original.nodeLat, restored.nodeLat)
        assertArrayEquals(original.nodeLon, restored.nodeLon)
        assertArrayEquals(original.nodeEle, restored.nodeEle)
        assertArrayEquals(original.edgeBase, restored.edgeBase)
        assertArrayEquals(original.edgeAdj, restored.edgeAdj)
        assertArrayEquals(original.edgeLength, restored.edgeLength)
        assertArrayEquals(original.edgeFlags, restored.edgeFlags)
        assertArrayEquals(original.edgeName, restored.edgeName)
        assertArrayEquals(original.geometryStart, restored.geometryStart)
        assertArrayEquals(original.pillarLat, restored.pillarLat)
        assertArrayEquals(original.pillarLon, restored.pillarLon)
        assertArrayEquals(original.pillarEle, restored.pillarEle)
        assertEquals(original.names, restored.names)
        assertArrayEquals(original.restrictionFrom, restored.restrictionFrom)
        assertArrayEquals(original.restrictionVia, restored.restrictionVia)
        assertArrayEquals(original.restrictionTo, restored.restrictionTo)
    }

    @Test
    fun `rejects foreign, truncated and newer files`() {
        val bytes = GraphFormat.toBytes(sample())
        assertThrows<IOException> { GraphFormat.read("not a graph".toByteArray()) }
        assertThrows<IOException> { GraphFormat.read(bytes.copyOf(bytes.size - 10)) }
        val newer = bytes.copyOf().also { it[4] = (GraphFormat.FORMAT_VERSION + 1).toByte() }
        assertThrows<IOException> { GraphFormat.read(newer) }
    }

    @Test
    fun `rejects inconsistent indexes`() {
        val data = sample()
        assertThrows<IllegalArgumentException> {
            GraphData(
                data.dataVersion, data.bounds, data.nodeLat, data.nodeLon, data.nodeEle,
                data.edgeBase.copyOf().also { it[0] = 99 }, data.edgeAdj, data.edgeLength, data.edgeFlags,
                data.edgeName, data.geometryStart, data.pillarLat, data.pillarLon, data.pillarEle, data.names,
                data.restrictionFrom, data.restrictionVia, data.restrictionTo,
            )
        }
    }
}
