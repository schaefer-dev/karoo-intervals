package com.karoo_intervals.engine

import com.karoo_intervals.workout.Workout
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

enum class WorkoutCommand { SKIP, RESTART }

data class WorkoutUpdate(val state: WorkoutState?, val events: List<WorkoutEvent> = emptyList())

/**
 * Pure-flow coordination (no Android): merges every input into one sequential stream so the
 * [WorkoutSession] is only ever touched from a single collector.
 *
 * - [elapsed] must already be in **seconds** (apply `elapsedSeconds()` at the adapter boundary).
 * - A tick only advances the workout while the ride is [RideState.Recording].
 * - [RideState.Idle] (ride ended / not started) resets the session, so the next ride starts the
 *   workout from step 1. A different selected workout also starts a fresh session.
 */
class WorkoutCoordinator(
    private val workout: Flow<Workout?>,
    private val elapsed: Flow<StreamState>,
    private val power: Flow<StreamState>,
    private val heartRate: Flow<StreamState>,
    private val rideState: Flow<RideState>,
    private val commands: Flow<WorkoutCommand>,
) {
    private sealed interface Input {
        data class W(val workout: Workout?) : Input
        data class Elapsed(val sec: Double?) : Input
        data class Power(val w: Double?) : Input
        data class Hr(val bpm: Double?) : Input
        data class Ride(val state: RideState) : Input
        data class Cmd(val cmd: WorkoutCommand) : Input
    }

    fun run(): Flow<WorkoutUpdate> = flow {
        var current: Workout? = null
        var session: WorkoutSession? = null
        var ride: RideState = RideState.Idle
        var lastPower: Double? = null
        var lastHr: Int? = null

        fun snapshot(events: List<WorkoutEvent> = emptyList()) =
            WorkoutUpdate(session?.state(lastHr), events)

        merge(
            workout.map { Input.W(it) },
            elapsed.map { Input.Elapsed(it.single()) },
            power.map { Input.Power(it.single()) },
            heartRate.map { Input.Hr(it.single()) },
            rideState.map { Input.Ride(it) },
            commands.map { Input.Cmd(it) },
        ).collect { input ->
            when (input) {
                is Input.W -> {
                    if (input.workout?.id != current?.id || input.workout?.steps != current?.steps) {
                        current = input.workout
                        session = input.workout?.let { WorkoutSession(it) }
                        emit(snapshot())
                    }
                }
                is Input.Power -> lastPower = input.w
                is Input.Hr -> lastHr = input.bpm?.toInt()
                is Input.Ride -> {
                    val wasActive = ride !is RideState.Idle
                    ride = input.state
                    if (input.state is RideState.Idle && wasActive) {
                        session = current?.let { WorkoutSession(it) }
                        emit(snapshot())
                    }
                }
                is Input.Elapsed -> {
                    val s = session
                    val sec = input.sec
                    if (s != null && sec != null && ride is RideState.Recording) {
                        emit(snapshot(s.tick(sec, lastPower)))
                    }
                }
                is Input.Cmd -> when (input.cmd) {
                    WorkoutCommand.SKIP -> session?.let { emit(snapshot(it.skip())) }
                    WorkoutCommand.RESTART -> {
                        session = current?.let { WorkoutSession(it) }
                        emit(snapshot())
                    }
                }
            }
        }
    }

    private fun StreamState.single(): Double? =
        (this as? StreamState.Streaming)?.dataPoint?.singleValue
}
