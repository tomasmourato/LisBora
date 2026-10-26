package pt.lisbora.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import pt.lisbora.routing.geo.GeoPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A position fix while following a route. [bearing] and [speedMps] are known when moving. */
data class LocationFix(
    val point: GeoPoint,
    val timeMs: Long,
    val accuracyM: Double? = null,
    val bearing: Double? = null,
    val speedMps: Double? = null,
    val altitudeM: Double? = null,
)

interface LocationRepository {
    /** Whether the user granted location access. */
    fun hasPermission(): Boolean

    /** A fresh position (waiting a few seconds at most), else a recent last known one; null when unavailable. */
    suspend fun currentLocation(): GeoPoint?

    /** Position fixes about every [intervalMs] while collected (navigation); nothing without permission. */
    fun updates(intervalMs: Long = UPDATE_INTERVAL_MS): Flow<LocationFix>

    companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}

/** Device location through the platform [LocationManager]; it never leaves the phone. */
@Singleton
class AndroidLocationRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationRepository {

    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission") // Checked by hasPermission().
    override suspend fun currentLocation(): GeoPoint? {
        val manager = manager ?: return null
        if (!hasPermission()) return null
        val providers = PROVIDERS.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        lastKnown(manager, providers, RECENT_AGE_MS)?.let { return it }
        for (provider in providers.take(2)) {
            val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) { currentFrom(manager, provider) }
            if (fresh != null) return GeoPoint(fresh.latitude, fresh.longitude)
        }
        return lastKnown(manager, providers, MAX_LAST_KNOWN_AGE_MS)
    }

    @SuppressLint("MissingPermission") // Checked by hasPermission().
    override fun updates(intervalMs: Long): Flow<LocationFix> {
        val manager = manager ?: return emptyFlow()
        if (!hasPermission()) return emptyFlow()
        // GPS for navigation; the fused or network provider only where GPS is off.
        val provider = PROVIDERS.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return emptyFlow()
        return callbackFlow {
            val listener = LocationListenerCompat { location ->
                trySend(
                    LocationFix(
                        point = GeoPoint(location.latitude, location.longitude),
                        timeMs = location.time,
                        accuracyM = location.accuracy.toDouble().takeIf { location.hasAccuracy() },
                        bearing = location.bearing.toDouble().takeIf { location.hasBearing() },
                        speedMps = location.speed.toDouble().takeIf { location.hasSpeed() },
                        altitudeM = location.altitude.takeIf { location.hasAltitude() },
                    ),
                )
            }
            val request = LocationRequestCompat.Builder(intervalMs)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build()
            LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), listener)
            awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, providers: List<String>, maxAgeMs: Long): GeoPoint? {
        val now = System.currentTimeMillis()
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.time < maxAgeMs }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFrom(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }

    companion object {
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)
        private const val FRESH_TIMEOUT_MS = 4_000L

        /** A last known fix this recent is used straight away. */
        private const val RECENT_AGE_MS = 2 * 60_000L
        private const val MAX_LAST_KNOWN_AGE_MS = 10 * 60_000L
    }
}
