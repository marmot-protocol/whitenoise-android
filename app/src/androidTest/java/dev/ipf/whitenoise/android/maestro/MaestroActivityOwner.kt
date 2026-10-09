package dev.ipf.whitenoise.android.maestro

import android.content.Intent
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.ipf.whitenoise.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Real lifecycle ownership survives shipping one-shot intent consumption and launcher reentry. */
internal class MaestroActivityOwner(
    private val application: MaestroFixtureApplication,
) {
    private val fixtureState = application.fixtureState
    private val monitor = ActivityLifecycleMonitorRegistry.getInstance()
    private val observed = linkedMapOf<MainActivity, Stage>()
    private var registered = false
    private val callback =
        ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && activity.application === application) {
                check(application.fixtureState === fixtureState) { "Activity fixture generation changed" }
                observed[activity] = stage
            }
        }

    suspend fun launch(intent: Intent) {
        withContext(Dispatchers.Main.immediate) {
            check(!registered && observed.isEmpty())
            val liveStages = Stage.values().filter { it != Stage.DESTROYED }
            check(liveStages.all { stage -> monitor.getActivitiesInStage(stage).none { it is MainActivity } }) {
                "A previous fixture activity remains live"
            }
            monitor.addLifecycleCallback(callback)
            registered = true
            application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        withTimeout(30_000L) {
            while (!withContext(Dispatchers.Main.immediate) { observed.values.count { it == Stage.RESUMED } == 1 }) {
                delay(100L)
            }
        }
    }

    /** External dispatch can temporarily pause MainActivity; observe its actual resumed owner before reading. */
    suspend fun onActivity(action: (MainActivity) -> Unit) {
        withTimeout(30_000L) {
            while (true) {
                val invoked =
                    withContext(Dispatchers.Main.immediate) {
                        check(registered && application.fixtureState === fixtureState)
                        val resumed = observed.filterValues { it == Stage.RESUMED }.keys
                        check(resumed.size <= 1) { "Multiple resumed fixture activities" }
                        val activity = resumed.singleOrNull() ?: return@withContext false
                        check(!activity.isDestroyed && !activity.isFinishing)
                        action(activity)
                        true
                    }
                if (invoked) return@withTimeout
                delay(100L)
            }
        }
    }

    /** Finish every observed owner and require Android's real destroyed callback before cleanup proof. */
    suspend fun close() {
        try {
            withContext(Dispatchers.Main.immediate) {
                check(registered && observed.isNotEmpty()) { "Activity lifecycle ownership was not established" }
                observed.filterValues { it != Stage.DESTROYED }.keys.forEach { it.finish() }
            }
            withTimeout(10_000L) {
                while (!withContext(Dispatchers.Main.immediate) { observed.values.all { it == Stage.DESTROYED } }) {
                    delay(100L)
                }
            }
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (registered) monitor.removeLifecycleCallback(callback)
                registered = false
            }
        }
    }
}
