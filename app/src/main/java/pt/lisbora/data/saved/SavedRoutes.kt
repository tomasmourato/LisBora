package pt.lisbora.data.saved

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pt.lisbora.routing.discover.DiscoveredRoute
import pt.lisbora.routing.discover.DiscoveryPurpose
import pt.lisbora.routing.discover.DiscoveryTweak
import pt.lisbora.routing.geo.GeoPoint
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Descobrir route the rider kept. Only what it was built from is stored (stops, purpose, adjustments),
 * so it is computed again with the data of the day; the numbers are for the list.
 */
@Entity(tableName = "saved_routes")
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [DiscoveryPurpose] name. */
    val purpose: String,
    /** [DiscoveryTweak] names, comma-separated. */
    val tweaks: String,
    /** "lat,lon;lat,lon;…", start and end included. */
    val stops: String,
    val turnsBack: Boolean,
    val distanceM: Double,
    val durationS: Double,
    val ascentM: Double,
    /** Best-known place on the route, for its title. */
    val highlight: String?,
    val createdAt: Long,
    /** A GIRA ride: from station to station, never leaving Lisbon. */
    val gira: Boolean = false,
)

@Dao
interface SavedRouteDao {
    @Query("SELECT * FROM saved_routes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SavedRouteEntity>>

    @Insert
    suspend fun insert(route: SavedRouteEntity): Long

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun delete(id: Long)
}

data class SavedRoute(
    val id: Long,
    val purpose: DiscoveryPurpose,
    val tweaks: Set<DiscoveryTweak>,
    val stops: List<GeoPoint>,
    val turnsBack: Boolean,
    val distanceM: Double,
    val durationS: Double,
    val ascentM: Double,
    val highlight: String?,
    val gira: Boolean = false,
) {
    val isLoop: Boolean get() = stops.size >= 2 && stops.first().distanceTo(stops.last()) < LOOP_M

    private companion object {
        const val LOOP_M = 30.0
    }
}

interface SavedRoutesRepository {
    /** Newest first. */
    val routes: Flow<List<SavedRoute>>

    suspend fun save(route: DiscoveredRoute, purpose: DiscoveryPurpose, tweaks: Set<DiscoveryTweak>, gira: Boolean = false): Long

    suspend fun remove(id: Long)
}

/** Saved Descobrir routes in Room, on the phone only. */
@Singleton
class RoomSavedRoutesRepository @Inject constructor(
    private val database: SavedPlacesDatabase,
    private val clock: Clock,
) : SavedRoutesRepository {

    override val routes: Flow<List<SavedRoute>> = database.savedRoutes().observeAll().map { rows -> rows.mapNotNull(::fromRow) }

    override suspend fun save(route: DiscoveredRoute, purpose: DiscoveryPurpose, tweaks: Set<DiscoveryTweak>, gira: Boolean): Long =
        database.savedRoutes().insert(
            SavedRouteEntity(
                purpose = purpose.name,
                tweaks = tweaks.joinToString(",") { it.name },
                stops = encodeStops(route.stops),
                turnsBack = route.turnsBack,
                distanceM = route.route.distanceM,
                durationS = route.route.durationS,
                ascentM = route.route.ascentM,
                highlight = (route.anchors + route.highlights.pois).firstNotNullOfOrNull { it.name },
                createdAt = clock.millis(),
                gira = gira,
            ),
        )

    override suspend fun remove(id: Long) = database.savedRoutes().delete(id)

    private fun fromRow(row: SavedRouteEntity): SavedRoute? {
        val purpose = runCatching { DiscoveryPurpose.valueOf(row.purpose) }.getOrNull() ?: return null
        val stops = decodeStops(row.stops) ?: return null
        return SavedRoute(
            id = row.id,
            purpose = purpose,
            tweaks = row.tweaks.split(',').mapNotNull { name -> DiscoveryTweak.entries.firstOrNull { it.name == name } }.toSet(),
            stops = stops,
            turnsBack = row.turnsBack,
            distanceM = row.distanceM,
            durationS = row.durationS,
            ascentM = row.ascentM,
            highlight = row.highlight,
            gira = row.gira,
        )
    }

    companion object {
        fun encodeStops(stops: List<GeoPoint>): String =
            stops.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lat, it.lon) }

        /** Null when the text is damaged or has fewer than two stops. */
        fun decodeStops(text: String): List<GeoPoint>? = text.split(';').map { pair ->
            val (lat, lon) = pair.split(',').takeIf { it.size == 2 } ?: return null
            GeoPoint(lat.toDoubleOrNull() ?: return null, lon.toDoubleOrNull() ?: return null)
        }.takeIf { it.size >= 2 }
    }
}
