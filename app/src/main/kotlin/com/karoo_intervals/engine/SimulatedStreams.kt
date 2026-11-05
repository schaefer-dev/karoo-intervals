package com.karoo_intervals.engine

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

/**
 * Synthetic ride streams for the debug simulator (toggle in the app, debug builds only).
 * Runs 5× faster than real time so a workout can be checked on-device without riding.
 * ELAPSED_TIME is emitted in **milliseconds**, like the real SDK.
 */
object SimulatedStreams {
    private const val TICK_MS = 200L

    fun elapsedTime(): Flow<StreamState> = flow {
        var t = 0
        while (true) {
            emit(streaming(DataType.Type.ELAPSED_TIME, t * 1000.0))
            delay(TICK_MS)
            t++
        }
    }

    /** Follows the current target ±15 W so the screen looks alive. */
    fun power(target: () -> Int?): Flow<StreamState> = flow {
        var t = 0
        while (true) {
            val base = target() ?: 150
            emit(streaming(DataType.Type.POWER, base + 15 * sin(t / 3.0)))
            delay(TICK_MS)
            t++
        }
    }

    fun heartRate(): Flow<StreamState> = flow {
        var t = 0
        while (true) {
            emit(streaming(DataType.Type.HEART_RATE, 135 + 20 * sin(t / 60.0)))
            delay(TICK_MS)
            t++
        }
    }

    fun rideState(): Flow<RideState> = flow {
        emit(RideState.Recording)
        awaitCancellation()
    }

    private fun streaming(type: String, v: Double): StreamState =
        StreamState.Streaming(DataPoint(type, mapOf(type to v)))
}
