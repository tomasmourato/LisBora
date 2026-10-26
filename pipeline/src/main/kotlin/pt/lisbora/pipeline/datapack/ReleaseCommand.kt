package pt.lisbora.pipeline.datapack

import pt.lisbora.pipeline.http.JdkHttpGet
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import kotlin.io.path.writeText

/** What publishing a data pack takes, against the pack already published (if any). */
data class ReleasePlan(
    /** The manifest to publish: every file with the name of its download. */
    val manifest: Manifest,
    /** Files the published pack does not have yet. */
    val uploads: List<Manifest.Entry>,
    /** Downloads to keep: this pack's and the published one's, for phones still fetching it. */
    val keep: Set<String>,
    /** Paths whose content is new or changed, and paths no longer in the pack. */
    val changed: List<String>,
    val removed: List<String>,
) {
    companion object {
        /**
         * Null when no data file changed: new fetch times in the `*.meta.json` files alone do not make a
         * new pack. The version is [now], kept above the published one so phones see it as newer.
         */
        fun of(local: Manifest, published: Manifest?, now: Instant): ReleasePlan? {
            if (published != null && local.dataFiles == published.dataFiles) return null
            val version = PackManifest.version(now).let { v -> if (published != null && v <= published.dataVersion) "${published.dataVersion}+" else v }
            val files = local.files.map { it.copy(url = assetName(it)) }
            val publishedAssets = published?.files?.mapNotNull { it.url }.orEmpty().toSet()
            val before = published?.dataFiles.orEmpty()
            return ReleasePlan(
                manifest = local.copy(dataVersion = version, files = files),
                uploads = files.filter { it.url !in publishedAssets },
                keep = (files.mapNotNull { it.url } + publishedAssets + PackManifest.FILE_NAME).toSet(),
                changed = local.dataFiles.filter { (path, sha) -> before[path] != sha }.keys.sorted(),
                removed = (before.keys - local.dataFiles.keys).sorted(),
            )
        }

        /** "graph.lbg" → "graph.625e72943f43be77.lbg": named by content, so a published file never changes. */
        fun assetName(entry: Manifest.Entry): String {
            val dot = entry.path.lastIndexOf('.')
            val hash = entry.sha256.take(16)
            return if (dot <= 0) "${entry.path}.$hash" else "${entry.path.substring(0, dot)}.$hash${entry.path.substring(dot)}"
        }
    }
}

/**
 * `release`: prepares the data pack for publishing in `pipeline/work/release`: `assets/` with the files
 * to upload, `manifest.json` (uploaded last, it switches phones to the new pack), `keep.txt` (the
 * downloads to keep; the rest can be deleted) and `notes.md`. Nothing is prepared (the folder is left
 * empty) when no data changed.
 */
class ReleaseCommand(
    private val http: JdkHttpGet,
    private val out: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun run(packDir: Path, releaseDir: Path, publishedUrl: String): Int {
        if (PackManifest.read(packDir) == null) {
            out.println("ERRO: $packDir não tem ${PackManifest.FILE_NAME}.")
            return 2
        }
        PackManifest.write(packDir, clock)
        val local = PackManifest.read(packDir)!!
        val published = http.getBytes(publishedUrl)?.decodeToString()?.let(PackManifest::parse)
        releaseDir.toFile().deleteRecursively()
        val plan = ReleasePlan.of(local, published, clock.instant())
        if (plan == null) {
            out.println("Sem mudanças nos dados desde o pacote publicado ${published?.dataVersion}; nada a publicar.")
            return 0
        }

        val assets = Files.createDirectories(releaseDir.resolve("assets"))
        plan.uploads.forEach { entry -> Files.copy(packDir.resolve(entry.path), assets.resolve(entry.url!!), StandardCopyOption.REPLACE_EXISTING) }
        releaseDir.resolve(PackManifest.FILE_NAME).writeText(PackManifest.encode(plan.manifest))
        releaseDir.resolve("keep.txt").writeText(plan.keep.sorted().joinToString("\n", postfix = "\n"))
        releaseDir.resolve("notes.md").writeText(notes(plan, published))

        val download = plan.uploads.sumOf { it.size }
        out.println("Pacote ${plan.manifest.dataVersion} (antes: ${published?.dataVersion ?: "nenhum"})")
        plan.changed.forEach { path -> out.println("  mudou: $path (%.1f MB)".format(local.entry(path)!!.size / 1_048_576.0)) }
        plan.removed.forEach { out.println("  saiu:  $it") }
        out.println("Ficheiros novos a publicar: %.1f MB (os telemóveis só descarregam os que não têm). Em $releaseDir".format(download / 1_048_576.0))
        return 0
    }

    private fun notes(plan: ReleasePlan, published: Manifest?) = buildString {
        appendLine("Pacote de dados da LisBora, lido pela app para se atualizar sem uma nova versão.")
        appendLine()
        appendLine("- Versão: `${plan.manifest.dataVersion}`" + (published?.let { " (antes: `${it.dataVersion}`)" } ?: ""))
        appendLine("- Mudou: " + plan.changed.joinToString { "`$it`" })
        if (plan.removed.isNotEmpty()) appendLine("- Saiu: " + plan.removed.joinToString { "`$it`" })
        appendLine("- Ficheiros novos: %.1f MB".format(plan.uploads.sumOf { it.size } / 1_048_576.0))
        appendLine()
        appendLine("Dados © OpenStreetMap contributors (ODbL); Rede Ciclável © Câmara Municipal de Lisboa (CC BY 4.0), normalizada pela LisBora; estações GIRA: EMEL.")
    }

    companion object {
        const val DEFAULT_DIR = "pipeline/work/release"
    }
}
