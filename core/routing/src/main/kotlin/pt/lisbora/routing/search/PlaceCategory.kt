package pt.lisbora.routing.search

/**
 * What a searchable place is. [code] is stored in search packs: never renumber or reuse one.
 * [weight] nudges the ranking (a neighbourhood or a station is more often the destination than a café).
 */
enum class PlaceCategory(val code: Int, val weight: Double) {
    STREET(0, 0.5),
    ADDRESS(1, 0.0),

    /** City, town or village. */
    LOCALITY(2, 1.2),

    /** Neighbourhood, quarter or parish. */
    NEIGHBOURHOOD(3, 1.0),

    METRO_STATION(10, 0.8),
    TRAIN_STATION(11, 0.8),
    FERRY_TERMINAL(12, 0.8),
    BUS_STATION(13, 0.4),

    /** Bike-share station (GIRA). */
    BIKE_SHARE(20, 0.3),
    BIKE_SHOP(21, 0.3),

    VIEWPOINT(30, 0.6),
    PARK(31, 0.6),
    BEACH(32, 0.5),
    MUSEUM(33, 0.6),
    MONUMENT(34, 0.5),
    ATTRACTION(35, 0.5),
    PLACE_OF_WORSHIP(36, 0.2),

    UNIVERSITY(40, 0.5),
    SCHOOL(41, 0.1),
    HOSPITAL(42, 0.5),
    HEALTH(43, 0.0),
    LIBRARY(44, 0.3),

    /** Theatre, cinema, arts centre, concert hall. */
    CULTURE(45, 0.4),
    SPORTS(46, 0.3),

    MARKET(50, 0.4),

    /** Shopping centre, department store, supermarket. */
    SHOPPING(51, 0.2),
    SHOP(52, -0.3),

    /** Restaurant, café, bar. */
    FOOD(53, -0.3),
    HOTEL(54, 0.0),

    /** Town hall, post office, police, court, embassy… */
    PUBLIC_SERVICE(55, 0.1),

    OTHER(99, -0.2);

    /** Stations and terminals: two of them with the same name (train and metro) are different destinations. */
    val isTransport: Boolean get() = this in setOf(METRO_STATION, TRAIN_STATION, FERRY_TERMINAL, BUS_STATION)

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): PlaceCategory = byCode[code] ?: OTHER
    }
}
