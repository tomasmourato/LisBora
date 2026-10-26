package pt.lisbora.data.saved

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

enum class SavedKind { HOME, WORK, FAVOURITE }

data class SavedPlace(val id: Long, val kind: SavedKind, val place: Place)

interface SavedPlacesRepository {
    /** Home first, then Work, then favourites (newest first). */
    val saved: Flow<List<SavedPlace>>

    /** Places recently chosen in the search, newest first. */
    val recent: Flow<List<Place>>

    /** Saves [place] as [kind]; Home and Work replace the previous one. */
    suspend fun save(place: Place, kind: SavedKind)

    suspend fun remove(id: Long)

    suspend fun addRecent(place: Place)

    suspend fun clearRecent()
}

/** Saved and recent places in Room, on the phone only. */
@Singleton
class RoomSavedPlacesRepository @Inject constructor(
    private val database: SavedPlacesDatabase,
    private val clock: Clock,
) : SavedPlacesRepository {

    override val saved: Flow<List<SavedPlace>> = database.savedPlaces().observeAll().map { rows ->
        rows.mapNotNull { row ->
            val kind = runCatching { SavedKind.valueOf(row.kind) }.getOrNull() ?: return@mapNotNull null
            SavedPlace(row.id, kind, Place(row.name, PlaceCategory.fromCode(row.category), GeoPoint(row.lat, row.lon), row.context))
        }
    }

    override val recent: Flow<List<Place>> = database.recentPlaces().observeRecent(RECENT_SHOWN).map { rows ->
        rows.map { Place(it.name, PlaceCategory.fromCode(it.category), GeoPoint(it.lat, it.lon), it.context) }
    }

    override suspend fun save(place: Place, kind: SavedKind) {
        val row = SavedPlaceEntity(
            kind = kind.name, name = place.name, context = place.context, category = place.category.code,
            lat = place.point.lat, lon = place.point.lon, createdAt = clock.millis(),
        )
        if (kind == SavedKind.FAVOURITE) database.savedPlaces().insert(row) else database.savedPlaces().replaceKind(row)
    }

    override suspend fun remove(id: Long) = database.savedPlaces().delete(id)

    override suspend fun addRecent(place: Place) {
        val dao = database.recentPlaces()
        dao.upsert(
            RecentPlaceEntity(
                name = place.name, context = place.context, category = place.category.code,
                lat = place.point.lat, lon = place.point.lon, usedAt = clock.millis(),
            ),
        )
        dao.trim(RECENT_KEPT)
    }

    override suspend fun clearRecent() = database.recentPlaces().clear()

    companion object {
        const val RECENT_SHOWN = 8
        const val RECENT_KEPT = 30
    }
}
