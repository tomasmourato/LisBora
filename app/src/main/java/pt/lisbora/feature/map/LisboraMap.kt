package pt.lisbora.feature.map

import android.annotation.SuppressLint
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.geo.ServiceArea
import pt.lisbora.routing.model.CycleInfraClass
import kotlin.coroutines.resume

enum class RecenterResult { CENTERED, OUTSIDE_SERVICE_AREA, UNAVAILABLE }

/**
 * The MapLibre map: offline basemap plus the CML cycle network. Stateless towards the caller — it
 * renders [styleJson] and reports taps; selection and visibility live in the ViewModel.
 *
 * [contentPadding] keeps MapLibre's compass and attribution clear of the overlays drawn on top.
 */
@Composable
fun LisboraMap(
    styleJson: String,
    cycleNetworkVisible: Boolean,
    selectedSegmentId: String?,
    locationEnabled: Boolean,
    recenterRequest: Long,
    contentPadding: PaddingValues,
    onSegmentClick: (CycleSegment?) -> Unit,
    onRecenterResult: (RecenterResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    // [lat, lon, zoom, bearing], so the view survives theme changes and rotation.
    var savedCamera by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // GeoJSON of the highlighted segment, saved so the highlight survives recreation like the card does.
    var selectedFeatureJson by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnSegmentClick by rememberUpdatedState(onSegmentClick)
    val tapSlopPx = with(LocalDensity.current) { 12.dp.toPx() }
    val context = LocalContext.current

    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(mapView) {
        val m = mapView.awaitMap()
        val camera = savedCamera
        m.cameraPosition = CameraPosition.Builder()
            .target(camera?.let { LatLng(it[0], it[1]) } ?: MapDefaults.LisbonCenter.toLatLng())
            .zoom(camera?.get(2) ?: MapDefaults.INITIAL_ZOOM)
            .bearing(camera?.get(3) ?: 0.0)
            .build()
        m.addOnCameraIdleListener {
            m.cameraPosition.let { p ->
                val target = p.target ?: return@let
                savedCamera = doubleArrayOf(target.latitude, target.longitude, p.zoom, p.bearing)
            }
        }
        m.setLatLngBoundsForCameraTarget(ServiceArea.BOUNDS.toLatLngBounds())
        m.setMinZoomPreference(MapDefaults.MIN_ZOOM)
        m.setMaxZoomPreference(MapDefaults.MAX_ZOOM)
        m.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = true
            isCompassEnabled = true
            isTiltGesturesEnabled = false
        }
        m.addOnMapClickListener { latLng ->
            val point = m.projection.toScreenLocation(latLng)
            val area = RectF(point.x - tapSlopPx, point.y - tapSlopPx, point.x + tapSlopPx, point.y + tapSlopPx)
            val feature = m.queryRenderedFeatures(area, *CycleNetworkLayers.classLayers.toTypedArray()).firstOrNull()
            selectedFeatureJson = feature?.toJson()
            m.style?.showSelection(feature)
            currentOnSegmentClick(feature?.toCycleSegment())
            feature != null
        }
        map = m
    }

    LaunchedEffect(map, styleJson) {
        val m = map ?: return@LaunchedEffect
        style = null
        style = m.awaitStyle(Style.Builder().fromJson(styleJson))
    }

    LaunchedEffect(style, cycleNetworkVisible) {
        val s = style ?: return@LaunchedEffect
        val visibility = PropertyFactory.visibility(if (cycleNetworkVisible) Property.VISIBLE else Property.NONE)
        CycleNetworkLayers.all.forEach { s.getLayer(it)?.setProperties(visibility) }
    }

    LaunchedEffect(style, selectedSegmentId) {
        val s = style ?: return@LaunchedEffect
        if (selectedSegmentId == null) selectedFeatureJson = null
        s.showSelection(selectedFeatureJson?.let(Feature::fromJson))
    }

    LaunchedEffect(style, locationEnabled) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (locationEnabled) m.enableLocation(context, s)
    }

    val density = LocalDensity.current
    LaunchedEffect(map, contentPadding) {
        val m = map ?: return@LaunchedEffect
        with(density) {
            val start = contentPadding.calculateStartPadding(LayoutDirection.Ltr).roundToPx()
            val top = contentPadding.calculateTopPadding().roundToPx()
            val bottom = contentPadding.calculateBottomPadding().roundToPx()
            m.uiSettings.compassGravity = Gravity.TOP or Gravity.START
            m.uiSettings.setCompassMargins(start, top, 0, 0)
            m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
            m.uiSettings.setAttributionMargins(start, 0, 0, bottom)
        }
    }

    val currentOnRecenterResult by rememberUpdatedState(onRecenterResult)
    LaunchedEffect(recenterRequest) {
        if (recenterRequest == 0L) return@LaunchedEffect
        val result = map?.recenterOnUser() ?: RecenterResult.UNAVAILABLE
        currentOnRecenterResult(result)
    }
}

@SuppressLint("MissingPermission") // Only called when the ViewModel knows the permission was granted.
private fun MapLibreMap.enableLocation(context: android.content.Context, style: Style) {
    val component = locationComponent
    if (!component.isLocationComponentActivated) {
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).useDefaultLocationEngine(true).build(),
        )
    }
    component.isLocationComponentEnabled = true
    component.renderMode = RenderMode.COMPASS
    component.cameraMode = CameraMode.NONE
}

/**
 * Waits briefly for the location component (enabled by another effect, possibly in the same frame)
 * and for a fix, then flies to it if it is inside the area LisBora covers.
 */
private suspend fun MapLibreMap.recenterOnUser(): RecenterResult {
    val component = locationComponent
    var fix: android.location.Location? = null
    for (attempt in 1..10) {
        fix = component.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
        if (fix != null) break
        delay(500)
    }
    if (fix == null) return RecenterResult.UNAVAILABLE
    val point = GeoPoint(fix.latitude, fix.longitude)
    if (point !in ServiceArea.BOUNDS) return RecenterResult.OUTSIDE_SERVICE_AREA
    animateCamera(CameraUpdateFactory.newLatLngZoom(point.toLatLng(), MapDefaults.LOCATION_ZOOM))
    return RecenterResult.CENTERED
}

private fun Style.showSelection(feature: Feature?) {
    val source = getSourceAs<GeoJsonSource>(CycleNetworkLayers.SELECTION_SOURCE) ?: return
    if (feature != null) source.setGeoJson(feature) else source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
}

private fun Feature.toCycleSegment(): CycleSegment? {
    fun text(key: String) = if (hasNonNullValueForProperty(key)) getStringProperty(key) else null
    val infraClass = text("class")?.let(CycleInfraClass::fromId) ?: return null
    return CycleSegment(
        id = text("id") ?: return null,
        infraClass = infraClass,
        name = text("name"),
        typology = text("typology"),
        segregation = text("segregation"),
        parish = text("parish"),
        year = text("year"),
        lengthM = if (hasNonNullValueForProperty("length_m")) getNumberProperty("length_m").toDouble() else null,
        contraflow = hasNonNullValueForProperty("contraflow") && getBooleanProperty("contraflow"),
    )
}

private fun GeoPoint.toLatLng() = LatLng(lat, lon)

private fun GeoBounds.toLatLngBounds(): LatLngBounds = LatLngBounds.from(north, east, south, west)

private suspend fun MapView.awaitMap(): MapLibreMap = suspendCancellableCoroutine { cont ->
    getMapAsync { cont.resume(it) }
}

private suspend fun MapLibreMap.awaitStyle(builder: Style.Builder): Style = suspendCancellableCoroutine { cont ->
    setStyle(builder) { cont.resume(it) }
}

/** A MapView whose lifecycle follows the composition's lifecycle owner. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
