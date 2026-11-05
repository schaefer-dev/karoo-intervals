package com.karoo_intervals.engine

import com.karoo_intervals.workout.Step
import com.karoo_intervals.workout.Workout
import kotlin.math.roundToInt

/** Everything the ride screen renders. */
data class WorkoutState(
    val workout: Workout,
    val stepIndex: Int,
    val stepElapsedSec: Double,
    val totalElapsedSec: Double,
    val targetW: Int?,
    val lapAvgW: Int?,
    val power3s: Int?,
    val heartRate: Int?,
    val started: Boolean,
    val finished: Boolean,
) {
    val step: Step get() = workout.steps[stepIndex]
    val nextStep: Step? get() = workout.steps.getOrNull(stepIndex + 1)
    val stepCount: Int get() = workout.steps.size
    val stepRemainingSec: Int get() =
        if (finished) 0 else (step.durationSec - stepElapsedSec).coerceAtLeast(0.0).let { kotlin.math.ceil(it).toInt() }
    val stepProgress: Float get() =
        if (finished) 1f else (stepElapsedSec / step.durationSec).toFloat().coerceIn(0f, 1f)
    val totalProgress: Float get() =
        (totalElapsedSec / workout.totalSec).toFloat().coerceIn(0f, 1f)
}

sealed interface WorkoutEvent {
    /** A step began ([index] > 0 – the first step starts silently with the workout). */
    data class StepStarted(val index: Int, val step: Step, val next: Step?) : WorkoutEvent
    /** [secondsLeft] (3, 2, 1) until the current step ends. */
    data class Countdown(val secondsLeft: Int) : WorkoutEvent
    data object Finished : WorkoutEvent
}

/**
 * Pure workout state machine driven by ride time (ELAPSED_TIME in **seconds**, which auto-pauses).
 *
 * The workout clock starts at the first [tick] and advances only by elapsed-time deltas, so a
 * paused ride pauses the workout. [skip] ends the current step immediately.
 */
class WorkoutSession(val workout: Workout) {
    var stepIndex = 0
        private set
    private var stepStart: Double? = null // ride elapsed when the current step began
    private var workoutStart: Double? = null
    private var lastElapsed: Double? = null
    private var finished = false
    private var lapEnergy = 0.0 // W·s within the current step
    private var lapTime = 0.0
    private val recentPower = ArrayDeque<Double>()

    val isStarted: Boolean get() = workoutStart != null
    val isFinished: Boolean get() = finished

    /** One ride tick. [elapsed] in seconds; [power] in watts (null = no sensor). */
    fun tick(elapsed: Double, power: Double?): List<WorkoutEvent> {
        val events = mutableListOf<WorkoutEvent>()
        val prev = lastElapsed
        lastElapsed = elapsed
        if (power != null) {
            recentPower.addLast(power)
            while (recentPower.size > 3) recentPower.removeFirst()
        }
        if (finished) return events
        if (workoutStart == null) {
            workoutStart = elapsed
            stepStart = elapsed
            return events
        }
        val dt = if (prev == null) 0.0 else (elapsed - prev).coerceIn(0.0, 5.0)
        if (dt > 0 && power != null) {
            lapEnergy += power * dt
            lapTime += dt
        }
        // Advance through as many steps as the elapsed time covers.
        while (!finished) {
            val end = stepStart!! + workout.steps[stepIndex].durationSec
            if (elapsed + 1e-6 < end) {
                val left = kotlin.math.ceil(end - elapsed).toInt()
                val prevLeft = prev?.let { kotlin.math.ceil(end - it).toInt() }
                if (left in 1..3 && prevLeft != left) events += WorkoutEvent.Countdown(left)
                break
            }
            advance(end, events)
        }
        return events
    }

    /** End the current step now (at the last seen ride time). */
    fun skip(): List<WorkoutEvent> {
        val now = lastElapsed ?: return emptyList()
        if (finished || workoutStart == null) return emptyList()
        val events = mutableListOf<WorkoutEvent>()
        // Shift the workout clock so totals reflect the skipped remainder.
        val end = stepStart!! + workout.steps[stepIndex].durationSec
        workoutStart = workoutStart!! - (end - now)
        advance(now, events)
        return events
    }

    private fun advance(at: Double, events: MutableList<WorkoutEvent>) {
        lapEnergy = 0.0
        lapTime = 0.0
        if (stepIndex + 1 >= workout.steps.size) {
            finished = true
            events += WorkoutEvent.Finished
        } else {
            stepIndex++
            stepStart = at
            events += WorkoutEvent.StepStarted(stepIndex, workout.steps[stepIndex], workout.steps.getOrNull(stepIndex + 1))
        }
    }

    fun state(heartRate: Int?): WorkoutState {
        val now = lastElapsed ?: 0.0
        val stepElapsed = stepStart?.let { now - it } ?: 0.0
        val total = workoutStart?.let { (now - it).coerceAtMost(workout.totalSec.toDouble()) } ?: 0.0
        return WorkoutState(
            workout = workout,
            stepIndex = stepIndex,
            stepElapsedSec = stepElapsed,
            totalElapsedSec = if (finished) workout.totalSec.toDouble() else total,
            targetW = if (finished) null else workout.steps[stepIndex].targetAt(stepElapsed),
            lapAvgW = if (lapTime >= 1.0) (lapEnergy / lapTime).roundToInt() else null,
            power3s = if (recentPower.isEmpty()) null else recentPower.average().roundToInt(),
            heartRate = heartRate,
            started = isStarted,
            finished = finished,
        )
    }
}
