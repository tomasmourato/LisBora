package pt.lisbora.pipeline.search

import com.graphhopper.reader.ReaderElement
import pt.lisbora.routing.search.PlaceCategory
import kotlin.math.log10
import kotlin.math.roundToInt

/** How OSM tags map to searchable places: name, [PlaceCategory] and a rough importance (0..100). */
object PlaceTags {

    /** The name shown in the app: `name`, else `name:pt`. */
    fun name(element: ReaderElement): String? =
        (element.getTag("name") ?: element.getTag("name:pt"))?.trim()?.takeIf { it.isNotEmpty() }

    /** Other names people may type: alternative, short, official and former names (`;`-separated in OSM). */
    fun altNames(element: ReaderElement): List<String> = ALT_NAME_KEYS
        .flatMap { key -> element.getTag(key)?.split(';').orEmpty() }
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != name(element) }
        .distinct()

    /** Category of a named feature, or null when it is not something people search for. */
    @Suppress("CyclomaticComplexMethod")
    fun category(e: ReaderElement): PlaceCategory? {
        if (INACTIVE.any { e.hasTag(it) }) return null
        val amenity = e.getTag("amenity")
        val tourism = e.getTag("tourism")
        val leisure = e.getTag("leisure")
        val railway = e.getTag("railway")
        val shop = e.getTag("shop")
        return when {
            isMetro(e) -> PlaceCategory.METRO_STATION
            railway == "station" || railway == "halt" -> PlaceCategory.TRAIN_STATION
            amenity == "ferry_terminal" -> PlaceCategory.FERRY_TERMINAL
            amenity == "bus_station" -> PlaceCategory.BUS_STATION
            amenity == "bicycle_rental" -> PlaceCategory.BIKE_SHARE
            shop == "bicycle" -> PlaceCategory.BIKE_SHOP
            tourism == "viewpoint" -> PlaceCategory.VIEWPOINT
            leisure in setOf("park", "garden", "nature_reserve") -> PlaceCategory.PARK
            e.hasTag("natural", "beach") -> PlaceCategory.BEACH
            tourism == "museum" -> PlaceCategory.MUSEUM
            tourism in setOf("attraction", "zoo", "aquarium", "theme_park") -> PlaceCategory.ATTRACTION
            e.getTag("historic") in HISTORIC -> PlaceCategory.MONUMENT
            amenity == "place_of_worship" -> PlaceCategory.PLACE_OF_WORSHIP
            amenity in setOf("university", "college") -> PlaceCategory.UNIVERSITY
            amenity in setOf("school", "kindergarten") -> PlaceCategory.SCHOOL
            amenity == "hospital" -> PlaceCategory.HOSPITAL
            amenity in setOf("clinic", "doctors", "pharmacy", "dentist") -> PlaceCategory.HEALTH
            amenity == "library" -> PlaceCategory.LIBRARY
            amenity in setOf("theatre", "cinema", "arts_centre", "concert_hall", "events_venue") ||
                tourism == "gallery" -> PlaceCategory.CULTURE
            leisure in setOf("stadium", "sports_centre", "swimming_pool", "marina", "golf_course", "ice_rink") -> PlaceCategory.SPORTS
            amenity == "marketplace" -> PlaceCategory.MARKET
            shop in setOf("mall", "department_store", "supermarket") -> PlaceCategory.SHOPPING
            shop != null && shop != "vacant" -> PlaceCategory.SHOP
            amenity in setOf("restaurant", "cafe", "bar", "pub", "fast_food", "ice_cream", "food_court", "biergarten") -> PlaceCategory.FOOD
            tourism in setOf("hotel", "hostel", "guest_house", "motel", "apartment") -> PlaceCategory.HOTEL
            amenity in PUBLIC_SERVICES || e.hasTag("office", "government") -> PlaceCategory.PUBLIC_SERVICE
            e.hasTag("building", "train_station") -> PlaceCategory.TRAIN_STATION
            amenity == "fountain" -> PlaceCategory.MONUMENT
            isOtherDestination(e) -> PlaceCategory.OTHER
            else -> null
        }
    }

    /**
     * Named features outside the categories above that people still look for: anything with a Wikidata
     * entry (LX Factory is `landuse=retail`), airports, lighthouses, offices and the like.
     */
    private fun isOtherDestination(e: ReaderElement): Boolean {
        if (e.hasTag("highway") || e.hasTag("boundary") || e.hasTag("place") || e.hasTag("route")) return false
        if (e.getTag("amenity") in NOT_DESTINATIONS) return false
        if (e.hasTag("wikidata") || e.hasTag("wikipedia")) return true
        return e.hasTag("aeroway", "aerodrome", "terminal") || e.hasTag("man_made", "lighthouse", "tower", "pier") ||
            e.hasTag("landuse", "retail", "commercial") || OTHER_KEYS.any { e.hasTag(it) } ||
            (e.getTag("amenity") != null && e.getTag("amenity") !in NOT_DESTINATIONS)
    }

    /** Importance of a point of interest: its kind, whether it has a Wikidata entry, and its area. */
    fun importance(e: ReaderElement, category: PlaceCategory, areaM2: Double = 0.0): Int {
        var score = BASE_IMPORTANCE[category] ?: 10
        if (e.hasTag("wikidata") || e.hasTag("wikipedia")) score += 25
        if (areaM2 > 1000) score += (10 * log10(areaM2 / 1000)).roundToInt()
        return score.coerceIn(0, 100)
    }

    /** `place=*` values worth searching for, with their category and importance. */
    val PLACES: Map<String, Pair<PlaceCategory, Int>> = mapOf(
        "city" to (PlaceCategory.LOCALITY to 100),
        "town" to (PlaceCategory.LOCALITY to 85),
        "suburb" to (PlaceCategory.NEIGHBOURHOOD to 70),
        "village" to (PlaceCategory.LOCALITY to 60),
        "quarter" to (PlaceCategory.NEIGHBOURHOOD to 55),
        "neighbourhood" to (PlaceCategory.NEIGHBOURHOOD to 45),
        "hamlet" to (PlaceCategory.LOCALITY to 30),
        "locality" to (PlaceCategory.NEIGHBOURHOOD to 20),
        "square" to (PlaceCategory.STREET to 40),
    )

    /** Named highways people search for; links, platforms and roads under construction are left out. */
    fun isSearchableStreet(e: ReaderElement): Boolean {
        val highway = e.getTag("highway") ?: return false
        return highway !in NOT_STREETS && !highway.endsWith("_link") && name(e) != null
    }

    /** Street importance boost by road class. */
    fun streetClassBonus(highway: String): Int = when (highway) {
        "motorway", "trunk", "primary" -> 15
        "secondary" -> 10
        "tertiary" -> 5
        else -> 0
    }

    private fun isMetro(e: ReaderElement): Boolean {
        val station = e.hasTag("railway", "station") || e.hasTag("public_transport", "station")
        return station && (e.hasTag("station", "subway") || e.hasTag("subway", "yes"))
    }

    private val INACTIVE = listOf("disused", "abandoned", "demolished", "razed", "construction", "proposed")
    private val ALT_NAME_KEYS = listOf("alt_name", "short_name", "official_name", "old_name")
    private val OTHER_KEYS = listOf("tourism", "leisure", "office", "craft", "historic")

    /** Named street furniture and parking: never a destination worth searching. */
    private val NOT_DESTINATIONS = setOf(
        "parking", "parking_entrance", "parking_space", "bicycle_parking", "motorcycle_parking", "bench", "waste_basket",
        "waste_disposal", "recycling", "vending_machine", "atm", "telephone", "post_box", "toilets", "shelter", "taxi",
        "charging_station", "car_sharing", "car_rental", "grit_bin", "clock", "loading_dock", "letter_box", "drinking_water",
    )
    private val HISTORIC = setOf(
        "monument", "memorial", "castle", "fort", "ruins", "archaeological_site", "city_gate", "aqueduct", "palace", "tower",
    )
    private val PUBLIC_SERVICES = setOf(
        "townhall", "post_office", "police", "courthouse", "embassy", "fire_station", "community_centre", "social_centre",
    )
    private val NOT_STREETS = setOf(
        "proposed", "construction", "platform", "bus_stop", "elevator", "corridor", "raceway", "abandoned", "razed", "services", "rest_area",
    )
    private val BASE_IMPORTANCE = mapOf(
        PlaceCategory.METRO_STATION to 55,
        PlaceCategory.TRAIN_STATION to 60,
        PlaceCategory.FERRY_TERMINAL to 60,
        PlaceCategory.BUS_STATION to 35,
        PlaceCategory.VIEWPOINT to 40,
        PlaceCategory.PARK to 25,
        PlaceCategory.BEACH to 35,
        PlaceCategory.MUSEUM to 40,
        PlaceCategory.MONUMENT to 25,
        PlaceCategory.ATTRACTION to 35,
        PlaceCategory.UNIVERSITY to 35,
        PlaceCategory.HOSPITAL to 40,
        PlaceCategory.MARKET to 30,
        PlaceCategory.SHOPPING to 20,
        PlaceCategory.CULTURE to 25,
        PlaceCategory.SPORTS to 20,
        PlaceCategory.LIBRARY to 20,
    )
}
