package pt.lisbora.pipeline.cml

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pt.lisbora.pipeline.datapack.PackManifest
import pt.lisbora.pipeline.datapack.PrettyJson
import pt.lisbora.routing.model.CycleInfraClass
import java.io.PrintStream
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.math.roundToLong

/** Result of normalising a CML snapshot, before anything is written. */
data class CmlSnapshot(
    val features: List<JsonObject>,
    val lengthKmByClass: Map<CycleInfraClass, Double>,
    val rejected: Map<Rejection, List<String>>,
) {
    val unknownShare: Double
        get() = rejected[Rejection.UNKNOWN_CLASS].orEmpty().size.toDouble() /
            (features.size + rejected.values.sumOf { it.size }).coerceAtLeast(1)
}

/**
 * `fetch-cml`: downloads the CML cycle network, normalises it, checks it and writes
 * `ciclovias.geojson` + `ciclovias.meta.json` into the data pack, then refreshes the manifest.
 */
class FetchCmlCommand(
    private val client: CmlClient,
    private val out: PrintStream,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun run(packDir: Path): Int {
        out.println("A descarregar a Rede Ciclável da CML…")
        val lastEdit = client.fetchLastEditDate()
        val snapshot = normalize(client.fetchFeatures())

        printReport(snapshot, lastEdit)
        val problems = validate(snapshot)
        if (problems.isNotEmpty()) {
            problems.forEach { out.println("ERRO: $it") }
            out.println("Nada foi escrito; o pacote anterior mantém-se.")
            return 3
        }

        PackManifest.prepare(packDir)
        packDir.resolve(GEOJSON_FILE).writeText(PrettyJson.encodeCompact(featureCollection(snapshot)))
        packDir.resolve(META_FILE).writeText(PrettyJson.encode(meta(snapshot, lastEdit, clock.instant())) + "\n")
        PackManifest.write(packDir)
        out.println("Escrito em $packDir: $GEOJSON_FILE, $META_FILE, ${PackManifest.FILE_NAME}")
        return 0
    }

    private fun printReport(snapshot: CmlSnapshot, lastEdit: Instant?) {
        out.println("Última edição na CML: ${lastEdit ?: "desconhecida"}")
        out.println("Segmentos aceites: ${snapshot.features.size}")
        CycleInfraClass.entries.forEach { c ->
            out.println("  %-26s %7.1f km".format(c.name, snapshot.lengthKmByClass[c] ?: 0.0))
        }
        snapshot.rejected.forEach { (reason, details) ->
            out.println("Rejeitados ($reason): ${details.size}")
            details.distinct().take(5).forEach { out.println("  - $it") }
        }
    }

    companion object {
        const val GEOJSON_FILE = "ciclovias.geojson"
        const val META_FILE = "ciclovias.meta.json"
        const val ATTRIBUTION =
            "Rede Ciclável © Câmara Municipal de Lisboa (CC BY 4.0), dados normalizados pela LisBora"

        /** Sanity gates: a much smaller network or many unknown typologies means the source changed shape. */
        const val MIN_FEATURES = 500
        const val MAX_UNKNOWN_SHARE = 0.05

        fun normalize(rawFeatures: List<JsonObject>): CmlSnapshot {
            val accepted = mutableListOf<Pair<JsonObject, String>>()
            val lengthByClass = mutableMapOf<CycleInfraClass, Double>()
            val rejected = mutableMapOf<Rejection, MutableList<String>>()
            rawFeatures.forEach { raw ->
                when (val result = CmlNormalizer.normalize(raw)) {
                    is NormalizeResult.Accepted -> {
                        val id = result.feature["properties"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        accepted += result.feature to id
                        lengthByClass.merge(result.infraClass, result.lengthM / 1000, Double::plus)
                    }
                    is NormalizeResult.Rejected -> rejected.getOrPut(result.reason) { mutableListOf() } += result.detail
                }
            }
            return CmlSnapshot(
                features = accepted.sortedBy { it.second }.map { it.first },
                lengthKmByClass = lengthByClass,
                rejected = rejected,
            )
        }

        fun validate(snapshot: CmlSnapshot): List<String> = buildList {
            if (snapshot.features.size < MIN_FEATURES) {
                add("Só ${snapshot.features.size} segmentos (mínimo $MIN_FEATURES).")
            }
            if (snapshot.unknownShare > MAX_UNKNOWN_SHARE) {
                add("%.1f%% dos segmentos têm tipologia desconhecida (máximo %.0f%%)."
                    .format(snapshot.unknownShare * 100, MAX_UNKNOWN_SHARE * 100))
            }
        }

        fun featureCollection(snapshot: CmlSnapshot): JsonObject = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", JsonArray(snapshot.features))
        }

        fun meta(snapshot: CmlSnapshot, lastEdit: Instant?, fetchedAt: Instant): JsonObject = buildJsonObject {
            put("source", "Câmara Municipal de Lisboa — Rede Ciclável")
            put("sourceUrl", CmlClient.DATASET_URL)
            put("serviceUrl", CmlClient.SERVICE_URL)
            put("license", "CC BY 4.0")
            put("attribution", ATTRIBUTION)
            put("sourceLastEdit", lastEdit?.toString())
            put("fetchedAt", fetchedAt.toString())
            put("featureCount", snapshot.features.size)
            putJsonObject("lengthKmByClass") {
                CycleInfraClass.entries.forEach { put(it.id, round1(snapshot.lengthKmByClass[it] ?: 0.0)) }
            }
            put("lengthKmTotal", round1(snapshot.lengthKmByClass.values.sum()))
            putJsonObject("rejected") {
                Rejection.entries.forEach { put(it.name, snapshot.rejected[it]?.size ?: 0) }
            }
            putJsonArray("unknownTypologies") {
                snapshot.rejected[Rejection.UNKNOWN_CLASS].orEmpty().distinct().forEach { add(it) }
            }
        }

        private fun round1(value: Double) = (value * 10).roundToLong() / 10.0
    }
}
