package com.karoo_intervals.workout

import kotlin.math.roundToInt

/** Rider's power settings from intervals.icu (Ride sport settings). */
data class PowerSettings(
    val ftp: Int,
    /** Upper bounds of each power zone in %FTP, e.g. [55, 75, 90, 105, 120, 150, 999]. */
    val zoneUpperPct: List<Int> = DEFAULT_ZONES,
) {
    /** 1-based zone for a wattage. */
    fun zoneFor(watts: Double): Int {
        val pct = watts / ftp * 100.0
        val idx = zoneUpperPct.indexOfFirst { pct <= it }
        return if (idx < 0) zoneUpperPct.size else idx + 1
    }

    /** Watt range of a 1-based zone (lower bound exclusive of the previous zone's upper). */
    fun zoneRangeW(zone: Int): Pair<Int, Int> {
        val z = zone.coerceIn(1, zoneUpperPct.size)
        val lowPct = if (z == 1) 0 else zoneUpperPct[z - 2] + 1
        // The last zone's upper bound (999 %) is a sentinel; cap at 150 % over the low end.
        val highPct = if (z == zoneUpperPct.size) lowPct + 50 else zoneUpperPct[z - 1]
        return pctToW(lowPct.toDouble()) to pctToW(highPct.toDouble())
    }

    fun pctToW(pct: Double): Int = (pct * ftp / 100.0).roundToInt()

    companion object {
        val DEFAULT_ZONES = listOf(55, 75, 90, 105, 120, 150, 999)
    }
}

/**
 * One flattened workout step (repeats already expanded).
 *
 * Power target is [lowW]..[highW]. If [ramp] is true the target moves linearly from [lowW]
 * to [highW] over the step; otherwise it is a range and the target is its midpoint.
 * Both null = no power target (free ride / HR-only step).
 */
data class Step(
    val durationSec: Int,
    val text: String? = null,
    val lowW: Int? = null,
    val highW: Int? = null,
    val ramp: Boolean = false,
    val freeride: Boolean = false,
    val cadenceLow: Int? = null,
    val cadenceHigh: Int? = null,
    val hrLow: Int? = null,
    val hrHigh: Int? = null,
    /** "2/6" when the step came from a repeat block, else null. */
    val repLabel: String? = null,
    val zone: Int? = null,
) {
    val hasPowerTarget: Boolean get() = lowW != null && highW != null && !freeride

    /** Target watts at [secIntoStep] (ramp interpolated, range → midpoint). */
    fun targetAt(secIntoStep: Double): Int? {
        val lo = lowW ?: return null
        val hi = highW ?: return null
        if (freeride) return null
        if (!ramp || durationSec <= 0) return ((lo + hi) / 2.0).roundToInt()
        val f = (secIntoStep / durationSec).coerceIn(0.0, 1.0)
        return (lo + (hi - lo) * f).roundToInt()
    }

    /** Representative watts for graphs and summaries. */
    val midW: Int? get() = if (lowW != null && highW != null) (lowW + highW) / 2 else null
}

data class Workout(
    val id: String,
    val name: String,
    /** ISO local date (yyyy-MM-dd) the workout is planned for, null for library workouts. */
    val date: String?,
    val steps: List<Step>,
    val description: String? = null,
) {
    val totalSec: Int = steps.sumOf { it.durationSec }

    /** Start offset (s) of each step. */
    val stepStarts: List<Int> = steps.runningFold(0) { acc, s -> acc + s.durationSec }.dropLast(1)
}

/** "1m @ 181W · Z1", "30s @ 330W · Z6", "10m free". */
fun Step.summary(): String {
    val dur = formatDurationShort(durationSec)
    val target = when {
        hasPowerTarget -> {
            val w = if (ramp) "$lowW→${highW}W" else if (lowW == highW) "${lowW}W" else "$lowW–${highW}W"
            "$dur @ $w" + (zone?.let { " · Z$it" } ?: "")
        }
        hrLow != null -> "$dur @ ${hrLow}–${hrHigh} bpm"
        else -> "$dur free"
    }
    return target
}

fun formatDurationShort(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> if (m > 0) "${h}h${m}m" else "${h}h"
        m > 0 -> if (s > 0) "${m}m${s}s" else "${m}m"
        else -> "${s}s"
    }
}

/** 19:00, 1:02:03, -0:05 never (clamped at 0). */
fun formatClock(sec: Int): String {
    val t = sec.coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
