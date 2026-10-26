package pt.lisbora.pipeline.datapack

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The version written inside a built file (`graph.lbg`, `search.lbs`): the date of its newest source,
 * not the build time, so building again from the same sources gives the same bytes and nothing to publish.
 */
object SourceVersion {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").withZone(ZoneOffset.UTC)

    /** [sourceDates] are ISO instants ("2026-10-24T20:20:51Z"); unknown or unreadable ones are skipped. */
    fun of(vararg sourceDates: String?, fallback: Instant): String =
        FORMAT.format(sourceDates.mapNotNull { date -> date?.let { runCatching { Instant.parse(it) }.getOrNull() } }.maxOrNull() ?: fallback)
}
