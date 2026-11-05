package com.karoo_intervals.engine

import com.karoo_intervals.R
import com.karoo_intervals.data.WorkoutRepository
import com.karoo_intervals.extension.consumerFlow
import com.karoo_intervals.extension.elapsedSeconds
import com.karoo_intervals.extension.streamDataFlow
import com.karoo_intervals.workout.summary
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the single [WorkoutCoordinator]. Publishes a hot [state] the ride screen
 * renders, and turns [WorkoutEvent]s into Karoo effects (lap, beeps, alert).
 * Thin wiring only — all logic lives in [WorkoutCoordinator] / [WorkoutSession].
 */
object WorkoutRunner {
    private val _state = MutableStateFlow<WorkoutState?>(null)
    val state: StateFlow<WorkoutState?> = _state

    private val _rideState = MutableStateFlow<RideState>(RideState.Idle)
    val rideState: StateFlow<RideState> = _rideState

    private val _simulated = MutableStateFlow(false)
    val simulated: StateFlow<Boolean> = _simulated

    private val commands = MutableSharedFlow<WorkoutCommand>(extraBufferCapacity = 8)
    private var karoo: KarooSystemService? = null
    private var started = false

    @Synchronized
    fun stop() {
        started = false
        karoo = null
    }

    fun command(cmd: WorkoutCommand) {
        commands.tryEmit(cmd)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun start(karooSystem: KarooSystemService, repository: WorkoutRepository, scope: CoroutineScope) {
        if (started) return
        started = true
        karoo = karooSystem

        val simulate = repository.library.map { it.simulate }.distinctUntilChanged()

        scope.launch {
            simulate.flatMapLatest { sim ->
                _simulated.value = sim
                val rideFlow = if (sim) SimulatedStreams.rideState() else karooSystem.consumerFlow<RideState>()
                WorkoutCoordinator(
                    workout = repository.selectedWorkout,
                    // ELAPSED_TIME arrives in ms (real and simulated); convert once here.
                    elapsed = (if (sim) SimulatedStreams.elapsedTime() else karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME))
                        .elapsedSeconds(),
                    power = if (sim) SimulatedStreams.power { _state.value?.targetW } else karooSystem.streamDataFlow(DataType.Type.POWER),
                    heartRate = if (sim) SimulatedStreams.heartRate() else karooSystem.streamDataFlow(DataType.Type.HEART_RATE),
                    rideState = rideFlow.onEach { _rideState.value = it },
                    commands = commands,
                ).run()
            }.collect { update ->
                _state.value = update.state
                update.events.forEach { handle(karooSystem, it, simulated = _simulated.value) }
            }
        }
    }

    /** The simulator stays silent (no beeps, no laps) — it's for checking the screen, not riding. */
    private fun handle(k: KarooSystemService, event: WorkoutEvent, simulated: Boolean) {
        when (event) {
            is WorkoutEvent.Countdown -> if (!simulated) k.dispatch(
                PlayBeepPattern(listOf(PlayBeepPattern.Tone(if (event.secondsLeft == 1) 2000 else 1500, 150))),
            )
            is WorkoutEvent.StepStarted -> {
                if (!simulated) {
                    k.dispatch(MarkLap)
                    k.dispatch(PlayBeepPattern(listOf(PlayBeepPattern.Tone(2500, 400))))
                }
                k.dispatch(
                    InRideAlert(
                        id = "step",
                        icon = R.drawable.ic_workout,
                        title = event.step.summary(),
                        detail = event.next?.let { "Next: ${it.summary()}" } ?: "Last interval",
                        autoDismissMs = 5_000,
                        backgroundColor = R.color.alert_bg,
                        textColor = R.color.alert_text,
                    ),
                )
            }
            WorkoutEvent.Finished -> {
                if (!simulated) k.dispatch(PlayBeepPattern(List(3) { PlayBeepPattern.Tone(2500, 200) }.flatMap { listOf(it, PlayBeepPattern.Tone(null, 100)) }))
                k.dispatch(
                    InRideAlert(
                        id = "done",
                        icon = R.drawable.ic_workout,
                        title = "Workout complete",
                        detail = _state.value?.workout?.name,
                        autoDismissMs = 8_000,
                        backgroundColor = R.color.alert_bg,
                        textColor = R.color.alert_text,
                    ),
                )
            }
        }
    }
}
