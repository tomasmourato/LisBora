package pt.lisbora.routing.search

import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphData

/** Collects places and turns them into a [SearchIndexData] (used by the pipeline and by tests). */
class SearchIndexBuilder {

    private class Entry(
        val name: String,
        val category: PlaceCategory,
        val point: GeoPoint,
        val context: String?,
        val importance: Int,
        val altNames: List<String>,
    )

    private val entries = mutableListOf<Entry>()

    val size: Int get() = entries.size

    /**
     * Adds a place; [importance] is clamped to 0..100. [altNames] (other or older names) also match
     * but are never shown. Blank names are ignored.
     */
    fun add(
        name: String,
        category: PlaceCategory,
        point: GeoPoint,
        context: String? = null,
        importance: Int = 0,
        altNames: List<String> = emptyList(),
    ) {
        if (name.isBlank() || SearchText.tokens(name).isEmpty()) return
        entries += Entry(name.trim(), category, point, context?.takeIf { it.isNotBlank() }, importance.coerceIn(0, 100), altNames)
    }

    fun build(dataVersion: String): SearchIndexData {
        val contexts = entries.mapNotNull { it.context }.distinct().sorted()
        val contextIds = contexts.withIndex().associate { (i, c) -> c to i }

        val entryTokens = entries.map { e -> (listOf(e.name) + e.altNames).flatMap(SearchText::indexTokens).distinct() }
        val tokens = entryTokens.flatten().distinct().sorted()
        val tokenIds = tokens.withIndex().associate { (i, t) -> t to i }

        val postingLists = Array(tokens.size) { mutableListOf<Int>() }
        entryTokens.forEachIndexed { entry, list -> list.forEach { postingLists[tokenIds.getValue(it)] += entry } }
        val postingStart = IntArray(tokens.size + 1)
        postingLists.forEachIndexed { t, list -> postingStart[t + 1] = postingStart[t] + list.size }
        val postings = IntArray(postingStart.last())
        postingLists.forEachIndexed { t, list -> list.sorted().forEachIndexed { i, e -> postings[postingStart[t] + i] = e } }

        val words = entries.map { e -> SearchText.tokens(e.name).filterNot(SearchText::isStopword) }
        return SearchIndexData(
            dataVersion = dataVersion,
            names = entries.map { it.name },
            categories = ByteArray(entries.size) { entries[it].category.code.toByte() },
            latE7 = IntArray(entries.size) { GraphData.toE7(entries[it].point.lat) },
            lonE7 = IntArray(entries.size) { GraphData.toE7(entries[it].point.lon) },
            importance = ByteArray(entries.size) { entries[it].importance.toByte() },
            contextIds = IntArray(entries.size) { i -> entries[i].context?.let(contextIds::getValue) ?: -1 },
            contexts = contexts,
            wordCounts = ByteArray(entries.size) { words[it].size.coerceAtMost(Byte.MAX_VALUE.toInt()).toByte() },
            firstTokens = IntArray(entries.size) { i -> words[i].firstOrNull()?.let(tokenIds::getValue) ?: -1 },
            tokens = tokens,
            postingStart = postingStart,
            postings = postings,
        )
    }
}
