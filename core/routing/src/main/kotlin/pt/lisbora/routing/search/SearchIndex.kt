package pt.lisbora.routing.search

import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphData
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A place the rider can search for and route to. */
data class Place(val name: String, val category: PlaceCategory, val point: GeoPoint, val context: String? = null)

data class SearchHit(val place: Place, val score: Double, val distanceM: Double?)

/**
 * Offline place search over a [SearchIndexData]. Every word typed must match the start of a word of the
 * name (accents and case ignored, abbreviations like "r." or "lg" expanded); stopwords ("da", "de") are
 * optional. Matches are ranked by how closely the name fits, the kind of place, its importance and,
 * when [search] gets a position, how near it is. Thread-safe: queries allocate their own state.
 */
class SearchIndex(val data: SearchIndexData) {

    private val grid = EntryGrid(data)

    fun place(entry: Int): Place = Place(data.names[entry], data.category(entry), data.point(entry), data.context(entry))

    fun search(query: String, near: GeoPoint? = null, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val typed = SearchText.tokens(query).take(MAX_QUERY_TOKENS)
        if (typed.isEmpty()) return emptyList()
        val words = typed.filterNot(SearchText::isStopword).ifEmpty { typed }
        // Generic words ("estação", "metro") are optional next to a name, but needed when typed alone.
        val required = words.filterNot { it in SearchText.GENERIC_WORDS }.ifEmpty { words }
        val generic = words.filter { it in SearchText.GENERIC_WORDS && it !in required }
        val optional = typed.filter { SearchText.isStopword(it) && it !in required }
        val wantsAddresses = typed.any(SearchText::isNumber)

        val matches = required.map { word ->
            val m = match(word)
            // A generic word typed alone also matches places of its kinds whose name lacks the word.
            val kinds = SearchText.GENERIC_WORDS[word]
            if (kinds == null) m else WordMatch(union(m.all, entriesOf(kinds)), m.exact, m.ranges)
        }
        val candidates = matches.map { it.all }.sortedBy { it.size }.reduce(::intersect)
        val optionalMatches = optional.map(::match)
        val genericMatches = generic.map { it to match(it) }

        val scored = ArrayList<SearchHit>()
        for (entry in candidates) {
            val category = data.category(entry)
            if (category == PlaceCategory.ADDRESS && !wantsAddresses) continue
            var text = 0.0
            matches.forEach { m -> text += if (m.exact.binarySearch(entry) >= 0) EXACT_WORD else PREFIX_WORD }
            optionalMatches.forEach { m -> if (m.all.binarySearch(entry) >= 0) text += STOPWORD }
            genericMatches.forEach { (word, m) ->
                if (m.all.binarySearch(entry) >= 0) text += PREFIX_WORD
                else if (category in SearchText.GENERIC_WORDS.getValue(word)) text += GENERIC_KIND
            }
            text -= EXTRA_WORD_PENALTY * max(0, data.wordCounts[entry] - required.size)
            val first = data.firstTokens[entry]
            if (first >= 0 && matches.first().ranges.any { first in it }) text += FIRST_WORD_BONUS
            val point = data.point(entry)
            val distance = near?.distanceTo(point)
            var score = text + category.weight + data.importance[entry] * IMPORTANCE_SCALE
            if (distance != null) score -= DISTANCE_WEIGHT * ln(1 + distance / 1000)
            scored += SearchHit(Place(data.names[entry], category, point, data.context(entry)), score, distance)
        }
        scored.sortByDescending { it.score }

        // The same place often appears twice (a node and an area, a museum and its shop): keep the best,
        // unless both are stations of different kinds (the train and the metro "Oriente").
        val result = ArrayList<SearchHit>(limit)
        for (hit in scored) {
            if (result.size >= limit) break
            val duplicate = result.any {
                val a = it.place.category
                val b = hit.place.category
                (a == b || !(a.isTransport && b.isTransport)) &&
                    it.place.name.equals(hit.place.name, ignoreCase = true) &&
                    it.place.point.distanceTo(hit.place.point) < DUPLICATE_RADIUS_M
            }
            if (!duplicate) result += hit
        }
        return result
    }

    /** The place closest to [point] within [maxDistanceM] whose category passes [accept], or null. */
    fun nearest(point: GeoPoint, maxDistanceM: Double, accept: (PlaceCategory) -> Boolean = { true }): SearchHit? {
        var best: SearchHit? = null
        grid.forEachNear(point, maxDistanceM) { entry ->
            val category = data.category(entry)
            if (!accept(category)) return@forEachNear
            val distance = point.distanceTo(data.point(entry))
            if (distance <= maxDistanceM && (best == null || distance < best!!.distanceM!!)) {
                best = SearchHit(place(entry), 0.0, distance)
            }
        }
        return best
    }

    /** Entries matching one typed word: every match, the exact ones, and the token id ranges that matched. */
    private class WordMatch(val all: IntArray, val exact: IntArray, val ranges: List<IntRange>)

    private fun match(word: String): WordMatch {
        val forms = SearchText.expansions(word)
        val ranges = forms.map(::prefixRange).filterNot { it.isEmpty() }
        val exactIds = forms.mapNotNull { form -> data.tokens.binarySearch(form).takeIf { it >= 0 } }
        return WordMatch(
            all = postingsOf(ranges.flatMap { it.toList() }),
            exact = postingsOf(exactIds),
            ranges = ranges,
        )
    }

    /** Token ids whose token starts with [prefix] (contiguous, since tokens are sorted). */
    private fun prefixRange(prefix: String): IntRange {
        var low = data.tokens.binarySearch(prefix).let { if (it >= 0) it else -it - 1 }
        val start = low
        while (low < data.tokens.size && data.tokens[low].startsWith(prefix)) low++
        return start until low
    }

    /** Sorted, distinct entries listed under any of [tokenIds]. */
    private fun postingsOf(tokenIds: List<Int>): IntArray {
        var total = 0
        tokenIds.forEach { total += data.postingStart[it + 1] - data.postingStart[it] }
        val merged = IntArray(total)
        var n = 0
        tokenIds.forEach { t ->
            for (i in data.postingStart[t] until data.postingStart[t + 1]) merged[n++] = data.postings[i]
        }
        merged.sort()
        var unique = 0
        for (i in merged.indices) if (i == 0 || merged[i] != merged[i - 1]) merged[unique++] = merged[i]
        return merged.copyOf(unique)
    }

    /** Entries of the given categories, ascending. */
    private fun entriesOf(categories: Set<PlaceCategory>): IntArray {
        val codes = categories.map { it.code.toByte() }.toSet()
        return (0 until data.entryCount).filter { data.categories[it] in codes }.toIntArray()
    }

    private fun union(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(a.size + b.size)
        var i = 0
        var j = 0
        var n = 0
        while (i < a.size || j < b.size) {
            out[n++] = when {
                j >= b.size || (i < a.size && a[i] < b[j]) -> a[i++]
                i >= a.size || b[j] < a[i] -> b[j++]
                else -> { j++; a[i++] }
            }
        }
        return out.copyOf(n)
    }

    private fun intersect(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(min(a.size, b.size))
        var i = 0
        var j = 0
        var n = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] < b[j] -> i++
                a[i] > b[j] -> j++
                else -> { out[n++] = a[i]; i++; j++ }
            }
        }
        return out.copyOf(n)
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_QUERY_TOKENS = 8
        const val EXACT_WORD = 2.0
        const val PREFIX_WORD = 1.0
        const val STOPWORD = 0.3
        const val GENERIC_KIND = 1.5
        const val EXTRA_WORD_PENALTY = 0.35
        const val FIRST_WORD_BONUS = 1.0
        const val IMPORTANCE_SCALE = 0.02
        const val DISTANCE_WEIGHT = 1.0
        const val DUPLICATE_RADIUS_M = 400.0
    }
}

/** Uniform grid over the entries' bounding box, for nearest-place lookups (reverse geocoding). */
private class EntryGrid(private val data: SearchIndexData) {
    private val south: Double
    private val west: Double
    private val cellLat: Double
    private val cellLon: Double
    private val rows: Int
    private val columns: Int
    private val cellStart: IntArray
    private val cellEntries: IntArray

    init {
        val n = data.entryCount
        var minLat = Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (i in 0 until n) {
            val lat = data.latE7[i] / GraphData.E7
            val lon = data.lonE7[i] / GraphData.E7
            minLat = min(minLat, lat); maxLat = max(maxLat, lat)
            minLon = min(minLon, lon); maxLon = max(maxLon, lon)
        }
        if (n == 0) { minLat = 0.0; maxLat = 0.0; minLon = 0.0; maxLon = 0.0 }
        south = minLat
        west = minLon
        cellLat = CELL_M / METRES_PER_DEGREE
        cellLon = CELL_M / (METRES_PER_DEGREE * cos(Math.toRadians((minLat + maxLat) / 2)))
        rows = ((maxLat - minLat) / cellLat).toInt() + 1
        columns = ((maxLon - minLon) / cellLon).toInt() + 1
        cellStart = IntArray(rows * columns + 1)
        val cells = IntArray(n) { cellOf(data.latE7[it] / GraphData.E7, data.lonE7[it] / GraphData.E7) }
        cells.forEach { cellStart[it + 1]++ }
        for (c in 0 until rows * columns) cellStart[c + 1] += cellStart[c]
        val fill = cellStart.copyOf(rows * columns)
        cellEntries = IntArray(n)
        cells.forEachIndexed { entry, cell -> cellEntries[fill[cell]++] = entry }
    }

    inline fun forEachNear(point: GeoPoint, radiusM: Double, action: (Int) -> Unit) {
        val dLat = radiusM / METRES_PER_DEGREE
        val dLon = dLat * cellLon / cellLat
        val r0 = row(point.lat - dLat)
        val r1 = row(point.lat + dLat)
        val c0 = column(point.lon - dLon)
        val c1 = column(point.lon + dLon)
        for (r in r0..r1) for (c in c0..c1) {
            val cell = r * columns + c
            for (i in cellStart[cell] until cellStart[cell + 1]) action(cellEntries[i])
        }
    }

    private fun cellOf(lat: Double, lon: Double) = row(lat) * columns + column(lon)
    fun row(lat: Double) = floor((lat - south) / cellLat).toInt().coerceIn(0, rows - 1)
    fun column(lon: Double) = floor((lon - west) / cellLon).toInt().coerceIn(0, columns - 1)

    companion object {
        const val CELL_M = 250.0
        const val METRES_PER_DEGREE = Math.PI / 180 * GeoPoint.EARTH_RADIUS_M
    }
}
