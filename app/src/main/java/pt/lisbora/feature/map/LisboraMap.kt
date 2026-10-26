package pt.lisbora.feature.map

import android.annotation.SuppressLint
import android.graphics.RectF
import android.view.Gravity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
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
import androidx.compose.runtime.withFrameNanos
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
import pt.lisbora.feature.planner.CameraRequest
import pt.lisbora.feature.planner.CameraTarget
import pt.lisbora.routing.geo.GeoBounds
import pt.lisbora.routing.geo.GeoPoint
import pt.lisbora.routing.geo.ServiceArea
import pt.lisbora.routing.model.CycleInfraClass
import kotlin.coroutines.resume

enum class RecenterResult { CENTERED, OUTSIDE_SERVICE_AREA, UNAVAILABLE }

private const val CAMERA_ANIMATION_MS = 700
private const val DIMMED_NETWORK_OPACITY = 0.35f
private const val CASING_OPACITY = 0.9f
private const val PADDING_SETTLE_FRAMES = 3

/**
 * The MapLibre map: offline basemap, the CML cycle network and the planned routes. Stateless towards
 * the caller — it renders [styleJson] plus the route and marker GeoJSON, and reports taps; selection,
 * visibility and camera requests live in the ViewModels.
 *
 * [contentPadding] keeps MapLibre's compass and attribution clear of the overlays drawn on top;
 * [cameraPadding] is the free area routes and places are framed in.
 * In [pickMode] a tap reports a point ([onPointPicked]) instead of selecting a segment.
 * [dimCycleNetwork] fades the network while a route is shown, so the route stands out.
 * [poiLayers] are the point-of-interest layers switched on in the legend.
 * While [followUser] (navigation) the camera follows the rider, turned to their heading; a gesture stops it
 * and a new [followRequest] starts it again.
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
    routesGeoJson: String = RouteGeoJson.EMPTY,
    markersGeoJson: String = RouteGeoJson.EMPTY,
    cameraRequest: CameraRequest? = null,
    cameraPadding: PaddingValues = contentPadding,
    pickMode: Boolean = false,
    dimCycleNetwork: Boolean = false,
    poiLayers: Set<PoiLayer> = emptySet(),
    followUser: Boolean = false,
    followRequest: Int = 0,
    onLongPress: (GeoPoint) -> Unit = {},
    onPointPicked: (GeoPoint) -> Unit = {},
    onRouteClick: (Int) -> Unit = {},
    onCameraIdle: (GeoPoint) -> Unit = {},
    onCameraRequestHandled: (Long) -> Unit = {},
) {
    val mapView = rememberMapViewWithLifecycle()
    // [lat, lon, zoom, bearing], so the view survives theme changes and rotation.
    var savedCamera by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // GeoJSON of the highlighted segment, saved so the highlight survives recreation like the card does.
    var selectedFeatureJson by rememberSaveable { mutableStateOf<String?>(null) }
    val currentOnSegmentClick by rememberUpdatedState(onSegmentClick)
    val currentPickMode by rememberUpdatedState(pickMode)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnPointPicked by rememberUpdatedState(onPointPicked)
    val currentOnRouteClick by rememberUpdatedState(onRouteClick)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val density = LocalDensity.current
    val tapSlopPx = with(density) { 12.dp.toPx() }
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
                currentOnCameraIdle(GeoPoint(target.latitude, target.longitude))
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
        m.addOnMapLongClickListener { latLng ->
            currentOnLongPress(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }
        m.addOnMapClickListener { latLng ->
            if (currentPickMode) {
                currentOnPointPicked(GeoPoint(latLng.latitude, latLng.longitude))
                return@addOnMapClickListener true
            }
            val point = m.projection.toScreenLocation(latLng)
            val area = RectF(point.x - tapSlopPx, point.y - tapSlopPx, point.x + tapSlopPx, point.y + tapSlopPx)
            // A tap on a grey alternative selects it.
            val alternative = m.queryRenderedFeatures(area, RouteLayers.ALT, RouteLayers.ALT_CASING).firstOrNull()
            if (alternative != null && alternative.hasNonNullValueForProperty(RouteLayers.INDEX_PROPERTY)) {
                currentOnRouteClick(alternative.getNumberProperty(RouteLayers.INDEX_PROPERTY).toInt())
                return@addOnMapClickListener true
            }
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

    LaunchedEffect(style, dimCycleNetwork) {
        val s = style ?: return@LaunchedEffect
        val opacity = PropertyFactory.lineOpacity(if (dimCycleNetwork) DIMMED_NETWORK_OPACITY else 1f)
        CycleNetworkLayers.classLayers.forEach { s.getLayer(it)?.setProperties(opacity) }
        s.getLayer(CycleNetworkLayers.CASING)?.setProperties(
            PropertyFactory.lineOpacity(if (dimCycleNetwork) DIMMED_NETWORK_OPACITY else CASING_OPACITY),
        )
    }

    LaunchedEffect(style, poiLayers) {
        val s = style ?: return@LaunchedEffect
        PoiLayer.entries.forEach { layer ->
            val visibility = PropertyFactory.visibility(if (layer in poiLayers) Property.VISIBLE else Property.NONE)
            s.getLayer(layer.layerId)?.setProperties(visibility)
            if (layer == PoiLayer.PLACES) s.getLayer(PoiLayer.PLACES_LABELS)?.setProperties(visibility)
        }
    }

    LaunchedEffect(style, selectedSegmentId) {
        val s = style ?: return@LaunchedEffect
        if (selectedSegmentId == null) selectedFeatureJson = null
        s.showSelection(selectedFeatureJson?.let(Feature::fromJson))
    }

    LaunchedEffect(style, routesGeoJson) {
        style?.getSourceAs<GeoJsonSource>(RouteLayers.SOURCE)?.setGeoJson(routesGeoJson)
    }

    LaunchedEffect(style, markersGeoJson) {
        style?.getSourceAs<GeoJsonSource>(RouteLayers.MARKER_SOURCE)?.setGeoJson(markersGeoJson)
    }

    val currentCameraPadding by rememberUpdatedState(cameraPadding)
    val currentOnCameraRequestHandled by rememberUpdatedState(onCameraRequestHandled)
    LaunchedEffect(map, cameraRequest?.id) {
        val m = map ?: return@LaunchedEffect
        val request = cameraRequest ?: return@LaunchedEffect
        // The panel that brought this request (e.g. the routes found) is measured in the next frames:
        // frame what it leaves free, not what the previous panel did.
        repeat(PADDING_SETTLE_FRAMES) { withFrameNanos { } }
        val padding = with(density) {
            val p = currentCameraPadding
            intArrayOf(
                p.calculateStartPadding(LayoutDirection.Ltr).roundToPx(),
                p.calculateTopPadding().roundToPx(),
                p.calculateEndPadding(LayoutDirection.Ltr).roundToPx(),
                p.calculateBottomPadding().roundToPx(),
            )
        }
        val update = when (val target = request.target) {
            is CameraTarget.Point -> CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target.point.toLatLng())
                    .zoom(target.zoom)
                    .padding(padding[0].toDouble(), padding[1].toDouble(), padding[2].toDouble(), padding[3].toDouble())
                    .build(),
            )
            is CameraTarget.Bounds -> CameraUpdateFactory.newLatLngBounds(
                target.bounds.toLatLngBounds(), padding[0], padding[1], padding[2], padding[3],
            )
        }
        m.animateCamera(update, CAMERA_ANIMATION_MS)
        currentOnCameraRequestHandled(request.id)
    }

    LaunchedEffect(style, locationEnabled) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (locationEnabled) m.enableLocation(context, s)
    }

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

    LaunchedEffect(style, locationEnabled, followUser, followRequest) {
        val m = map ?: return@LaunchedEffect
        if (style == null || !locationEnabled) return@LaunchedEffect
        val component = m.locationComponent
        if (!component.isLocationComponentActivated) return@LaunchedEffect
        if (followUser) {
            val p = currentCameraPadding
            with(density) {
                m.moveCamera(
                    CameraUpdateFactory.paddingTo(
                        p.calculateStartPadding(LayoutDirection.Ltr).roundToPx().toDouble(),
                        p.calculateTopPadding().roundToPx().toDouble(),
                        p.calculateEndPadding(LayoutDirection.Ltr).roundToPx().toDouble(),
                        p.calculateBottomPadding().roundToPx().toDouble(),
                    ),
                )
            }
            component.renderMode = RenderMode.GPS
            component.setCameraMode(CameraMode.TRACKING_GPS, CAMERA_ANIMATION_MS.toLong(), MapDefaults.NAVIGATION_ZOOM, null, null, null)
        } else {
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.COMPASS
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
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
