package com.karoo_intervals.engine

import com.karoo_intervals.workout.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetJudgeTest {
    private val range = Step(durationSec = 300, lowW = 140, highW = 165)
    private val single = Step(durationSec = 60, lowW = 200, highW = 200)
    private val ramp = Step(durationSec = 100, lowW = 100, highW = 200, ramp = true)

    @Test
    fun `range uses its own bounds`() {
        assertEquals(TargetStatus.BELOW, TargetJudge.power(range, 30.0, 139))
        assertEquals(TargetStatus.IN_RANGE, TargetJudge.power(range, 30.0, 140))
        assertEquals(TargetStatus.IN_RANGE, TargetJudge.power(range, 30.0, 165))
        assertEquals(TargetStatus.ABOVE, TargetJudge.power(range, 30.0, 166))
    }

    @Test
    fun `single value gets five percent tolerance`() {
        assertEquals(TargetStatus.BELOW, TargetJudge.power(single, 30.0, 189))
        assertEquals(TargetStatus.IN_RANGE, TargetJudge.power(single, 30.0, 190))
        assertEquals(TargetStatus.IN_RANGE, TargetJudge.power(single, 30.0, 210))
        assertEquals(TargetStatus.ABOVE, TargetJudge.power(single, 30.0, 211))
    }

    @Test
    fun `ramp follows the moving target`() {
        // target at 50 s = 150 W → 143..158
        assertEquals(TargetStatus.IN_RANGE, TargetJudge.power(ramp, 50.0, 150))
        assertEquals(TargetStatus.ABOVE, TargetJudge.power(ramp, 50.0, 170))
        // lap reference = average target so far (100+150)/2 = 125 W
        assertEquals(TargetStatus.IN_RANGE, TargetJudge.lap(ramp, 50.0, 125))
    }

    @Test
    fun `grace period, missing power and free ride give no verdict`() {
        assertEquals(TargetStatus.NONE, TargetJudge.power(range, 4.0, 300))
        assertEquals(TargetStatus.NONE, TargetJudge.power(range, 30.0, null))
        assertEquals(TargetStatus.NONE, TargetJudge.power(Step(60, freeride = true, lowW = 100, highW = 120), 30.0, 300))
        assertEquals(TargetStatus.NONE, TargetJudge.power(Step(60), 30.0, 300))
    }
}
