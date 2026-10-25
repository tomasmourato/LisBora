package pt.lisbora.pipeline.osm

import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.TurnCostsConfig
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Imports the OSM extract with GraphHopper (bike access, one-ways incl. `oneway:bicycle`, barriers,
 * turn restrictions, road class, surface…) into `pipeline/work/gh`, reusing a previous import when
 * neither the extract nor this configuration changed. GraphHopper is only used here, never on the phone.
 */
class GraphHopperImport(private val osmFile: Path, private val graphDir: Path, private val out: PrintStream) {

    fun importOrLoad(): GraphHopper {
        val stamp = "osm=${osmFile.fileName} size=${osmFile.fileSize()} modified=${osmFile.getLastModifiedTime()} config=$CONFIG_VERSION"
        val stampFile = graphDir.resolve(STAMP_FILE)
        if (graphDir.exists() && (!stampFile.exists() || stampFile.readText() != stamp)) {
            out.println("GraphHopper: o extrato ou a configuração mudaram; a reimportar.")
            graphDir.toFile().deleteRecursively()
        }
        val reuse = stampFile.exists()
        out.println(if (reuse) "GraphHopper: a carregar o grafo importado em $graphDir" else "GraphHopper: a importar $osmFile (demora alguns minutos)…")
        val hopper = GraphHopper()
            .setOSMFile(osmFile.toString())
            .setGraphHopperLocation(graphDir.toString())
            .setEncodedValuesString(ENCODED_VALUES)
            .setProfiles(profile())
            .setMinNetworkSize(0) // the export keeps the largest strongly connected component itself
            .setSortGraph(false)
            .setElevation(false) // elevation comes from the DEM at export time, for the region only
        hopper.readerConfig.isParseWayNames = true
        hopper.importOrLoad()
        if (!reuse) {
            Files.createDirectories(graphDir)
            stampFile.writeText(stamp)
        }
        out.println("GraphHopper: ${hopper.baseGraph.nodes} nós, ${hopper.baseGraph.edges} arestas (Portugal)")
        return hopper
    }

    companion object {
        const val DEFAULT_DIR = "pipeline/work/gh"
        const val PROFILE = "bike"
        private const val STAMP_FILE = "lisbora-import.txt"

        /** Bump when [ENCODED_VALUES] or [profile] change, to force a re-import. */
        private const val CONFIG_VERSION = 1

        const val ENCODED_VALUES = "bike_access, bike_average_speed, get_off_bike, road_class, road_environment, " +
            "roundabout, surface, max_speed, lanes, osm_way_id"

        /** A minimal profile: only needed so GraphHopper parses bike access and bike turn restrictions. */
        fun profile(): Profile = Profile(PROFILE)
            .setCustomModel(
                CustomModel()
                    .addToPriority(Statement.If("!bike_access", Statement.Op.MULTIPLY, "0"))
                    .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "bike_average_speed")),
            )
            .setTurnCostsConfig(TurnCostsConfig.bike())
    }
}
