package pt.lisbora.feature.map

import androidx.annotation.StringRes
import pt.lisbora.R
import pt.lisbora.routing.model.CycleInfraClass
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@StringRes
fun CycleInfraClass.labelRes(): Int = when (this) {
    CycleInfraClass.SEGREGATED_TRACK -> R.string.cycle_class_segregated
    CycleInfraClass.PAINTED_LANE -> R.string.cycle_class_lane
    CycleInfraClass.SHARED_STREET_30 -> R.string.cycle_class_shared30
    CycleInfraClass.SHARED_WITH_PEDESTRIANS -> R.string.cycle_class_shared_ped
    CycleInfraClass.TRAIL -> R.string.cycle_class_trail
}

private val PortugueseLocale: Locale = Locale.forLanguageTag("pt-PT")
private val LisbonZone: ZoneId = ZoneId.of("Europe/Lisbon")
private val DateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", PortugueseLocale)

/** "157 m" below 1 km, "1,2 km" above. */
fun formatLength(meters: Double): String =
    if (meters < 1000) "${Math.round(meters)} m" else String.format(PortugueseLocale, "%.1f km", meters / 1000)

/** "118,9 km" */
fun formatKm(km: Double): String = String.format(PortugueseLocale, "%.1f km", km)

/** "23/10/2026", in Lisbon time. */
fun formatDate(instant: Instant): String = DateFormat.format(instant.atZone(LisbonZone))
