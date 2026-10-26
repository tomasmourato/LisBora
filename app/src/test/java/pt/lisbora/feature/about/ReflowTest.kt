package pt.lisbora.feature.about

import org.junit.Assert.assertEquals
import org.junit.Test

class ReflowTest {

    @Test
    fun `hard-wrapped paragraphs are joined, lists keep their lines and rules shrink`() {
        val text = """
            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions are
            met:

            * Redistributions of source code must retain the above copyright
              notice, this list of conditions and the following disclaimer.
            * Redistributions in binary form must reproduce the above copyright
              notice.

            ========================================================================

            1. Definitions.
            2. Grant of Copyright License.
        """.trimIndent()
        assertEquals(
            """
                Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

                * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
                * Redistributions in binary form must reproduce the above copyright notice.

                · · ·

                1. Definitions.
                2. Grant of Copyright License.
            """.trimIndent(),
            reflow(text),
        )
    }
}
