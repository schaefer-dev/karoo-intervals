package com.karoo_intervals.workout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

/**
 * intervals.icu → [Workout]. Pure, JVM-tested against real API fixtures.
 *
 * `workout_doc.steps` is a tree: leaf steps have `duration` and targets; repeat blocks have
 * `reps` + nested `steps`. Targets look like:
 *  - `power: {units: "%ftp", start: 50, end: 60}` → range (ramp only if `ramp: true`)
 *  - `power: {units: "%ftp", value: 89}` / `{units: "w", value: 120}`
 *  - `power: {units: "power_zone", value: 2}` → that zone's range
 *  - `hr: {units: "%lthr", start, end}`, `cadence: {units: "rpm", ...}`
 */
object WorkoutParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val RIDE_TYPES = setOf("Ride", "VirtualRide", "MountainBikeRide", "GravelRide")

    /** Events (`/athlete/0/events?category=WORKOUT`) → ride workouts with at least one timed step. */
    fun parseEvents(body: String, power: PowerSettings, lthr: Int?): List<Workout> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val type = obj.str("type")
            if (type != null && type !in RIDE_TYPES) return@mapNotNull null
            val doc = obj["workout_doc"] as? JsonObject ?: return@mapNotNull null
            val steps = flatten(doc["steps"] as? JsonArray ?: return@mapNotNull null, power, lthr)
            if (steps.isEmpty()) return@mapNotNull null
            Workout(
                id = obj.str("id") ?: return@mapNotNull null,
                name = obj.str("name") ?: "Workout",
                date = obj.str("start_date_local")?.take(10),
                steps = steps,
                description = doc.str("description"),
            )
        }.sortedBy { it.date }

    data class Athlete(val power: PowerSettings, val lthr: Int?)

    /** `/athlete/0/sport-settings/Ride`. */
    fun parseSportSettings(body: String): Athlete? {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
        val ftp = obj.int("ftp") ?: return null
        val zones = (obj["power_zones"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.takeIf { it.isNotEmpty() } ?: PowerSettings.DEFAULT_ZONES
        return Athlete(PowerSettings(ftp, zones), obj.int("lthr"))
    }

    fun flatten(steps: JsonArray, power: PowerSettings, lthr: Int?): List<Step> {
        val out = mutableListOf<Step>()
        for (el in steps) {
            val s = el as? JsonObject ?: continue
            val children = s["steps"] as? JsonArray
            if (children != null) {
                val reps = (s.int("reps") ?: 1).coerceAtLeast(1)
                val inner = flatten(children, power, lthr)
                repeat(reps) { r ->
                    inner.forEach { st ->
                        out += if (reps > 1) st.copy(repLabel = "${r + 1}/$reps") else st
                    }
                }
            } else {
                leaf(s, power, lthr)?.let { out += it }
            }
        }
        return out
    }

    private fun leaf(s: JsonObject, power: PowerSettings, lthr: Int?): Step? {
        val duration = s.int("duration")?.takeIf { it > 0 } ?: return null // distance-based steps unsupported
        val ramp = s.bool("ramp") == true
        var lowW: Int? = null
        var highW: Int? = null
        (s["power"] as? JsonObject)?.let { p ->
            val (lo, hi) = rangeOf(p) ?: return@let
            when (p.str("units")) {
                "%ftp" -> { lowW = power.pctToW(lo); highW = power.pctToW(hi) }
                "w" -> { lowW = lo.roundToInt(); highW = hi.roundToInt() }
                "power_zone" -> {
                    val z1 = power.zoneRangeW(lo.roundToInt())
                    val z2 = power.zoneRangeW(hi.roundToInt())
                    lowW = z1.first; highW = z2.second
                }
            }
        }
        var hrLow: Int? = null
        var hrHigh: Int? = null
        (s["hr"] as? JsonObject)?.let { h ->
            val (lo, hi) = rangeOf(h) ?: return@let
            when (h.str("units")) {
                "%lthr" -> if (lthr != null) {
                    hrLow = (lo * lthr / 100).roundToInt(); hrHigh = (hi * lthr / 100).roundToInt()
                }
                "bpm" -> { hrLow = lo.roundToInt(); hrHigh = hi.roundToInt() }
            }
        }
        val cad = (s["cadence"] as? JsonObject)?.let { rangeOf(it) }
        val mid = if (lowW != null && highW != null) (lowW!! + highW!!) / 2.0 else null
        return Step(
            durationSec = duration,
            text = s.str("text")?.trim()?.takeIf { it.isNotEmpty() },
            lowW = lowW,
            highW = highW,
            ramp = ramp,
            freeride = s.bool("freeride") == true,
            cadenceLow = cad?.first?.roundToInt(),
            cadenceHigh = cad?.second?.roundToInt(),
            hrLow = hrLow,
            hrHigh = hrHigh,
            zone = mid?.let { power.zoneFor(it) },
        )
    }

    private fun rangeOf(o: JsonObject): Pair<Double, Double>? {
        o.dbl("value")?.let { return it to it }
        val start = o.dbl("start") ?: return null
        val end = o.dbl("end") ?: start
        return start to end
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonElement)?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }
    private fun JsonObject.int(k: String): Int? = runCatching { this[k]?.jsonPrimitive?.doubleOrNull?.roundToInt() }.getOrNull()
    private fun JsonObject.dbl(k: String): Double? = runCatching { this[k]?.jsonPrimitive?.doubleOrNull }.getOrNull()
    private fun JsonObject.bool(k: String): Boolean? = runCatching { this[k]?.jsonPrimitive?.booleanOrNull }.getOrNull()
}
