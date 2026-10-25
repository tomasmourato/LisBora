package pt.lisbora.feature.map

import pt.lisbora.routing.model.CycleInfraClass

/** One CML cycle-network segment, as shown in the info card when the user taps it on the map. */
data class CycleSegment(
    val id: String,
    val infraClass: CycleInfraClass,
    val name: String?,
    val typology: String?,
    val segregation: String?,
    val parish: String?,
    val year: String?,
    val lengthM: Double?,
    val contraflow: Boolean,
)
