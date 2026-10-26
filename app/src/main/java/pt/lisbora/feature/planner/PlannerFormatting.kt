package pt.lisbora.feature.planner

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pt.lisbora.R
import pt.lisbora.feature.map.labelRes
import pt.lisbora.routing.HazardKind
import pt.lisbora.routing.RouteHazard
import pt.lisbora.routing.graph.EdgeInfra
import pt.lisbora.routing.search.Place
import pt.lisbora.routing.search.PlaceCategory
import java.util.Locale
import kotlin.math.roundToInt

private val PortugueseLocale: Locale = Locale.forLanguageTag("pt-PT")

/** "8 %" style percentages, rounded: "62%". */
fun formatPercent(share: Double): String = "${(share * 100).roundToInt()}%"

/** A grade in percent with one decimal below 10: "7,5%", "12%". */
fun formatGrade(percent: Double): String =
    if (percent < 10) String.format(PortugueseLocale, "%.1f%%", percent) else "${percent.roundToInt()}%"

/** Metres of climb: "85 m". */
fun formatMetres(metres: Double): String = "${metres.roundToInt()} m"

/** "7 min", "1 h 05 min"; at least one minute. */
@Composable
fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) stringResource(R.string.duration_minutes, minutes)
    else stringResource(R.string.duration_hours, minutes / 60, minutes % 60)
}

@StringRes
fun PlaceCategory.labelRes(): Int = when (this) {
    PlaceCategory.STREET -> R.string.category_street
    PlaceCategory.ADDRESS -> R.string.category_address
    PlaceCategory.LOCALITY -> R.string.category_locality
    PlaceCategory.NEIGHBOURHOOD -> R.string.category_neighbourhood
    PlaceCategory.METRO_STATION -> R.string.category_metro
    PlaceCategory.TRAIN_STATION -> R.string.category_train
    PlaceCategory.FERRY_TERMINAL -> R.string.category_ferry
    PlaceCategory.BUS_STATION -> R.string.category_bus
    PlaceCategory.BIKE_SHARE -> R.string.category_bike_share
    PlaceCategory.BIKE_SHOP -> R.string.category_bike_shop
    PlaceCategory.VIEWPOINT -> R.string.category_viewpoint
    PlaceCategory.PARK -> R.string.category_park
    PlaceCategory.BEACH -> R.string.category_beach
    PlaceCategory.MUSEUM -> R.string.category_museum
    PlaceCategory.MONUMENT -> R.string.category_monument
    PlaceCategory.ATTRACTION -> R.string.category_attraction
    PlaceCategory.PLACE_OF_WORSHIP -> R.string.category_worship
    PlaceCategory.UNIVERSITY -> R.string.category_university
    PlaceCategory.SCHOOL -> R.string.category_school
    PlaceCategory.HOSPITAL -> R.string.category_hospital
    PlaceCategory.HEALTH -> R.string.category_health
    PlaceCategory.LIBRARY -> R.string.category_library
    PlaceCategory.CULTURE -> R.string.category_culture
    PlaceCategory.SPORTS -> R.string.category_sports
    PlaceCategory.MARKET -> R.string.category_market
    PlaceCategory.SHOPPING -> R.string.category_shopping
    PlaceCategory.SHOP -> R.string.category_shop
    PlaceCategory.FOOD -> R.string.category_food
    PlaceCategory.HOTEL -> R.string.category_hotel
    PlaceCategory.PUBLIC_SERVICE -> R.string.category_public_service
    PlaceCategory.OTHER -> R.string.category_other
}

@DrawableRes
fun PlaceCategory.iconRes(): Int = when (this) {
    PlaceCategory.STREET -> R.drawable.ic_signpost
    PlaceCategory.LOCALITY, PlaceCategory.NEIGHBOURHOOD -> R.drawable.ic_city
    PlaceCategory.METRO_STATION, PlaceCategory.TRAIN_STATION, PlaceCategory.BUS_STATION -> R.drawable.ic_train
    PlaceCategory.FERRY_TERMINAL -> R.drawable.ic_boat
    PlaceCategory.BIKE_SHARE, PlaceCategory.BIKE_SHOP -> R.drawable.ic_bike
    PlaceCategory.VIEWPOINT -> R.drawable.ic_viewpoint
    PlaceCategory.PARK, PlaceCategory.BEACH -> R.drawable.ic_park
    PlaceCategory.MUSEUM, PlaceCategory.MONUMENT, PlaceCategory.ATTRACTION, PlaceCategory.CULTURE,
    PlaceCategory.PLACE_OF_WORSHIP, PlaceCategory.LIBRARY -> R.drawable.ic_museum
    PlaceCategory.UNIVERSITY, PlaceCategory.SCHOOL -> R.drawable.ic_school
    PlaceCategory.HOSPITAL, PlaceCategory.HEALTH -> R.drawable.ic_hospital
    PlaceCategory.FOOD -> R.drawable.ic_restaurant
    PlaceCategory.MARKET, PlaceCategory.SHOPPING, PlaceCategory.SHOP -> R.drawable.ic_shopping
    PlaceCategory.ADDRESS, PlaceCategory.SPORTS, PlaceCategory.HOTEL, PlaceCategory.PUBLIC_SERVICE,
    PlaceCategory.OTHER -> R.drawable.ic_place
}

/** The name to show for a place; a nameless pin is "Ponto no mapa". */
@Composable
fun Place.displayName(): String = name.ifBlank { stringResource(R.string.place_pin) }

@StringRes
fun EdgeInfra.labelRes(): Int = when (this) {
    EdgeInfra.NONE -> R.string.infra_none
    EdgeInfra.OSM_CYCLEWAY -> R.string.infra_osm_cycleway
    else -> cmlClass!!.labelRes()
}

/** One line describing a hazard: "Descida de 11%", "Carris de elétrico". */
@Composable
fun RouteHazard.title(): String = when (kind) {
    HazardKind.TRAM_TRACKS -> stringResource(R.string.hazard_tram)
    HazardKind.COBBLES -> stringResource(R.string.hazard_cobbles)
    HazardKind.BUSY_ROAD -> stringResource(R.string.hazard_busy_road)
    HazardKind.STEEP_DESCENT -> stringResource(
        if (walkAdvised) R.string.hazard_descent_walk else R.string.hazard_descent,
        formatGrade(gradePercent ?: 0.0),
    )
    HazardKind.STEEP_CLIMB -> stringResource(R.string.hazard_climb, formatGrade(gradePercent ?: 0.0))
    HazardKind.WALK -> stringResource(R.string.hazard_walk)
    HazardKind.FERRY -> stringResource(R.string.hazard_ferry)
}

@StringRes
fun stressLabelRes(level: Int): Int = when (level) {
    1 -> R.string.stress_1
    2 -> R.string.stress_2
    3 -> R.string.stress_3
    else -> R.string.stress_4
}
