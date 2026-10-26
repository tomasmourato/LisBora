package pt.lisbora.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import pt.lisbora.routing.geo.GeoPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

interface LocationRepository {
    /** Whether the user granted location access. */
    fun hasPermission(): Boolean

    /** A fresh position (waiting a few seconds at most), else a recent last known one; null when unavailable. */
    suspend fun currentLocation(): GeoPoint?
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
