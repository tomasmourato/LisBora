package pt.lisbora.data.saved

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A place the rider saved: Home, Work (one of each) or any favourite. */
@Entity(tableName = "saved_places", indices = [Index("kind")])
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [SavedKind] name. */
    val kind: String,
    val name: String,
    val context: String?,
    /** [pt.lisbora.routing.search.PlaceCategory.code]. */
    val category: Int,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
)

/** A place recently chosen in the search, newest first. */
@Entity(tableName = "recent_places", indices = [Index(value = ["name", "lat", "lon"], unique = true)])
data class RecentPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val context: String?,
    val category: Int,
    val lat: Double,
    val lon: Double,
    val usedAt: Long,
)

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY CASE kind WHEN 'HOME' THEN 0 WHEN 'WORK' THEN 1 ELSE 2 END, createdAt DESC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Insert
    suspend fun insert(place: SavedPlaceEntity): Long

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM saved_places WHERE kind = :kind")
    suspend fun deleteKind(kind: String)

    /** Home and Work are unique: setting one replaces the previous. */
    @Transaction
    suspend fun replaceKind(place: SavedPlaceEntity) {
        deleteKind(place.kind)
        insert(place)
    }
}

@Dao
interface RecentPlaceDao {
    @Query("SELECT * FROM recent_places ORDER BY usedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: RecentPlaceEntity)

    @Query("DELETE FROM recent_places WHERE id NOT IN (SELECT id FROM recent_places ORDER BY usedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM recent_places")
    suspend fun clear()
}

@Database(entities = [SavedPlaceEntity::class, RecentPlaceEntity::class], version = 1, exportSchema = true)
abstract class SavedPlacesDatabase : RoomDatabase() {
    abstract fun savedPlaces(): SavedPlaceDao
    abstract fun recentPlaces(): RecentPlaceDao

    companion object {
        const val NAME = "lisbora.db"
    }
}
