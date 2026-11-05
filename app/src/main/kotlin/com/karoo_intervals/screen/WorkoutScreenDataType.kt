package com.karoo_intervals.screen

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.karoo_intervals.R
import com.karoo_intervals.engine.WorkoutRunner
import com.karoo_intervals.engine.WorkoutState
import com.karoo_intervals.extension.streamDataFlow
import com.karoo_intervals.ui.WorkoutsActivity
import com.karoo_intervals.workout.Step
import com.karoo_intervals.workout.Workout
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * The full-page workout screen. Meant to be the only field on its own profile page (60×60).
 * Thin adapter: reads [WorkoutRunner.state], draws via [WorkoutScreenRenderer], wires tap zones.
 */
class WorkoutScreenDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private val pool = arrayOfNulls<Bitmap>(2)
    private var toggle = 0

    override fun startStream(emitter: Emitter<StreamState>) {
        // Graphic-only field; no numeric stream.
        emitter.onNext(StreamState.NotAvailable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO)
        android.util.Log.i("IntervalsScreen", "startView grid=${config.gridSize} view=${config.viewSize} preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        scope.launch {
            if (config.preview) {
                emitter.updateView(build(context, config, previewInput(), taps = null, pooled = false))
                return@launch
            }
            // PendingIntent lookups are IPC to system_server: create them once per view, not per frame.
            val taps = Taps(context)
            // Live power/HR are only drawn when no workout is loaded (the session carries its own
            // values otherwise), so only subscribe to them then. Otherwise their unsynchronised
            // ~1 Hz updates would trigger extra, pixel-identical full-screen frames.
            val live = WorkoutRunner.state.map { it == null }.distinctUntilChanged().flatMapLatest { idle ->
                if (idle) {
                    val power = karooSystem.streamDataFlow(DataType.Type.SMOOTHED_3S_AVERAGE_POWER).value().onStart { emit(null) }
                    val hr = karooSystem.streamDataFlow(DataType.Type.HEART_RATE).value().onStart { emit(null) }
                    combine(power, hr) { p, h -> p to h }
                } else {
                    flowOf(null to null)
                }
            }
            combine(WorkoutRunner.state, WorkoutRunner.simulated, live) { st, sim, (p, h) ->
                WorkoutScreenRenderer.Input(
                    state = st,
                    livePower = p,
                    liveHr = h,
                    paused = st?.paused == true,
                    simulated = sim,
                )
            }.distinctUntilChanged().conflate().collect { input ->
                emitter.updateView(build(context, config, input, taps, pooled = true))
                delay(MIN_FRAME_MS)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun kotlinx.coroutines.flow.Flow<StreamState>.value() =
        map { (it as? StreamState.Streaming)?.dataPoint?.singleValue?.toInt() }

    private fun build(
        context: Context,
        config: ViewConfig,
        input: WorkoutScreenRenderer.Input,
        taps: Taps?,
        pooled: Boolean,
    ): RemoteViews {
        val (w, h) = config.viewSize
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Alternate between two bitmaps so the one the host is still drawing isn't overwritten.
        val reuse = if (pooled) { toggle = toggle xor 1; pool[toggle] } else null
        val bmp = WorkoutScreenRenderer.render(input, w, h, dark, reuse)
        if (pooled) pool[toggle] = bmp

        val rv = RemoteViews(context.packageName, R.layout.field_workout)
        rv.setImageViewBitmap(R.id.screen, bmp)
        if (taps != null) {
            rv.setOnClickPendingIntent(R.id.tap_settings, taps.open)
            rv.setOnClickPendingIntent(R.id.tap_pause, taps.pause)
            rv.setOnClickPendingIntent(R.id.tap_skip, taps.skip)
            // Without a workout, the whole body opens the picker.
            rv.setOnClickPendingIntent(R.id.tap_body, if (input.state == null) taps.open else taps.none)
        }
        return rv
    }

    /** The screen's tap targets, resolved once per view. */
    private class Taps(context: Context) {
        val open: PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, WorkoutsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = broadcast(context, TapReceiver.ACTION_PAUSE)
        val skip = broadcast(context, TapReceiver.ACTION_SKIP)
        val none = broadcast(context, TapReceiver.ACTION_NONE)

        private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(action).setComponent(ComponentName(context, TapReceiver::class.java)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun previewInput(): WorkoutScreenRenderer.Input {
        val steps = buildList {
            add(Step(600, "Warm up", 130, 180, ramp = true, zone = 2))
            repeat(3) {
                add(Step(300, "Hard", 260, 270, zone = 4))
                add(Step(180, "Easy", 140, 150, zone = 2))
            }
            add(Step(600, "Cool down", 120, 130, zone = 1))
        }
        val w = Workout("preview", "Preview workout", null, steps)
        return WorkoutScreenRenderer.Input(
            state = WorkoutState(w, 1, 120.0, 720.0, 265, 258, 294, 161, started = true, finished = false),
            livePower = 294, liveHr = 161, paused = false, simulated = false,
        )
    }

    companion object {
        const val TYPE_ID = "workout_screen"
        private const val MIN_FRAME_MS = 250L
    }
}
