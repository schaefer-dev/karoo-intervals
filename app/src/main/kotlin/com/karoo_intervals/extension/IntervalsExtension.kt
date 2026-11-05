package com.karoo_intervals.extension

import com.karoo_intervals.data.WorkoutRepository
import com.karoo_intervals.engine.WorkoutRunner
import com.karoo_intervals.intervals.IntervalsApi
import com.karoo_intervals.intervals.SyncOutcome
import com.karoo_intervals.screen.WorkoutScreenDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

class IntervalsExtension : KarooExtension(EXTENSION_ID, "0.1.0") {
    private lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types: List<DataTypeImpl> by lazy {
        listOf(WorkoutScreenDataType(karooSystem, EXTENSION_ID))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        val repo = WorkoutRepository.get(applicationContext)
        karooSystem.connect()
        WorkoutRunner.start(karooSystem, repo, scope)
        // Refresh the cache in the background when it's older than an hour.
        scope.launch {
            val last = repo.library.first().lastSyncedAt ?: 0L
            if (System.currentTimeMillis() - last < 60 * 60_000L) return@launch
            val outcome = withTimeoutOrNull(60_000) {
                IntervalsApi.sync(repo.apiKey(), LocalDate.now(), { karooSystem.http(it) })
            }
            if (outcome is SyncOutcome.Ok) repo.saveSync(outcome.payload, System.currentTimeMillis())
        }
    }

    override fun onDestroy() {
        WorkoutRunner.stop()
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    companion object {
        const val EXTENSION_ID = "karoo_intervals"
    }
}
