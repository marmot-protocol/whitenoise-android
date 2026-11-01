package dev.ipf.whitenoise.android.ui.conversation.media

import android.os.Looper
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.BroadcastFrameClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Window admission waits for a submitted hardware frame and cannot outlive a cancelled viewer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaViewerEntryFrameTest {
    /** A hardware-accelerated host enters only after its pre-draw frame commits, not at pre-draw itself. */
    @Test
    fun hardwareEntryDoesNotBeginAtPreDrawOrBeforeCommit() =
        runBlocking {
            val view = hardwareView()
            var entered = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaViewerEntryFrame(view)
                    entered = true
                }
            assertFalse(entered)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertFalse(entered)
            val callbacks = captureCommits(view)
            assertEquals(1, callbacks.size)
            callbacks.single().run()
            yield()
            assertTrue(entered)
            job.join()
        }

    /** A software-rendered host proceeds at pre-draw because it has no frame commit to wait for. */
    @Test
    fun softwareHostProceedsAtPreDrawWithoutWaitingForAnUnsupportedCommit() =
        runBlocking {
            val view = View(ApplicationProvider.getApplicationContext())
            var entered = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaViewerEntryFrame(view)
                    entered = true
                }
            assertFalse(entered)
            view.viewTreeObserver.dispatchOnPreDraw()
            yield()
            assertTrue(entered)
            assertTrue(captureCommits(view).isEmpty())
            job.join()
        }

    /** Cancelling before pre-draw removes the pending admission, so a later pre-draw registers no commit. */
    @Test
    fun cancellationBeforePreDrawRemovesThePendingAdmission() =
        runBlocking {
            val view = hardwareView()
            var entered = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaViewerEntryFrame(view)
                    entered = true
                }
            job.cancelAndJoin()
            shadowOf(Looper.getMainLooper()).idle()
            view.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(captureCommits(view).isEmpty())
            assertFalse(entered)
        }

    /** Cancelling after pre-draw unregisters the frame-commit callback so no stale commit remains. */
    @Test
    fun cancellationAfterPreDrawUnregistersTheCommitCallback() =
        runBlocking {
            val view = hardwareView()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitMediaViewerEntryFrame(view) }
            view.viewTreeObserver.dispatchOnPreDraw()
            job.cancelAndJoin()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(captureCommits(view).isEmpty())
        }

    /** A commit already captured by the renderer cannot let a cancelled viewer proceed when it later runs. */
    @Test
    fun commitAlreadyCapturedByTheRendererCannotReviveACancelledViewer() =
        runBlocking {
            val view = hardwareView()
            var entered = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    awaitMediaViewerEntryFrame(view)
                    entered = true
                }
            view.viewTreeObserver.dispatchOnPreDraw()
            val committed = captureCommits(view).single()
            job.cancelAndJoin()
            shadowOf(Looper.getMainLooper()).idle()
            committed.run()
            yield()
            assertFalse(entered)
        }

    /** Losing the source during window admission unregisters the pending commit and settles without any travel. */
    @Test
    fun sourceLossDuringWindowAdmissionUnregistersCommitAndFallsBackWithoutTravel() = sourceLossDuringAdmission(false)

    /** A commit the renderer already captured cannot start travel once the source thumbnail is lost. */
    @Test
    fun rendererCapturedCommitCannotStartTravelAfterItsSourceWasLost() = sourceLossDuringAdmission(true)

    /**
     * Drives an entry flight whose thumbnail disappears while waiting for the window frame, optionally after the
     * renderer captured the commit, and checks it settles once at full progress with no commit callback left behind.
     */
    private fun sourceLossDuringAdmission(captureBeforeLoss: Boolean) =
        runBlocking {
            val view = hardwareView()
            val clock = BroadcastFrameClock()
            val progress = Animatable(0f)
            var available = true
            var settled = 0
            val job =
                launch(clock, start = CoroutineStart.UNDISPATCHED) {
                    animateMediaViewerMotion(
                        progress,
                        closing = false,
                        sourceAvailable = { available },
                        awaitEntryFrame = { awaitMediaViewerEntryFrame(view) },
                        onSettled = { settled++ },
                    )
                }
            yield()
            view.viewTreeObserver.dispatchOnPreDraw()
            val captured = if (captureBeforeLoss) captureCommits(view).single() else null
            available = false
            clock.sendFrame(1_000_000L)
            withTimeout(5_000) { job.join() }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(captureCommits(view).isEmpty())
            captured?.run()
            yield()
            assertEquals(1f, progress.value, 0f)
            assertEquals(1, settled)
            assertFalse(job.isActive)
        }

    /** Builds a view that reports hardware acceleration, so entry admission waits for a frame commit. */
    private fun hardwareView(): View =
        object : View(ApplicationProvider.getApplicationContext()) {
            override fun isHardwareAccelerated(): Boolean = true
        }

    /** Simulates ViewRootImpl taking ownership of one frame's callbacks before submitting it. */
    @Suppress("UNCHECKED_CAST")
    private fun captureCommits(view: View): List<Runnable> {
        val method = android.view.ViewTreeObserver::class.java.getDeclaredMethod("captureFrameCommitCallbacks")
        method.isAccessible = true
        return method.invoke(view.viewTreeObserver) as? List<Runnable> ?: emptyList()
    }
}
