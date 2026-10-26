package pt.lisbora.pipeline.datapack

import pt.lisbora.pipeline.http.JdkHttpGet
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * `pull`: downloads the published data pack into a folder, so a new build starts from it (the files it
 * does not rebuild, such as the base map, stay as published). Files already there are kept.
 */
class PullCommand(private val http: JdkHttpGet, private val out: PrintStream) {

    fun run(manifestUrl: String, packDir: Path): Int {
        val published = http.getBytes(manifestUrl)?.decodeToString()?.let(PackManifest::parse) ?: run {
            out.println("Ainda não há dados publicados em $manifestUrl; nada a descarregar.")
            return 0
        }
        PackManifest.prepare(packDir)
        var downloaded = 0L
        published.files.forEach { entry ->
            val target = packDir.resolve(entry.path)
            if (target.exists() && target.fileSize() == entry.size && PackManifest.sha256(target) == entry.sha256) return@forEach
            val partial = packDir.resolve("${entry.path}.partial")
            http.download(URI(manifestUrl).resolve(entry.url ?: entry.path).toString(), partial)
            if (partial.fileSize() != entry.size || PackManifest.sha256(partial) != entry.sha256) {
                partial.deleteIfExists()
                out.println("ERRO: ${entry.path} descarregado não corresponde ao manifesto publicado.")
                return 3
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            downloaded += entry.size
        }
        // The published version, unless the folder holds other files too (then it is a new pack).
        packDir.resolve(PackManifest.FILE_NAME).writeText(PackManifest.encode(published.copy(files = published.files.map { it.copy(url = null) })))
        PackManifest.write(packDir)
        out.println("Pacote publicado ${published.dataVersion} em $packDir (%.1f MB descarregados)".format(downloaded / 1_048_576.0))
        return 0
    }
}
