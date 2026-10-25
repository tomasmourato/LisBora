package pt.lisbora.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class MainTest {

    private fun runCapturing(vararg args: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { run(args.toList(), it) }
        return code to buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `no arguments prints usage and succeeds`() {
        val (code, output) = runCapturing()
        assertEquals(0, code)
        assertTrue(output.contains("Comandos:"))
    }

    @Test
    fun `unknown command fails with exit code 2`() {
        val (code, output) = runCapturing("nao-existe")
        assertEquals(2, code)
        assertTrue(output.contains("Comando desconhecido: nao-existe"))
    }
}
