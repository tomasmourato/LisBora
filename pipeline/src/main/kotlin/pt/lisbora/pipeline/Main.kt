package pt.lisbora.pipeline

import java.io.PrintStream
import kotlin.system.exitProcess

internal val USAGE = """
    Utilização: gradlew :pipeline:run --args="<comando>"

    Comandos:
      help         Mostra esta ajuda.

    Previstos:
      fetch-cml    Descarrega e normaliza a Rede Ciclável da CML (Fase 1).
      build        Constrói o pacote de dados: grafo, ciclovias, POIs, pesquisa (Fase 2).
""".trimIndent()

fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.out))
}

/** Dispatches a pipeline command and returns the process exit code. */
internal fun run(args: List<String>, out: PrintStream): Int =
    when (val command = args.firstOrNull()) {
        null, "help", "--help", "-h" -> {
            out.println(USAGE)
            0
        }
        else -> {
            out.println("Comando desconhecido: $command")
            out.println(USAGE)
            2
        }
    }
