package com.karoo_intervals.engine

import com.karoo_intervals.workout.Step
import com.karoo_intervals.workout.Workout
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutCoordinatorTest {
    private val workout = Workout(
        id = "w1",
        name = "Test",
        date = "2026-11-05",
        steps = listOf(
            Step(durationSec = 10, lowW = 100, highW = 100),
            Step(durationSec = 5, lowW = 300, highW = 300),
            Step(durationSec = 10, lowW = 100, highW = 200, ramp = true),
        ),
    )

    private fun sp(type: String, v: Double): StreamState =
        StreamState.Streaming(DataPoint(type, mapOf(type to v)))

    private class Harness(workout: Workout?) {
        val elapsed = MutableSharedFlow<StreamState>()
        val power = MutableSharedFlow<StreamState>()
        val ride = MutableSharedFlow<RideState>()
        val cmds = MutableSharedFlow<WorkoutCommand>()
        val coordinator = WorkoutCoordinator(
            flowOf(workout), elapsed, power, flowOf(), ride, cmds,
        )
    }

    @Test
    fun `steps advance on elapsed time and emit events`() = runTest {
        val h = Harness(workout)
        val out = mutableListOf<WorkoutUpdate>()
        val job = launch { h.coordinator.run().toList(out) }
        testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        for (t in 0..16) {
            h.power.emit(sp(DataType.Type.POWER, 200.0))
            h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, t.toDouble()))
            testScheduler.runCurrent()
        }
        val last = out.last().state!!
        assertEquals(2, last.stepIndex)
        assertEquals(1.0, last.stepElapsedSec, 1e-9)
        assertEquals(110, last.targetW) // ramp 100→200 at 1/10
        val events = out.flatMap { it.events }
        assertEquals(2, events.count { it is WorkoutEvent.StepStarted })
        assertEquals(
            listOf(3, 2, 1, 3, 2, 1),
            events.filterIsInstance<WorkoutEvent.Countdown>().map { it.secondsLeft },
        )
        job.cancel()
    }

    @Test
    fun `paused ride does not advance the workout`() = runTest {
        val h = Harness(workout)
        val out = mutableListOf<WorkoutUpdate>()
        val job = launch { h.coordinator.run().toList(out) }
        testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, 0.0)); testScheduler.runCurrent()
        h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, 4.0)); testScheduler.runCurrent()
        h.ride.emit(RideState.Paused(auto = false)); testScheduler.runCurrent()
        // ELAPSED_TIME itself is frozen during a pause; ticks while paused are ignored anyway.
        h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, 4.0)); testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, 5.0)); testScheduler.runCurrent()
        val st = out.last().state!!
        assertEquals(0, st.stepIndex)
        assertEquals(5, st.stepRemainingSec)
        job.cancel()
    }

    @Test
    fun `skip jumps to next step and resets lap average`() = runTest {
        val h = Harness(workout)
        val out = mutableListOf<WorkoutUpdate>()
        val job = launch { h.coordinator.run().toList(out) }
        testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        for (t in 0..3) {
            h.power.emit(sp(DataType.Type.POWER, 150.0))
            h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, t.toDouble()))
            testScheduler.runCurrent()
        }
        assertEquals(150, out.last().state!!.lapAvgW)
        h.cmds.emit(WorkoutCommand.SKIP); testScheduler.runCurrent()
        val st = out.last().state!!
        assertEquals(1, st.stepIndex)
        assertNull(st.lapAvgW)
        assertEquals(10.0, st.totalElapsedSec, 1e-9)
        assertTrue(out.last().events.single() is WorkoutEvent.StepStarted)
        job.cancel()
    }

    @Test
    fun `finishes after last step and ride end resets`() = runTest {
        val h = Harness(workout)
        val out = mutableListOf<WorkoutUpdate>()
        val job = launch { h.coordinator.run().toList(out) }
        testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        for (t in 0..30) {
            h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, t.toDouble()))
            testScheduler.runCurrent()
        }
        assertTrue(out.last().state!!.finished)
        assertEquals(1, out.flatMap { it.events }.count { it == WorkoutEvent.Finished })
        h.ride.emit(RideState.Idle); testScheduler.runCurrent()
        val reset = out.last().state!!
        assertEquals(0, reset.stepIndex)
        assertEquals(false, reset.started)
        job.cancel()
    }

    @Test
    fun `workout starts mid ride at current elapsed`() = runTest {
        val h = Harness(workout)
        val out = mutableListOf<WorkoutUpdate>()
        val job = launch { h.coordinator.run().toList(out) }
        testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, 1000.0)); testScheduler.runCurrent()
        h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, 1003.0)); testScheduler.runCurrent()
        val st = out.last().state!!
        assertEquals(0, st.stepIndex)
        assertEquals(7, st.stepRemainingSec)
        job.cancel()
    }

    @Test
    fun `workout pause holds the workout clock while the ride keeps recording`() = runTest {
        val h = Harness(workout)
        val out = mutableListOf<WorkoutUpdate>()
        val job = launch { h.coordinator.run().toList(out) }
        testScheduler.runCurrent()
        h.ride.emit(RideState.Recording)
        for (t in 0..4) { h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, t.toDouble())); testScheduler.runCurrent() }
        h.cmds.emit(WorkoutCommand.TOGGLE_PAUSE); testScheduler.runCurrent()
        assertTrue(out.last().state!!.paused)
        for (t in 5..30) { h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, t.toDouble())); testScheduler.runCurrent() }
        var st = out.last().state!!
        assertEquals(0, st.stepIndex)
        assertEquals(6, st.stepRemainingSec) // 4 s ridden before the pause, frozen since
        assertEquals(TargetStatus.NONE, st.powerStatus)
        h.cmds.emit(WorkoutCommand.TOGGLE_PAUSE); testScheduler.runCurrent()
        for (t in 31..33) { h.elapsed.emit(sp(DataType.Type.ELAPSED_TIME, t.toDouble())); testScheduler.runCurrent() }
        st = out.last().state!!
        assertEquals(false, st.paused)
        assertEquals(3, st.stepRemainingSec)
        assertEquals(7.0, st.totalElapsedSec, 1e-9)
        job.cancel()
    }
}
