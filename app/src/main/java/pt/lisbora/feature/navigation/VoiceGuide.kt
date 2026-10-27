package pt.lisbora.feature.navigation

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pt.lisbora.R
import pt.lisbora.feature.discover.label
import pt.lisbora.routing.navigation.Maneuver
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The phone's text-to-speech in European Portuguese (Brazilian when that is all it has) for the ride's
 * instructions. Other audio (music, a podcast) is ducked while it speaks. Opened for a ride, closed after.
 */
@Singleton
class VoiceGuide @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _available = MutableStateFlow<Boolean?>(null)

    /** Whether the phone can speak Portuguese; null until the engine has answered. */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTRIBUTES).build()

    private var tts: TextToSpeech? = null
    private var ready = false

    /** Said before the engine was ready. */
    private val waiting = ArrayDeque<String>()
    private var outstanding = 0
    private var nextId = 0

    fun open() {
        if (tts != null) return
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status -> onInit(engine, status) }
        tts = engine
    }

    fun say(text: String) {
        val engine = tts ?: return
        if (!ready) {
            if (waiting.size == MAX_WAITING) waiting.removeFirst()
            waiting.addLast(text)
            return
        }
        Log.d(TAG, "Saying: $text")
        if (outstanding == 0) audio?.requestAudioFocus(focus)
        outstanding++
        _speaking.value = true
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "lisbora-${nextId++}")
    }

    /** Stops talking now (muted). */
    fun silence() {
        waiting.clear()
        tts?.stop()
        done()
    }

    fun close() {
        silence()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun onInit(engine: TextToSpeech?, status: Int) {
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "No text-to-speech engine ($status)")
            _available.value = false
            return
        }
        if (tts !== engine) return
        val language = LOCALES.firstOrNull { engine.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
        if (language == null) {
            Log.w(TAG, "No Portuguese voice")
            _available.value = false
            return
        }
        engine.setAudioAttributes(ATTRIBUTES)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        ready = true
        _available.value = true
        Log.i(TAG, "Voice ready: $language")
        while (waiting.isNotEmpty()) say(waiting.removeFirst())
    }

    /** One utterance over (on the engine's thread). */
    private fun finished() {
        main.post {
            if (outstanding > 0) outstanding--
            if (outstanding == 0) done()
        }
    }

    private fun done() {
        outstanding = 0
        audio?.abandonAudioFocusRequest(focus)
        _speaking.value = false
    }

    private companion object {
        const val TAG = "LisBoraVoice"
        const val MAX_WAITING = 3
        val LOCALES = listOf(Locale.forLanguageTag("pt-PT"), Locale.forLanguageTag("pt-BR"), Locale.forLanguageTag("pt"))
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** What to say for a prompt: "Daqui a 150 metros, vire à esquerda. Rua do Alecrim." */
fun VoicePrompt.speech(res: Resources): String = when (this) {
    is VoicePrompt.Turn -> buildString {
        val roundabout = instruction.maneuver == Maneuver.ROUNDABOUT
        val what = when {
            roundabout && instruction.street != null -> res.getString(R.string.voice_roundabout_to, instruction.street)
            roundabout -> res.getString(R.string.voice_roundabout)
            else -> instruction.text(res)
        }
        if (distanceM != null) {
            append(res.getString(R.string.voice_in, spokenDistance(res, distanceM), what.lowercaseFirst())).append('.')
            if (!roundabout) instruction.street?.let { append(' ').append(it).append('.') }
        } else {
            append(what).append('.')
        }
        then?.let { append(' ').append(res.getString(R.string.nav_then, it.text(res).lowercaseFirst())).append('.') }
    }
    is VoicePrompt.Ahead -> hazard.aheadText(res, spokenDistance(res, distanceM)) + "."
    VoicePrompt.OffRoute -> res.getString(R.string.voice_off_route)
    is VoicePrompt.RerouteFailed -> res.getString(if (free) R.string.nav_free_route_failed else R.string.nav_reroute_failed)
    is VoicePrompt.GiraTime -> {
        val time = if (warning == GiraWarning.OVER || minutesLeft <= 0) {
            res.getString(R.string.voice_gira_over)
        } else {
            res.getQuantityString(R.plurals.voice_gira_left, minutesLeft, minutesLeft)
        }
        val where = station?.let { res.getString(R.string.voice_gira_swap_at, it.station.label(res), spokenDistance(res, it.distanceM)) }
            ?: res.getString(R.string.nav_gira_no_station)
        "$time $where"
    }
    is VoicePrompt.GiraAtStation -> res.getString(R.string.nav_gira_at_station, station.label(res))
    VoicePrompt.Arrived -> res.getString(R.string.voice_arrived)
}

/** "150 metros", "1 quilómetro", "1,5 quilómetros": rounded as on the screen, in words for the voice. */
fun spokenDistance(res: Resources, m: Double): String {
    val rounded = roundedDistance(m)
    if (rounded < 1000) {
        val metres = rounded.roundToInt()
        return res.getQuantityString(R.plurals.voice_metres, metres, metres)
    }
    val km = rounded / 1000
    return if (km % 1.0 == 0.0) {
        res.getQuantityString(R.plurals.voice_km, km.toInt(), km.toInt())
    } else {
        res.getString(R.string.voice_km_decimal, String.format(Locale.forLanguageTag("pt-PT"), "%.1f", km))
    }
}

private fun String.lowercaseFirst() = replaceFirstChar { it.lowercase() }
