package pt.lisbora.data.datapack

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.lisbora.routing.model.CycleInfraClass
import java.time.Instant

class CycleNetworkMetaTest {

    @Test
    fun `parses attribution, last edit and lengths, ignoring unknown classes`() {
        val meta = CycleNetworkMeta.parse(
            """
            {"attribution":"Rede Ciclável © CML","sourceLastEdit":"2026-10-23T09:14:09.040Z",
             "lengthKmByClass":{"segregated":118.9,"trail":58.8,"future_class":1.0}}
            """.trimIndent(),
        )
        assertEquals("Rede Ciclável © CML", meta.attribution)
        assertEquals(Instant.parse("2026-10-23T09:14:09.040Z"), meta.sourceLastEdit)
        assertEquals(
            mapOf(CycleInfraClass.SEGREGATED_TRACK to 118.9, CycleInfraClass.TRAIL to 58.8),
            meta.lengthKmByClass,
        )
    }
}
