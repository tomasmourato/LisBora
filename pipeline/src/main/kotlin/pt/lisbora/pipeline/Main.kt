package pt.lisbora.pipeline

import pt.lisbora.pipeline.basemap.BasemapCommand
import pt.lisbora.pipeline.cml.CmlClient
import pt.lisbora.pipeline.cml.FetchCmlCommand
import pt.lisbora.pipeline.dem.TerrariumDem
import pt.lisbora.pipeline.graph.BuildGraphCommand
import pt.lisbora.pipeline.graph.BuildOptions
import pt.lisbora.pipeline.http.JdkHttpGet
import pt.lisbora.pipeline.osm.OsmExtract
import pt.lisbora.pipeline.qa.DiscoverCommand
import pt.lisbora.pipeline.qa.GoldenConfig
import pt.lisbora.pipeline.qa.GoldenDiscoveries
import pt.lisbora.pipeline.qa.GoldenRoutesCommand
import pt.lisbora.pipeline.search.BuildSearchCommand
import pt.lisbora.pipeline.search.GoldenSearchConfig
import pt.lisbora.pipeline.search.PoiCatalog
import pt.lisbora.pipeline.search.SearchOptions
import pt.lisbora.routing.discover.DiscoveryEngine
import pt.lisbora.routing.graph.GraphFormat
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.system.exitProcess

internal const val DEFAULT_PACK_DIR = "pipeline/work/datapack"
internal const val BUNDLED_PACK_DIR = "app/src/main/assets/datapack"
internal const val WORK_DIR = "pipeline/work"

internal val USAGE = """
    Utilização: gradlew :pipeline:run --args="<comando> [opções]"
    (os caminhos são relativos à raiz do repositório)

    Comandos:
      help                    Mostra esta ajuda.
      fetch-cml [--out DIR]   Descarrega e normaliza a Rede Ciclável da CML.
      basemap [--out DIR] [--date AAAAMMDD] [--maxzoom N] [--pmtiles CAMINHO]
                              Extrai o mapa base da região a partir do build diário Protomaps.
      build [--out DIR] [--osm FICHEIRO] [--refresh-osm sim] [--cml FICHEIRO] [--dem-zoom N]
                              Constrói o grafo de rotas (graph.lbg) a partir do OSM, da rede da CML
                              e da elevação, com relatório QA em $WORK_DIR/qa. Só escreve se o QA passar.
      golden-routes [--graph FICHEIRO] [--detalhe sim] [--amostra N]
                              Verifica as rotas de referência (${GoldenConfig.DEFAULT_FILE}).
                              O build guarda sempre o grafo candidato em $WORK_DIR/${BuildGraphCommand.CANDIDATE_FILE}.
      search [--out DIR] [--osm FICHEIRO] [--refresh-osm sim] [--mostrar TEXTO]
                              Constrói o índice de pesquisa offline (ruas, moradas, locais) e os pontos de
                              interesse do mapa e do Descobrir (${PoiCatalog.FILE_NAME}) a partir do OSM.
                              Só escreve se as pesquisas de referência (${GoldenSearchConfig.DEFAULT_FILE}) passarem.
                              --mostrar lista as etiquetas OSM dos elementos com esse texto no nome.
      discover [--graph FICHEIRO] [--pois FICHEIRO] [--amostra N] [--fios N] [--heuristica X] [--detalhe sim]
                              Corre as descobertas de referência (${GoldenDiscoveries.DEFAULT_FILE}) com tempos
                              de cálculo; --amostra N acrescenta N descobertas aleatórias (sempre as mesmas);
                              --detalhe sim mostra todas as candidatas.

    Opções:
      --out DIR    Pasta do pacote de dados (por omissão: $DEFAULT_PACK_DIR).
                   Para atualizar o pacote embutido na app: --out $BUNDLED_PACK_DIR
      --osm        Extrato OSM (por omissão: ${OsmExtract.DEFAULT_FILE}; descarregado se faltar).
      --cml        Rede da CML usada pelo mapa (por omissão: $BUNDLED_PACK_DIR/${FetchCmlCommand.GEOJSON_FILE}).
      --dem-zoom   Nível dos mosaicos de elevação Mapterhorn (por omissão: ${TerrariumDem.DEFAULT_ZOOM}).

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
        "build" -> BuildGraphCommand(JdkHttpGet(), out).run(
            BuildOptions(
                packDir = packDir,
                osmFile = Path(options["osm"] ?: OsmExtract.DEFAULT_FILE),
                refreshOsm = options["refresh-osm"] == "sim",
                cmlFile = Path(options["cml"] ?: "$BUNDLED_PACK_DIR/${FetchCmlCommand.GEOJSON_FILE}"),
                demZoom = options["dem-zoom"]?.toIntOrNull() ?: TerrariumDem.DEFAULT_ZOOM,
                workDir = Path(WORK_DIR),
                goldenFile = Path(GoldenConfig.DEFAULT_FILE),
            ),
        )
        "search" -> BuildSearchCommand(JdkHttpGet(), out).run(
            SearchOptions(
                packDir = packDir,
                osmFile = Path(options["osm"] ?: OsmExtract.DEFAULT_FILE),
                refreshOsm = options["refresh-osm"] == "sim",
                workDir = Path(WORK_DIR),
                goldenFile = Path(GoldenSearchConfig.DEFAULT_FILE),
                showName = options["mostrar"],
            ),
        )
        "golden-routes" -> GoldenRoutesCommand(out).run(
            graphFile = Path(options["graph"] ?: "$DEFAULT_PACK_DIR/${GraphFormat.FILE_NAME}"),
            configFile = Path(GoldenConfig.DEFAULT_FILE),
            detailed = options["detalhe"] == "sim",
            sample = options["amostra"]?.toIntOrNull() ?: 0,
        )
        "discover" -> DiscoverCommand(out).run(
            graphFile = Path(options["graph"] ?: "$DEFAULT_PACK_DIR/${GraphFormat.FILE_NAME}"),
            poisFile = Path(options["pois"] ?: "$DEFAULT_PACK_DIR/${PoiCatalog.FILE_NAME}"),
            configFile = Path(GoldenDiscoveries.DEFAULT_FILE),
            sample = options["amostra"]?.toIntOrNull() ?: 0,
            threads = options["fios"]?.toIntOrNull() ?: DiscoverCommand.DEFAULT_THREADS,
            heuristicWeight = options["heuristica"]?.toDoubleOrNull() ?: DiscoveryEngine.DEFAULT_HEURISTIC_WEIGHT,
            detailed = options["detalhe"] == "sim",
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
