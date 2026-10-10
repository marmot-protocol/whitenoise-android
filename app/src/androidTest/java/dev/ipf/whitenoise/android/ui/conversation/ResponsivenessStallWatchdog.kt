package dev.ipf.whitenoise.android.ui.conversation

import android.os.Looper
import android.util.Log
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Timer
import java.util.TimerTask

/** Captures a stalled harness without depending on the UI thread or logging fixture content. */
class ResponsivenessStallWatchdog : TestWatcher() {
    @Volatile
    private var currentPhase = "rule setup"

    @Volatile
    private var testThread: Thread? = null
    private var ruleThread: Thread? = null
    private var timer: Timer? = null

    /** Arms a one-shot diagnostic outside Compose and JUnit's potentially blocked test worker. */
    override fun starting(description: Description) {
        ruleThread = Thread.currentThread()
        testThread = ruleThread
        currentPhase = "rule setup"
        timer =
            Timer("WNResponsivenessWatchdog", true).apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            Log.e("WNFirstFrameStall", "phase=$currentPhase")
                            dumpThread("rule", ruleThread)
                            dumpThread("test", testThread)
                            dumpThread("main", Looper.getMainLooper().thread)
                        }
                    },
                    90_000L,
                )
            }
    }

    /** Records a closed, content-free phase and the thread actually executing the test body. */
    fun phase(name: String) {
        testThread = Thread.currentThread()
        currentPhase = name
    }

    /** Preserves body failures before an outer Activity rule can stall, without logging fixture content. */
    fun bodyFailureReporter(): TestWatcher =
        object : TestWatcher() {
            override fun failed(
                failure: Throwable,
                description: Description,
            ) {
                Log.e("WNFirstFrameStall", "body_failure=${failure.javaClass.name} phase=$currentPhase")
                failure.stackTrace.take(32).forEach { frame -> Log.e("WNFirstFrameStall", "body: $frame") }
            }
        }

    /** Cancels diagnostics only after rule cleanup has returned, including failed test cleanup. */
    override fun finished(description: Description) {
        timer?.cancel()
        timer = null
        testThread = null
        ruleThread = null
    }

    /** Reads stack frames directly so a blocked Android main thread cannot block diagnostics. */
    private fun dumpThread(
        label: String,
        thread: Thread?,
    ) {
        thread?.stackTrace?.take(64)?.forEach { frame -> Log.e("WNFirstFrameStall", "$label: $frame") }
    }
}
