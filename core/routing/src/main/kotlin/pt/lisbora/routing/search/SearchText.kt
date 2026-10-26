package pt.lisbora.routing.search

import java.text.Normalizer

/**
 * Text normalisation shared by the pipeline (which builds the index) and the app (which queries it),
 * so both sides always produce the same tokens: lower case, no accents ("Praça" → "praca"),
 * punctuation as spaces, and numbers split from letters ("1º" → "1", "o").
 */
object SearchText {

    /** Portuguese articles and prepositions: matched when typed, but never required and never penalised. */
    val STOPWORDS: Set<String> = setOf(
        "a", "o", "as", "os", "da", "de", "do", "das", "dos", "e", "d", "em", "na", "no", "nas", "nos", "ao", "aos",
    )

    /**
     * Common abbreviations in Portuguese addresses, expanded when typed. Abbreviations that are already
     * a prefix of the full word ("av" → "avenida") need no entry.
     */
    val ALIASES: Map<String, List<String>> = mapOf(
        "r" to listOf("rua"),
        "lg" to listOf("largo"),
        "lgo" to listOf("largo"),
        "pc" to listOf("praca"),
        "pca" to listOf("praca"),
        "tv" to listOf("travessa"),
        "trv" to listOf("travessa"),
        "cc" to listOf("calcada"),
        "cal" to listOf("calcada", "calcadinha"),
        "estr" to listOf("estrada"),
        "al" to listOf("alameda"),
        "bc" to listOf("beco"),
        "esc" to listOf("escadinhas"),
        "qta" to listOf("quinta"),
        "sta" to listOf("santa"),
        "sto" to listOf("santo"),
        "s" to listOf("sao", "santa", "santo"),
        "dr" to listOf("doutor", "doutora"),
        "eng" to listOf("engenheiro", "engenheira"),
        "gen" to listOf("general"),
        "prof" to listOf("professor", "professora"),
        "cmdt" to listOf("comandante"),
        "cmte" to listOf("comandante"),
        "d" to listOf("dom", "dona"),
        "n" to listOf("nossa"),
        "sr" to listOf("senhor", "senhora"),
        "sra" to listOf("senhora"),
        "lx" to listOf("lx", "lisboa"),
    )

    /**
     * Words that name a kind of place rather than a place ("estação", "metro", "barco"). Typed with a name,
     * they are optional and favour places of their categories: "gare do oriente" finds the station "Oriente".
     */
    val GENERIC_WORDS: Map<String, Set<PlaceCategory>> = run {
        val rail = setOf(PlaceCategory.TRAIN_STATION, PlaceCategory.METRO_STATION)
        val ferry = setOf(PlaceCategory.FERRY_TERMINAL)
        mapOf(
            "estacao" to rail + ferry + PlaceCategory.BUS_STATION,
            "gare" to rail + PlaceCategory.BUS_STATION,
            "comboio" to setOf(PlaceCategory.TRAIN_STATION),
            "comboios" to setOf(PlaceCategory.TRAIN_STATION),
            "cp" to setOf(PlaceCategory.TRAIN_STATION),
            "metro" to setOf(PlaceCategory.METRO_STATION),
            "metropolitano" to setOf(PlaceCategory.METRO_STATION),
            "terminal" to ferry + PlaceCategory.BUS_STATION,
            "fluvial" to ferry,
            "barco" to ferry,
            "barcos" to ferry,
            "ferry" to ferry,
            "rodoviaria" to setOf(PlaceCategory.BUS_STATION),
        )
    }

    private val combiningMarks = Regex("\\p{Mn}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val digitLetterBoundary = Regex("(?<=\\p{N})(?=\\p{L})|(?<=\\p{L})(?=\\p{N})")

    /** Lower case without accents; every run of punctuation or spaces becomes a single space. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD)
        return decomposed.replace(combiningMarks, "").replace(separators, " ").trim()
    }

    /** Tokens of [text] for querying: apostrophes join ("Sant'Ana" → "santana"). */
    fun tokens(text: String): List<String> = split(normalize(removeApostrophes(text)))

    /**
     * Tokens of [text] for indexing: both the joined and the split form of words with apostrophes
     * ("Sant'Ana" → "santana", "sant", "ana"), so either way of typing them matches.
     */
    fun indexTokens(text: String): List<String> =
        (tokens(text) + split(normalize(text))).distinct()

    fun isStopword(token: String): Boolean = token in STOPWORDS

    fun isNumber(token: String): Boolean = token.isNotEmpty() && token.all { it.isDigit() }

    /** The words a typed [token] may stand for: itself plus any expansion of an abbreviation. */
    fun expansions(token: String): List<String> = listOf(token) + ALIASES[token].orEmpty().filter { it != token }

    private fun removeApostrophes(text: String) = text.replace(Regex("[’'`´]"), "")

    private fun split(normalized: String): List<String> =
        normalized.split(' ').flatMap { it.split(digitLetterBoundary) }.filter { it.isNotEmpty() }
}
