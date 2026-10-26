package pt.lisbora.data.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.lisbora.data.datapack.DataPackInstaller
import pt.lisbora.data.datapack.DataPackStore
import pt.lisbora.data.routing.RoutingRepository
import pt.lisbora.di.DefaultDispatcher
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import pt.lisbora.routing.search.SearchHit
import pt.lisbora.routing.search.SearchIndex
import pt.lisbora.routing.search.SearchIndexFormat
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface SearchRepository {
    /** Places matching [query], nearer ones first among equally good matches when [near] is known. */
    suspend fun search(query: String, near: GeoPoint?): List<SearchHit>

    /**
     * A name for a point picked on the map: the address there, else a place right on it, else the street;
     * null when nothing is close. The context ("Arroios, Lisboa") comes from the nearest indexed place.
     */
    suspend fun describe(point: GeoPoint): Place?

    /** Loads the index ahead of the first query. */
    suspend fun warmUp()
}

/** Offline search over the index in the installed data pack (`search.lbs`, built by the pipeline). */
@Singleton
class OfflineSearchRepository @Inject constructor(
    private val packs: DataPackStore,
    private val routing: RoutingRepository,
    @param:DefaultDispatcher private val compute: CoroutineDispatcher,
) : SearchRepository {

    private val mutex = Mutex()
    private var index: SearchIndex? = null

    override suspend fun search(query: String, near: GeoPoint?): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val index = index()
        return withContext(compute) { index.search(query, near) }
    }

    override suspend fun describe(point: GeoPoint): Place? {
        val index = index()
        val (exact, context) = withContext(compute) {
            val address = index.nearest(point, ADDRESS_RADIUS_M) { it == PlaceCategory.ADDRESS }
            val spot = address ?: index.nearest(point, PLACE_RADIUS_M) { it != PlaceCategory.ADDRESS && it != PlaceCategory.STREET }
            spot?.place to index.nearest(point, CONTEXT_RADIUS_M)?.place?.context
        }
        if (exact != null) return exact.copy(point = point)
        val street = runCatching { routing.streetName(point) }.getOrNull() ?: return null
        return Place(street, PlaceCategory.STREET, point, context)
    }

    override suspend fun warmUp() {
        index()
    }

    private suspend fun index(): SearchIndex = mutex.withLock {
        index ?: run {
            val file = packs.pack().file(DataPackInstaller.SEARCH_FILE)
                ?: throw IOException("Data pack has no ${DataPackInstaller.SEARCH_FILE}")
            withContext(compute) {
                val data = try {
                    file.inputStream().buffered().use(SearchIndexFormat::read)
                } catch (e: IOException) {
                    packs.reportBroken()
                    throw e
                }
                SearchIndex(data)
            }
        }.also { index = it }
    }

    companion object {
        const val ADDRESS_RADIUS_M = 25.0
        const val PLACE_RADIUS_M = 20.0
        const val CONTEXT_RADIUS_M = 400.0
    }
}
