package pt.lisbora.routing.model

/**
 * Cycling infrastructure classes used across the map, the router and the pipeline.
 * [id] is the stable value stored in data packs (GeoJSON `class` property); never rename it.
 */
enum class CycleInfraClass(val id: String) {
    /** Physically separated cycle track (CML "Pista Ciclável", physically separated contraflow). */
    SEGREGATED_TRACK("segregated"),

    /** Painted cycle lane, visually separated (CML "Faixa Ciclável", painted contraflow). */
    PAINTED_LANE("lane"),

    /** Shared street with 30 km/h traffic (CML "30+Bici", "Zona de Coexistência"). */
    SHARED_STREET_30("shared30"),

    /** Path shared with pedestrians (CML "Percurso Ciclopedonal"). */
    SHARED_WITH_PEDESTRIANS("shared_ped"),

    /** Trail, usually unpaved (CML "Trilho", e.g. Monsanto). */
    TRAIL("trail");

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): CycleInfraClass? = byId[id]
    }
}
