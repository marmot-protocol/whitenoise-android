package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.ui.navigation.WarmResumeLifecycleClass
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The route's slices, read back through a recording sink: what opens and closes, in which order, and that
 * nothing written to a trace can carry an account, group or message identifier.
 */
class NotificationRouteTraceTest {
    private val recorder = RecordingSink()
    private lateinit var original: NotificationRouteTraceSink

    /** Installs the recording sink and clears any request a previous case left open. */
    @Before
    fun install() {
        original = NotificationRouteTrace.sink
        NotificationRouteTrace.finishRequest(REQUEST)
        NotificationRouteTrace.finishRequest(NEWER_REQUEST)
        NotificationRouteTrace.sink = recorder
    }

    /** Restores the platform sink so no other suite writes into this recorder. */
    @After
    fun restore() {
        NotificationRouteTrace.finishRequest(REQUEST)
        NotificationRouteTrace.finishRequest(NEWER_REQUEST)
        NotificationRouteTrace.sink = original
    }

    /** A request's total opens with its launch-class marker and closes when the request finishes. */
    @Test
    fun aRequestOpensItsTotalWithALaunchClassMarkerAndClosesItOnFinish() {
        NotificationRouteTrace.startRequest(REQUEST, NotificationRouteLaunchClass.COLD_PROCESS)
        NotificationRouteTrace.finishRequest(REQUEST)

        assertEquals(
            listOf(
                "begin ${NotificationRouteTraceSection.TOTAL}",
                "instant ${NotificationRouteTraceSection.LAUNCH_CLASS_PREFIX}coldProcess",
                "end ${NotificationRouteTraceSection.TOTAL}",
            ),
            recorder.events,
        )
    }

    /** A disabled trace opens nothing, so an untraced process pays no section bookkeeping. */
    @Test
    fun aDisabledTraceWritesNothingAndLaterPausesAreIgnored() {
        recorder.enabled = false

        NotificationRouteTrace.startRequest(REQUEST, NotificationRouteLaunchClass.WARM_TASK)
        NotificationRouteTrace.pauseForAppLock(REQUEST)
        NotificationRouteTrace.resumeAfterAppLock(REQUEST)

        assertTrue(recorder.events.isEmpty())
    }

    /** A newer tap closes the older request's slices before opening its own. */
    @Test
    fun aNewerRequestClosesTheOlderOnesSlices() {
        NotificationRouteTrace.startRequest(REQUEST)
        NotificationRouteTrace.beginPhase(REQUEST, NotificationRouteTraceSection.COMMIT_DISMISS)
        NotificationRouteTrace.startRequest(NEWER_REQUEST)

        assertEquals(
            listOf(
                "begin ${NotificationRouteTraceSection.TOTAL}",
                "begin ${NotificationRouteTraceSection.COMMIT_DISMISS}",
                "end ${NotificationRouteTraceSection.COMMIT_DISMISS}",
                "end ${NotificationRouteTraceSection.TOTAL}",
                "begin ${NotificationRouteTraceSection.TOTAL}",
            ),
            recorder.events,
        )
    }

    /** The commit's awaited dismissal is its own slice, and only the active request may open one. */
    @Test
    fun theCommitDismissPhaseIsTracedForTheActiveRequestOnly() =
        runTest {
            NotificationRouteTrace.startRequest(REQUEST)
            NotificationRouteTrace.tracePhase(REQUEST, NotificationRouteTraceSection.COMMIT_DISMISS) {}
            NotificationRouteTrace.tracePhase(NEWER_REQUEST, NotificationRouteTraceSection.COMMIT_DISMISS) {}

            assertEquals(
                listOf(
                    "begin ${NotificationRouteTraceSection.TOTAL}",
                    "begin ${NotificationRouteTraceSection.COMMIT_DISMISS}",
                    "end ${NotificationRouteTraceSection.COMMIT_DISMISS}",
                ),
                recorder.events,
            )
        }

    /** Human unlock time is its own slice: the total closes before the wait and resumes after it. */
    @Test
    fun appLockSplitsTheTotalSoTheBudgetExcludesHumanUnlockTime() {
        NotificationRouteTrace.startRequest(REQUEST)
        NotificationRouteTrace.pauseForAppLock(REQUEST)
        NotificationRouteTrace.pauseForAppLock(REQUEST)
        NotificationRouteTrace.resumeAfterAppLock(REQUEST)
        NotificationRouteTrace.resumeAfterAppLock(REQUEST)
        NotificationRouteTrace.finishRequest(REQUEST)

        assertEquals(
            listOf(
                "begin ${NotificationRouteTraceSection.TOTAL}",
                "end ${NotificationRouteTraceSection.TOTAL}",
                "begin ${NotificationRouteTraceSection.APP_LOCK_WAIT}",
                "end ${NotificationRouteTraceSection.APP_LOCK_WAIT}",
                "begin ${NotificationRouteTraceSection.TOTAL}",
                "end ${NotificationRouteTraceSection.TOTAL}",
            ),
            recorder.events,
        )
    }

    /** A request that ends while the lock is still up closes the wait, not a total that already ended. */
    @Test
    fun finishingWhileLockedClosesTheWaitSlice() {
        NotificationRouteTrace.startRequest(REQUEST)
        NotificationRouteTrace.pauseForAppLock(REQUEST)
        NotificationRouteTrace.finishRequest(REQUEST)

        assertEquals("end ${NotificationRouteTraceSection.APP_LOCK_WAIT}", recorder.events.last())
        assertEquals(1, recorder.events.count { it == "end ${NotificationRouteTraceSection.APP_LOCK_WAIT}" })
        assertEquals(1, recorder.events.count { it == "end ${NotificationRouteTraceSection.TOTAL}" })
    }

    /** A lock event for a request that is no longer current cannot touch the current request's slices. */
    @Test
    fun aStaleRequestCannotPauseOrResumeTheCurrentOne() {
        NotificationRouteTrace.startRequest(NEWER_REQUEST)

        NotificationRouteTrace.pauseForAppLock(REQUEST)
        NotificationRouteTrace.resumeAfterAppLock(REQUEST)

        assertEquals(listOf("begin ${NotificationRouteTraceSection.TOTAL}"), recorder.events)
    }

    /** Every section name and launch label is a fixed word, so no identifier or content can enter a trace. */
    @Test
    fun noSectionNameCanCarryAnIdentifier() {
        val names =
            listOf(
                NotificationRouteTraceSection.TOTAL,
                NotificationRouteTraceSection.COMMIT_DISMISS,
                NotificationRouteTraceSection.STARTUP_SWAP,
                NotificationRouteTraceSection.APP_LOCK_WAIT,
            ) +
                NotificationRouteLaunchClass.entries.map {
                    NotificationRouteTraceSection.LAUNCH_CLASS_PREFIX + it.traceLabel
                }

        names.forEach { name ->
            assertTrue(name, name.startsWith("WhiteNoise.notificationRoute."))
            assertFalse("a section name must not carry digits: $name", name.any(Char::isDigit))
        }
    }

    /** A lock outranks everything, then a new process, then an unready runtime, else the tap is warm. */
    @Test
    fun launchClassificationPrefersLockThenColdProcessThenUnreadyRuntime() {
        /** Classifies one tap by its lifecycle, runtime readiness and lock state. */
        fun classify(
            lifecycle: WarmResumeLifecycleClass,
            ready: Boolean,
            locked: Boolean,
        ) = classifyNotificationRouteLaunch(lifecycle, ready, locked)

        assertEquals(
            NotificationRouteLaunchClass.APP_LOCK_DEFERRED,
            classify(WarmResumeLifecycleClass.ColdProcessStart, ready = false, locked = true),
        )
        assertEquals(
            NotificationRouteLaunchClass.COLD_PROCESS,
            classify(WarmResumeLifecycleClass.ColdProcessStart, ready = true, locked = false),
        )
        assertEquals(
            NotificationRouteLaunchClass.COLD_PROCESS,
            classify(WarmResumeLifecycleClass.ProcessRestoration, ready = false, locked = false),
        )
        assertEquals(
            NotificationRouteLaunchClass.RUNTIME_NOT_READY,
            classify(WarmResumeLifecycleClass.SameActivity, ready = false, locked = false),
        )
        assertEquals(
            NotificationRouteLaunchClass.WARM_TASK,
            classify(WarmResumeLifecycleClass.RetainedViewModelActivity, ready = true, locked = false),
        )
    }

    /** A sink that records every call as one readable line. */
    private class RecordingSink : NotificationRouteTraceSink {
        val events = mutableListOf<String>()
        var enabled = true

        /** Reports whatever the case configured. */
        override fun isEnabled(): Boolean = enabled

        /** Records an opening. */
        override fun beginAsyncSection(
            name: String,
            cookie: Int,
        ) {
            events += "begin $name"
        }

        /** Records a closing. */
        override fun endAsyncSection(
            name: String,
            cookie: Int,
        ) {
            events += "end $name"
        }

        /** Records a zero-length slice. */
        override fun instant(name: String) {
            events += "instant $name"
        }
    }

    private companion object {
        const val REQUEST = 41L
        const val NEWER_REQUEST = 42L
    }
}
