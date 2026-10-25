package pt.lisbora.feature.map

import androidx.compose.ui.graphics.Color
import pt.lisbora.routing.model.CycleInfraClass

/**
 * Colours of the cycle network, shared by the map layers and the Compose legend so both always match.
 * Segregated tracks are the strongest colour; painted lanes and trails are dashed. The selection
 * halo is violet, a hue no infrastructure class uses.
 */
data class CycleNetworkPalette(
    val classes: Map<CycleInfraClass, ClassStyle>,
    val casing: String,
    val selection: String,
) {
    data class ClassStyle(val color: String, val dashed: Boolean)

    fun style(infraClass: CycleInfraClass): ClassStyle = classes.getValue(infraClass)

    fun composeColor(infraClass: CycleInfraClass): Color = hexToColor(style(infraClass).color)

    companion object {
        val Light = CycleNetworkPalette(
            classes = mapOf(
                CycleInfraClass.SEGREGATED_TRACK to ClassStyle("#0B7A4B", dashed = false),
                CycleInfraClass.PAINTED_LANE to ClassStyle("#2E9D5B", dashed = true),
                CycleInfraClass.SHARED_STREET_30 to ClassStyle("#D98E04", dashed = false),
                CycleInfraClass.SHARED_WITH_PEDESTRIANS to ClassStyle("#2B6CB0", dashed = false),
                CycleInfraClass.TRAIL to ClassStyle("#8B5A2B", dashed = true),
            ),
            casing = "#FFFFFF",
            selection = "#7C3AED",
        )

        val Dark = CycleNetworkPalette(
            classes = mapOf(
                CycleInfraClass.SEGREGATED_TRACK to ClassStyle("#3DDC84", dashed = false),
                CycleInfraClass.PAINTED_LANE to ClassStyle("#7BD88F", dashed = true),
                CycleInfraClass.SHARED_STREET_30 to ClassStyle("#FFB938", dashed = false),
                CycleInfraClass.SHARED_WITH_PEDESTRIANS to ClassStyle("#6CA8F0", dashed = false),
                CycleInfraClass.TRAIL to ClassStyle("#C99A6B", dashed = true),
            ),
            casing = "#101412",
            selection = "#C4B5FD",
        )

        private fun hexToColor(hex: String): Color = Color(("FF" + hex.removePrefix("#")).toLong(16))
    }
}
