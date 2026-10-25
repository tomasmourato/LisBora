package pt.lisbora.pipeline.datapack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PackManifestTest {

    @Test
    fun `lists files with size and sha256, sorted and excluding itself`(@TempDir dir: Path) {
        dir.resolve("b.txt").writeText("abc")
        dir.resolve("a.txt").writeText("")
        dir.resolve(".hidden").writeText("x")
        PackManifest.write(dir)
        PackManifest.write(dir) // a second run must not list the manifest itself

        val manifest = Json.parseToJsonElement(dir.resolve(PackManifest.FILE_NAME).readText()).jsonObject
        assertEquals(PackManifest.FORMAT_VERSION, manifest["formatVersion"]!!.jsonPrimitive.int)
        val files = manifest["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a.txt", "b.txt"), files.map { it["path"]!!.jsonPrimitive.content })
        assertEquals(3L, files[1]["size"]!!.jsonPrimitive.long)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            files[1]["sha256"]!!.jsonPrimitive.content,
        )
    }
}
