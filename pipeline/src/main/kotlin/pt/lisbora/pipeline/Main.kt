package pt.lisbora.pipeline

import pt.lisbora.pipeline.basemap.BasemapCommand
import pt.lisbora.pipeline.cml.CmlClient
import pt.lisbora.pipeline.cml.FetchCmlCommand
import pt.lisbora.pipeline.http.JdkHttpGet
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

internal const val DEFAULT_PACK_DIR = "pipeline/work/datapack"
internal const val BUNDLED_PACK_DIR = "app/src/main/assets/datapack"

internal val USAGE = """
    Utilização: gradlew :pipeline:run --args="<comando> [opções]"
    (os caminhos são relativos à raiz do repositório)

    Comandos:
      help                    Mostra esta ajuda.
      fetch-cml [--out DIR]   Descarrega e normaliza a Rede Ciclável da CML.
      basemap [--out DIR] [--date AAAAMMDD] [--maxzoom N] [--pmtiles CAMINHO]
                              Extrai o mapa base da região a partir do build diário Protomaps.

    Opções:
      --out DIR    Pasta do pacote de dados (por omissão: $DEFAULT_PACK_DIR).
                   Para atualizar o pacote embutido na app: --out $BUNDLED_PACK_DIR

    Previstos:
      build        Constrói o grafo, os POIs e o índice de pesquisa (Fase 2).
""".trimIndent()

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out))
}

/** Dispatches a pipeline command and returns the process exit code. */
internal fun run(args: List<String>, out: PrintStream): Int {
    val command = args.firstOrNull()
    val options = parseOptions(args.drop(1)) ?: run {
        out.println("Opções inválidas: ${args.drop(1).joinToString(" ")}")
        out.println(USAGE)
        return 2
    }
    val packDir: Path = Path(options["out"] ?: DEFAULT_PACK_DIR)
    return when (command) {
        null, "help", "--help", "-h" -> {
            out.println(USAGE)
            0
        }
        "fetch-cml" -> FetchCmlCommand(CmlClient(JdkHttpGet()), out).run(packDir)
        "basemap" -> BasemapCommand(options["pmtiles"] ?: defaultPmtiles(), out).run(
            packDir = packDir,
            buildDate = options["date"],
            maxZoom = options["maxzoom"]?.toIntOrNull() ?: BasemapCommand.DEFAULT_MAX_ZOOM,
        )
        else -> {
            out.println("Comando desconhecido: $command")
            out.println(USAGE)
            2
        }
    }
}

/** Parses `--key value` pairs; returns null on a dangling or malformed option. */
internal fun parseOptions(args: List<String>): Map<String, String>? {
    if (args.size % 2 != 0) return null
    return args.chunked(2).associate { (key, value) ->
        if (!key.startsWith("--") || value.startsWith("--")) return null
        key.removePrefix("--") to value
    }
}

/** The pmtiles CLI kept under pipeline/work/tools (gitignored), else whatever is on PATH. */
private fun defaultPmtiles(): String {
    val local = listOf("pipeline/work/tools/pmtiles.exe", "pipeline/work/tools/pmtiles").map(::Path)
    return local.firstOrNull { it.exists() }?.toString() ?: "pmtiles"
}
