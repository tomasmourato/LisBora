package pt.lisbora.pipeline.qa

import pt.lisbora.pipeline.search.PoiCatalog
import pt.lisbora.routing.RoutingEngine
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryEngine
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryRequest
import pt.lisbora.routing.discover.DiscoveryResult
import pt.lisbora.routing.discover.PoiKind
import pt.lisbora.routing.discover.TaskRunner
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphFormat
import pt.lisbora.routing.graph.RoutingGraph
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.random.Random

/**
 * `discover`: runs the reference discoveries (`golden-discoveries.json`) on a graph and its points of
 * interest, with timings, and optionally a random sample to measure changes to Descobrir.
 */
class DiscoverCommand(private val out: PrintStream) {

    fun run(
        graphFile: Path,
        poisFile: Path,
        configFile: Path,
        sample: Int = 0,
        threads: Int = DEFAULT_THREADS,
        heuristicWeight: Double = DiscoveryEngine.DEFAULT_HEURISTIC_WEIGHT,
        detailed: Boolean = false,
    ): Int {
        for (file in listOf(graphFile, poisFile)) {
            if (!file.exists()) {
                out.println("ERRO: falta $file; corre primeiro build e search.")
                return 2
            }
        }
        val graph = RoutingGraph(graphFile.inputStream().buffered().use(GraphFormat::read))
        val pois = PoiCatalog.parse(poisFile.readText())
        val started = System.nanoTime()
        val engine = DiscoveryEngine(RoutingEngine(graph), pois, heuristicWeight)
        out.println("Grafo ${graph.data.dataVersion}, ${pois.size} pontos de interesse; motor pronto em ${elapsedMs(started)} ms " +
            "($threads fios, heurística ×$heuristicWeight)")
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val runner = ExecutorRunner(executor)
            val golden = GoldenDiscoveries.parse(configFile.readText())
            // Warm up the JIT so the first timing is not misleading.
            golden.firstOrNull()?.let { engine.discover(it.request, runner) }
            var failed = 0
            golden.forEach { g ->
                val t0 = System.nanoTime()
                val lines = mutableListOf<String>()
                val result = engine.discover(g.request, runner, if (detailed) { line -> lines += line } else null)
                val ms = elapsedMs(t0)
                val routes = (result as? DiscoveryResult.Success)?.routes.orEmpty()
                val failures = if (result is DiscoveryResult.Failure) listOf("Falhou: ${result.reason}") else GoldenDiscoveries.failures(g, routes)
                if (failures.isNotEmpty()) failed++
                out.println("${if (failures.isEmpty()) "OK   " else "FALHA"} ${g.name} ($ms ms) ${failures.joinToString(" ")}")
                routes.forEachIndexed { i, r -> out.println("      ${i + 1}. ${summary(r, g.request.durationS)}") }
                lines.forEach(out::println)
            }
            if (sample > 0) printSample(engine, runner, sample)
            return if (failed == 0) 0 else 3
        } finally {
            executor.shutdown()
        }
    }

    private fun summary(r: DiscoveredRoute, targetS: Double): String {
        val route = r.route
        val h = r.highlights
        val km = route.distanceM / 1000
        val places = h.pois.filter { it.name != null }.take(4).joinToString(", ") { "${it.name} (${it.kind.code})" }
        return "%.0f min (%+.0f%%), %.1f km, %.0f%% infra, %.0f%% calmas, rio %.1f km, verde %.1f km, +%.0f m (%.0f m/km), máx %.1f%%, repete %.0f%%, %d miradouros, pontuação %.2f%s".format(
            route.durationS / 60, (route.durationS / targetS - 1) * 100, km, h.infraShare * 100, h.calmShare * 100,
            h.riversideM / 1000, h.greenM / 1000, route.ascentM, route.ascentM / km, route.maxGradePercent,
            GoldenDiscoveries.repeatedShare(r) * 100, h.count(PoiKind.VIEWPOINT), r.score,
            if (places.isEmpty()) "" else "; $places",
        )
    }

    /**
     * [count] random discoveries in central Lisbon (fixed seed, so runs are comparable): every purpose,
     * 30 to 90 minutes, one in four A→B. Reports time taken and how well the routes fit the time.
     */
    private fun printSample(engine: DiscoveryEngine, runner: TaskRunner, count: Int) {
        val random = Random(SAMPLE_SEED)
        val times = mutableListOf<Long>()
        val errors = mutableListOf<Double>()
        val repeated = mutableListOf<Double>()
        var routes = 0
        var failures = 0
        repeat(count) { i ->
            val start = GeoPoint(random.nextDouble(38.705, 38.76), random.nextDouble(-9.20, -9.11))
            val end = if (i % 4 == 3) GeoPoint(random.nextDouble(38.705, 38.76), random.nextDouble(-9.20, -9.11)) else null
            val minutes = MINUTES[(i / DiscoveryPurpose.entries.size) % MINUTES.size]
            val request = DiscoveryRequest(start = start, end = end, durationS = minutes * 60.0, purpose = DiscoveryPurpose.entries[i % DiscoveryPurpose.entries.size], seed = i.toLong())
            val t0 = System.nanoTime()
            val result = engine.discover(request, runner)
            times += elapsedMs(t0)
            val found = (result as? DiscoveryResult.Success)?.routes.orEmpty()
            if (found.isEmpty()) {
                failures++
                return@repeat
            }
            routes += found.size
            if (!(result as DiscoveryResult.Success).directOnly) errors += found.first().route.durationS / request.durationS - 1
            repeated += GoldenDiscoveries.repeatedShare(found.first())
        }
        times.sort()
        fun within(limit: Double) = errors.count { abs(it) <= limit } * 100.0 / errors.size.coerceAtLeast(1)
        out.println(
            "Amostra de %d descobertas: %d sem rota, %.1f rotas em média; tempo de cálculo mediana %d ms, p90 %d ms, máx %d ms; ".format(
                count, failures, routes.toDouble() / (count - failures).coerceAtLeast(1), times[times.size / 2], times[times.size * 9 / 10], times.last(),
            ) + "duração da melhor rota a ±12%%: %.0f%%, a ±20%%: %.0f%%, erro médio %.0f%%; repete em média %.1f%% do percurso".format(
                within(0.12), within(0.20), errors.map { abs(it) }.average() * 100, repeated.average() * 100,
            ),
        )
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** Runs a discovery's candidate routes on a thread pool, like the app does. */
    private class ExecutorRunner(private val executor: ExecutorService) : TaskRunner {
        override fun <T> runAll(tasks: List<() -> T>): List<T> = executor.invokeAll(tasks.map { Callable(it) }).map { it.get() }
    }

    companion object {
        const val DEFAULT_THREADS = 4
        private const val SAMPLE_SEED = 20261026
        private val MINUTES = listOf(30, 45, 60, 90)
    }
}
