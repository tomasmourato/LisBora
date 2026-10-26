package pt.lisbora.routing.search

import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.graph.GraphData

/**
 * Raw arrays of a search index, exactly as stored in `search.lbs`. Each entry is one place;
 * [tokens] lists every normalised word (sorted), and [postings] the entries containing each one.
 */
class SearchIndexData(
    val dataVersion: String,
    val names: List<String>,
    /** [PlaceCategory.code] of each entry. */
    val categories: ByteArray,
    val latE7: IntArray,
    val lonE7: IntArray,
    /** 0..100: how prominent the place is (a long avenue, a big park). */
    val importance: ByteArray,
    /** Index into [contexts] ("Arroios, Lisboa"), or -1. */
    val contextIds: IntArray,
    val contexts: List<String>,
    /** Words in the name that are not stopwords, for preferring names that match the query closely. */
    val wordCounts: ByteArray,
    /** Token id of the first word of the name that is not a stopword, or -1. */
    val firstTokens: IntArray,
    /** Every distinct token, sorted with [String.compareTo] so tokens sharing a prefix are contiguous. */
    val tokens: List<String>,
    /** Entries containing token t: postings[postingStart[t] until postingStart[t + 1]], ascending. */
    val postingStart: IntArray,
    val postings: IntArray,
) {
    val entryCount: Int get() = names.size

    init {
        val n = names.size
        require(categories.size == n && latE7.size == n && lonE7.size == n && importance.size == n &&
            contextIds.size == n && wordCounts.size == n && firstTokens.size == n) { "Entry arrays differ in size" }
        require(postingStart.size == tokens.size + 1) { "postingStart must have tokens + 1 entries" }
        require(postingStart.last() == postings.size) { "postingStart does not cover the postings" }
        require(contextIds.all { it in -1 until contexts.size }) { "Context id out of range" }
        require(firstTokens.all { it in -1 until tokens.size }) { "First token out of range" }
        require(postings.all { it in 0 until n }) { "Posting out of range" }
    }

    fun point(entry: Int): GeoPoint = GeoPoint(latE7[entry] / GraphData.E7, lonE7[entry] / GraphData.E7)

    fun category(entry: Int): PlaceCategory = PlaceCategory.fromCode(categories[entry].toInt())

    fun context(entry: Int): String? = contextIds[entry].let { if (it < 0) null else contexts[it] }
}
