package com.karoo_intervals.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karoo_intervals.engine.WorkoutCommand
import com.karoo_intervals.engine.WorkoutRunner

/** Tap zones of the ride screen (see `field_workout.xml`). Broadcast, so the ride app stays in front. */
class TapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SKIP -> WorkoutRunner.command(WorkoutCommand.SKIP)
            ACTION_PAUSE -> WorkoutRunner.command(WorkoutCommand.TOGGLE_PAUSE)
        }
    }

    companion object {
        const val ACTION_SKIP = "com.karoo_intervals.TAP_SKIP"
        const val ACTION_PAUSE = "com.karoo_intervals.TAP_PAUSE"
        const val ACTION_NONE = "com.karoo_intervals.TAP_NONE"
    }
}
