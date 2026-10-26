package pt.lisbora.feature.navigation

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pt.lisbora.R
import pt.lisbora.data.settings.RidingSettingsRepository
import pt.lisbora.feature.discover.label
import pt.lisbora.feature.map.formatLength
import pt.lisbora.feature.planner.formatDuration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Keeps a ride going with the screen off or the app in the background: a foreground service (location)
 * while the [NavigationSession] is active, with the next instruction in its notification (and in the
 * status bar, where the phone shows live updates) and the voice prompts. It stops when the ride ends,
 * after saying so.
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject lateinit var session: NavigationSession

    @Inject lateinit var voice: VoiceGuide

    @Inject lateinit var settings: RidingSettingsRepository

    private val scope = MainScope()
    private var job: Job? = null
    private var stopJob: Job? = null
    private var voiceOn = false
    private var shown: Content? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            session.onStop()
            // An old notification, with no ride to end.
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val content = content(session.uiState.value)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(content), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // No location permission, or started from the background: the ride goes on while the screen is on.
            Log.w(TAG, "Could not keep the ride in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        shown = content
        voice.open()
        scope.launch {
            settings.settings.map { it.voiceGuidance }.distinctUntilChanged().collect { on ->
                voiceOn = on
                if (!on) voice.silence()
            }
        }
        val prompts = VoicePrompts()
        job = scope.launch {
            session.uiState.collect { state ->
                val said = prompts.next(state)
                if (voiceOn) said.forEach { voice.say(it.speech(resources)) }
                if (state.active) {
                    stopJob?.cancel()
                    stopJob = null
                    update(content(state))
                } else if (stopJob == null) {
                    stopJob = scope.launch { stopAfterSpeaking() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        voice.close()
        super.onDestroy()
    }

    private suspend fun stopAfterSpeaking() {
        withTimeoutOrNull(LAST_WORDS_MS) { voice.speaking.first { !it } }
        if (session.uiState.value.active) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- the notification -----------------------------------------------------------------------

    /** [title]: the next instruction ("150 m · Vire à esquerda"); [chip]: its distance, for the status bar. */
    private data class Content(val title: String, val lines: List<String>, val chip: String?)

    private fun content(state: NavigationUiState): Content {
        val res = resources
        val g = state.gira
        val lines = buildList {
            if (g?.warning != null) {
                val left = ceil(g.leftS / 60).toInt()
                add(if (left <= 0) getString(R.string.nav_gira_over) else res.getQuantityString(R.plurals.nav_gira_left, left, left))
            }
            state.next?.street?.takeIf { state.isGuiding() }?.let(::add)
            val remainingS = state.progress?.remainingS ?: state.route?.durationS ?: 0.0
            val remainingM = state.progress?.remainingM ?: state.route?.distanceM ?: 0.0
            val arrival = LocalTime.now().plusSeconds(remainingS.toLong()).format(CLOCK)
            add(formatDuration(res, remainingS) + " · " + getString(R.string.nav_remaining, formatLength(remainingM), arrival))
        }
        val next = state.next
        return when {
            g != null && !g.started -> Content(getString(R.string.nav_gira_walk_to, g.trip.pickUp.label(res), formatLength(g.trip.walkToPickUpM)), lines, null)
            state.waitingForGps -> Content(getString(R.string.nav_waiting_gps), lines, null)
            state.rerouting -> Content(getString(R.string.nav_rerouting), lines, null)
            next == null -> Content(getString(R.string.nav_notification_riding), lines, null)
            else -> {
                val distance = formatLength(roundedDistance(state.distanceToNextM ?: 0.0))
                Content("$distance · ${next.text(res)}", lines, distance)
            }
        }
    }

    private fun NavigationUiState.isGuiding() = !waitingForGps && !rerouting && (gira == null || gira.started)

    private fun update(content: Content) {
        if (content == shown) return
        shown = content
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(content))
    }

    private fun notification(content: Content): Notification {
        ensureChannel()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bike)
            .setContentTitle(content.title)
            .setContentText(content.lines.firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.lines.joinToString("\n")))
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.nav_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            // The next turn on the lock screen, as on a bike computer.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)
            .apply { content.chip?.let(::setShortCriticalText) }
            .build()
    }

    private fun ensureChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.nav_channel))
                .setDescription(getString(R.string.nav_channel_description))
                .setShowBadge(false)
                .build(),
        )
    }

    companion object {
        private const val TAG = "LisBoraNav"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "pt.lisbora.action.STOP_NAVIGATION"

        /** Time for "Chegaste ao destino" before the service goes. */
        private const val LAST_WORDS_MS = 8_000L
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Called when a ride starts, with the app on screen (a location service cannot start from the background). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start the ride's service", e)
            }
        }
    }
}
