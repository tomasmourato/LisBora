package pt.lisbora.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import pt.lisbora.routing.eta.BikeType
import pt.lisbora.routing.eta.RiderLevel
import pt.lisbora.routing.eta.RiderProfile
import pt.lisbora.routing.weighting.RoutingPreferences
import pt.lisbora.routing.weighting.TripPurpose
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The rider's saved choices for planning routes. */
data class RidingSettings(
    val bike: BikeType = BikeType.CITY,
    val level: RiderLevel = RiderLevel.MEDIUM,
    val purpose: TripPurpose = TripPurpose.COMMUTE,
    /** Steepest climb accepted, in percent; null = no limit. */
    val maxGradePercent: Int? = null,
    val avoidCobbles: Boolean = false,
    val avoidTramTracks: Boolean = false,
    val preferSegregated: Boolean = false,
    val allowFerries: Boolean = true,
    /** The rider's own pace against the model (above 1 = faster), learnt from finished rides. */
    val personalFactor: Double = 1.0,
) {
    fun toRoutingPreferences() = RoutingPreferences(
        rider = RiderProfile(bike = bike, level = level, personalFactor = personalFactor),
        purpose = purpose,
        maxGradePercent = maxGradePercent?.toDouble(),
        avoidCobbles = avoidCobbles,
        avoidTramTracks = avoidTramTracks,
        preferSegregated = preferSegregated,
        allowFerries = allowFerries,
    )

    companion object {
        /** Choices offered for the maximum grade. */
        val MAX_GRADE_OPTIONS: List<Int?> = listOf(null, 5, 8, 10, 12)

        /** Range of the learnt pace, well inside what [RiderProfile] accepts. */
        val PERSONAL_FACTOR_RANGE = 0.6..1.6
    }
}

interface RidingSettingsRepository {
    val settings: Flow<RidingSettings>

    suspend fun update(transform: (RidingSettings) -> RidingSettings)
}

/** [RidingSettings] in Preferences DataStore. Unknown or corrupt values fall back to the defaults. */
@Singleton
class DataStoreRidingSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RidingSettingsRepository {

    override val settings: Flow<RidingSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::fromPreferences)

    override suspend fun update(transform: (RidingSettings) -> RidingSettings) {
        dataStore.edit { prefs -> write(prefs, transform(fromPreferences(prefs))) }
    }

    private fun fromPreferences(p: Preferences): RidingSettings {
        val defaults = RidingSettings()
        return RidingSettings(
            bike = p[BIKE]?.let { runCatching { BikeType.valueOf(it) }.getOrNull() } ?: defaults.bike,
            level = p[LEVEL]?.let { runCatching { RiderLevel.valueOf(it) }.getOrNull() } ?: defaults.level,
            purpose = p[PURPOSE]?.let { runCatching { TripPurpose.valueOf(it) }.getOrNull() } ?: defaults.purpose,
            maxGradePercent = p[MAX_GRADE]?.takeIf { it in RidingSettings.MAX_GRADE_OPTIONS },
            avoidCobbles = p[AVOID_COBBLES] ?: defaults.avoidCobbles,
            avoidTramTracks = p[AVOID_TRAMS] ?: defaults.avoidTramTracks,
            preferSegregated = p[PREFER_SEGREGATED] ?: defaults.preferSegregated,
            allowFerries = p[ALLOW_FERRIES] ?: defaults.allowFerries,
            personalFactor = p[PERSONAL_FACTOR]?.takeIf { it in RidingSettings.PERSONAL_FACTOR_RANGE } ?: defaults.personalFactor,
        )
    }

    private fun write(p: MutablePreferences, s: RidingSettings) {
        p[BIKE] = s.bike.name
        p[LEVEL] = s.level.name
        p[PURPOSE] = s.purpose.name
        if (s.maxGradePercent != null) p[MAX_GRADE] = s.maxGradePercent else p.remove(MAX_GRADE)
        p[AVOID_COBBLES] = s.avoidCobbles
        p[AVOID_TRAMS] = s.avoidTramTracks
        p[PREFER_SEGREGATED] = s.preferSegregated
        p[ALLOW_FERRIES] = s.allowFerries
        p[PERSONAL_FACTOR] = s.personalFactor
    }

    private companion object {
        val BIKE = stringPreferencesKey("bike")
        val LEVEL = stringPreferencesKey("level")
        val PURPOSE = stringPreferencesKey("purpose")
        val MAX_GRADE = intPreferencesKey("max_grade_percent")
        val AVOID_COBBLES = booleanPreferencesKey("avoid_cobbles")
        val AVOID_TRAMS = booleanPreferencesKey("avoid_tram_tracks")
        val PREFER_SEGREGATED = booleanPreferencesKey("prefer_segregated")
        val ALLOW_FERRIES = booleanPreferencesKey("allow_ferries")
        val PERSONAL_FACTOR = doublePreferencesKey("personal_factor")
    }
}
