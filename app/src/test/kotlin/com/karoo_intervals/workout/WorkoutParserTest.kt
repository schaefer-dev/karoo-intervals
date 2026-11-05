package com.karoo_intervals.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutParserTest {
    private fun resource(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText()

    private val athlete = WorkoutParser.parseSportSettings(resource("sport_settings_ride.json"))!!
    private val workouts = WorkoutParser.parseEvents(resource("events.json"), athlete.power, athlete.lthr)

    @Test
    fun `sport settings give ftp and zones`() {
        assertEquals(254, athlete.power.ftp)
        assertEquals(listOf(55, 75, 90, 105, 120, 150, 999), athlete.power.zoneUpperPct)
        assertEquals(183, athlete.lthr)
    }

    @Test
    fun `all fixture workouts parse`() {
        assertEquals(4, workouts.size)
        workouts.forEach { assertTrue(it.name, it.steps.isNotEmpty()) }
    }

    @Test
    fun `nested repeats are expanded and total matches workout_doc duration`() {
        val w = workouts.single { it.name == "3x6x30/30" }
        assertEquals(3480, w.totalSec)
        // 1 warmup + 4 (1x block) + recovery + 3 * (12 + recovery)
        assertEquals(1 + 4 + 1 + 3 * (12 + 1), w.steps.size)
        val firstHard = w.steps.first { it.text == "Hard" }
        assertEquals("1/6", firstHard.repLabel)
        assertEquals(90, firstHard.cadenceLow)
        assertEquals(110, firstHard.cadenceHigh)
    }

    @Test
    fun `percent ftp ranges convert to watts with midpoint target`() {
        val warmup = workouts.single { it.name == "3x6x30/30" }.steps.first()
        assertEquals(127, warmup.lowW) // 50 % of 254
        assertEquals(152, warmup.highW) // 60 %
        assertFalse(warmup.ramp)
        assertEquals(140, warmup.targetAt(0.0))
        assertEquals(140, warmup.targetAt(299.0))
        assertEquals(1, warmup.zone) // 139.5 W = 54.9 % ≤ 55 % → Z1 with the rider's zones
    }

    @Test
    fun `absolute watts and power zones are supported`() {
        val all = workouts.flatMap { it.steps }
        assertTrue(all.any { it.lowW == 120 && it.highW == 120 })
        val z2 = all.first { it.freeride && it.durationSec == 600 }
        assertEquals(athlete.power.zoneRangeW(2), z2.lowW!! to z2.highW!!)
        assertNull(z2.targetAt(0.0)) // freeride: no target
    }

    @Test
    fun `ramp interpolates`() {
        val s = Step(durationSec = 100, lowW = 100, highW = 200, ramp = true)
        assertEquals(100, s.targetAt(0.0))
        assertEquals(150, s.targetAt(50.0))
        assertEquals(200, s.targetAt(150.0))
    }

    @Test
    fun `hr percent lthr resolves to bpm`() {
        // No ride HR workouts in the account fixture; inline the documented shape.
        val body = """[{"id":1,"name":"HR","type":"Ride","start_date_local":"2026-11-05T00:00:00",
            "workout_doc":{"steps":[{"duration":600,"hr":{"units":"%lthr","start":70,"end":80}}]}}]"""
        val step = WorkoutParser.parseEvents(body, athlete.power, athlete.lthr).single().steps.single()
        assertEquals(128, step.hrLow) // 70 % of 183
        assertEquals(146, step.hrHigh)
        assertNull(step.targetAt(0.0))
    }

    @Test
    fun `summary formats like the mockup`() {
        val s = Step(durationSec = 60, lowW = 181, highW = 181, zone = 1)
        assertEquals("1m @ 181W · Z1", s.summary())
        assertEquals("19:00", formatClock(1140))
        assertEquals("1:00:00", formatClock(3600))
    }
}
