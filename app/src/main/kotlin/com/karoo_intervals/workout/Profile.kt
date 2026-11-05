package com.karoo_intervals.workout

/**
 * Workout profile geometry shared by the ride-screen bitmap and the Compose picker.
 * One [Bar] per step; x proportional to time, height to target watts (ramps are trapezoids).
 * Pure (no Android) so both renderers draw the identical shape.
 */
object Profile {
    data class Bar(
        val left: Float,
        val right: Float,
        /** Bar heights in [0,1] of the plot height at left and right edge. */
        val startFrac: Float,
        val endFrac: Float,
        val color: Int,
    )

    /** Fraction of plot height a step without a power target gets. */
    private const val FREE_FRAC = 0.25f

    fun scaleMaxW(workout: Workout): Int =
        (workout.steps.mapNotNull { it.highW }.maxOrNull() ?: 300).coerceAtLeast(100)

    fun bars(workout: Workout, width: Float): List<Bar> {
        val total = workout.totalSec.toFloat().coerceAtLeast(1f)
        val max = scaleMaxW(workout).toFloat()
        val starts = workout.stepStarts
        return workout.steps.mapIndexed { i, s ->
            val l = starts[i] / total * width
            val r = (starts[i] + s.durationSec) / total * width
            val (a, b) = when {
                s.lowW == null || s.highW == null -> FREE_FRAC to FREE_FRAC
                s.ramp -> s.lowW / max to s.highW / max
                else -> s.midW!! / max to s.midW!! / max
            }
            Bar(l, r, a.coerceIn(0.05f, 1f), b.coerceIn(0.05f, 1f), ZoneColors.forZone(s.zone))
        }
    }
}
