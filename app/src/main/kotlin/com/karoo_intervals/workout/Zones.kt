package com.karoo_intervals.workout

/** Zone → ARGB color, shared by the ride screen graph and the workout picker. Pure (no Android). */
object ZoneColors {
    private val COLORS = intArrayOf(
        0xFF8FA3AD.toInt(), // Z1 recovery – grey blue
        0xFF1FA37A.toInt(), // Z2 endurance – teal green
        0xFF6AA329.toInt(), // Z3 tempo – green
        0xFFE0A030.toInt(), // Z4 threshold – amber
        0xFFC47A1C.toInt(), // Z5 VO2 – dark amber
        0xFFE2603A.toInt(), // Z6 anaerobic – orange red
        0xFF9B4DCA.toInt(), // Z7 neuromuscular – purple
    )
    const val NO_TARGET: Int = 0xFFB0B0B0.toInt()

    fun forZone(zone: Int?): Int = if (zone == null) NO_TARGET else COLORS[(zone - 1).coerceIn(0, COLORS.size - 1)]
}
