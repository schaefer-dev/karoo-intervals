package com.karoo_intervals.engine

import com.karoo_intervals.workout.Step
import kotlin.math.roundToInt

/** Is the rider below / inside / above the current step's power target? */
enum class TargetStatus { NONE, BELOW, IN_RANGE, ABOVE }

object TargetJudge {
    /** Tolerance around a single-value (or ramp) target. */
    const val TOLERANCE = 0.05
    /** No verdict in the first seconds of a step while the rider adjusts. */
    const val GRACE_SEC = 5.0

    /** Allowed watt range at [secIntoStep]; null = no power target (free ride / HR step). */
    fun range(step: Step, secIntoStep: Double): IntRange? {
        if (!step.hasPowerTarget) return null
        val lo = step.lowW!!
        val hi = step.highW!!
        return when {
            step.ramp -> tolerance(step.targetAt(secIntoStep)!!)
            lo == hi -> tolerance(lo)
            else -> lo..hi
        }
    }

    /** Live 3 s power against the target. */
    fun power(step: Step, secIntoStep: Double, watts: Int?): TargetStatus {
        if (watts == null || secIntoStep < GRACE_SEC) return TargetStatus.NONE
        return judge(range(step, secIntoStep), watts)
    }

    /**
     * Lap average against the target. For ramps the reference is the average target so far
     * (midpoint of start and current target), since the lap average lags a rising target.
     */
    fun lap(step: Step, secIntoStep: Double, lapAvg: Int?): TargetStatus {
        if (lapAvg == null || secIntoStep < GRACE_SEC) return TargetStatus.NONE
        val r = if (step.ramp && step.hasPowerTarget) {
            tolerance(((step.targetAt(0.0)!! + step.targetAt(secIntoStep)!!) / 2.0).roundToInt())
        } else {
            range(step, secIntoStep)
        }
        return judge(r, lapAvg)
    }

    private fun tolerance(w: Int): IntRange =
        (w * (1 - TOLERANCE)).roundToInt()..(w * (1 + TOLERANCE)).roundToInt()

    private fun judge(r: IntRange?, w: Int): TargetStatus = when {
        r == null -> TargetStatus.NONE
        w < r.first -> TargetStatus.BELOW
        w > r.last -> TargetStatus.ABOVE
        else -> TargetStatus.IN_RANGE
    }
}
