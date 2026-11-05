package com.karoo_intervals.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.karoo_intervals.BuildConfig
import com.karoo_intervals.intervals.SyncPayload
import com.karoo_intervals.workout.PowerSettings
import com.karoo_intervals.workout.Workout
import com.karoo_intervals.workout.WorkoutParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "workouts")

/** Parsed, display-ready view of the cache. */
data class WorkoutLibrary(
    /** Planned calendar workouts (yesterday … +7 days). */
    val planned: List<Workout>,
    val power: PowerSettings?,
    val selected: Workout?,
    val lastSyncedAt: Long?,
    val apiKeyConfigured: Boolean,
    val simulate: Boolean,
)

/**
 * Cache of the last intervals.icu sync plus user choices.
 *
 * The raw API bodies live as files in `filesDir/sync/` and are
 * parsed once into memory; small values (selection, API key, last sync, simulator flag) live in
 * DataStore. Process singleton ([get]) shared by the extension service and the activity.
 */
class WorkoutRepository private constructor(context: Context) {
    private val store = context.applicationContext.dataStore
    private val dir = File(context.applicationContext.filesDir, "sync").apply { mkdirs() }

    private data class Parsed(val planned: List<Workout>, val power: PowerSettings?)

    private val parsed = MutableStateFlow<Parsed?>(null)

    val library: Flow<WorkoutLibrary> = combine(store.data, parsedFlow()) { p, data ->
        WorkoutLibrary(
            planned = data.planned,
            power = data.power,
            selected = p[KEY_SELECTED]?.let { id -> data.planned.firstOrNull { it.id == id } },
            lastSyncedAt = p[KEY_LAST_SYNC],
            apiKeyConfigured = apiKey(p).isNotBlank(),
            simulate = BuildConfig.DEBUG && p[KEY_SIMULATE] == true,
        )
    }.distinctUntilChanged()

    val selectedWorkout: Flow<Workout?> = library.map { it.selected }.distinctUntilChanged()

    private fun parsedFlow(): Flow<Parsed> = flow {
        if (parsed.value == null) {
            val p = withContext(Dispatchers.IO) { parseFiles() }
            parsed.compareAndSet(null, p)
        }
        emitAll(parsed.filterNotNull())
    }

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()

    private fun parseFiles(): Parsed {
        val athlete = read(SETTINGS)?.let { runCatching { WorkoutParser.parseSportSettings(it) }.getOrNull() }
            ?: return Parsed(emptyList(), null)
        val planned = read(EVENTS)?.let {
            runCatching { WorkoutParser.parseEvents(it, athlete.power, athlete.lthr) }.getOrNull()
        }.orEmpty()
        return Parsed(planned, athlete.power)
    }

    suspend fun apiKey(): String = apiKey(store.data.first())

    private fun apiKey(p: Preferences): String =
        p[KEY_API_KEY]?.takeIf { it.isNotBlank() } ?: BuildConfig.INTERVALS_API_KEY

    suspend fun saveSync(payload: SyncPayload, now: Long) {
        withContext(Dispatchers.IO) {
            File(dir, SETTINGS).writeText(payload.sportSettingsJson)
            File(dir, EVENTS).writeText(payload.eventsJson)
            parsed.value = parseFiles()
        }
        store.edit { it[KEY_LAST_SYNC] = now }
    }

    suspend fun select(workoutId: String?) {
        store.edit { if (workoutId == null) it.remove(KEY_SELECTED) else it[KEY_SELECTED] = workoutId }
    }

    suspend fun setApiKey(key: String) {
        store.edit { it[KEY_API_KEY] = key.trim() }
    }

    suspend fun setSimulate(on: Boolean) {
        store.edit { it[KEY_SIMULATE] = on }
    }

    companion object {
        private const val SETTINGS = "sport_settings.json"
        private const val EVENTS = "events.json"
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
        private val KEY_SELECTED = stringPreferencesKey("selected_workout")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_SIMULATE = booleanPreferencesKey("simulate")

        @Volatile private var instance: WorkoutRepository? = null
        fun get(context: Context): WorkoutRepository =
            instance ?: synchronized(this) { instance ?: WorkoutRepository(context).also { instance = it } }
    }
}
