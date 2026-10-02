package dev.ipf.whitenoise.android.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.AttachmentDownloadIntentStore
import dev.ipf.whitenoise.android.state.AttachmentOpenCoordinator
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentDispatchGuard
import dev.ipf.whitenoise.android.ui.conversation.media.OpenAttachmentResult
import dev.ipf.whitenoise.android.ui.conversation.media.openAttachmentExternally
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/** Exercises the real Android external opener while cancellation persistence is deliberately delayed. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class AttachmentOpenDispatchDeviceTest {
    /** Cancel must prevent platform dispatch even when a valid local artifact and the old durable intent remain. */
    @Test
    @Suppress("LongMethod") // One guarded fixture owns delayed persistence and dispatch.
    fun cancelledOpenCannotLaunchBeforeDiskCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferencesName = "dispatch-fence-${UUID.randomUUID()}"
        val store = AttachmentDownloadIntentStore(context.getSharedPreferences(preferencesName, 0))
        val persistence = PausedPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val file = File.createTempFile("dispatch-fence-", ".txt", context.cacheDir)
        file.writeText("generated fixture")
        var launches = 0
        lateinit var canDispatch: () -> Boolean
        try {
            instrumentation.runOnMainSync {
                val coordinator =
                    AttachmentOpenCoordinator(store, scope, { _, _ -> }, { _, _ -> true }, persistence)
                val request = AttachmentTransferRequest("generated-account", "ab".repeat(16), "cd".repeat(32), 0)
                coordinator.setDestination(AttachmentOpenDestination(request.accountRef, request.groupIdHex, 1L))
                assertTrue(coordinator.requestOpen(request))
                val open = requireNotNull(coordinator.openRequest(request))
                canDispatch = coordinator.captureDispatchGuard(open)
                assertTrue(canDispatch())
                coordinator.cancelOpen(open)
                assertTrue("disk cleanup must still be paused", store.hasOpenIntent(open))
                assertFalse(coordinator.hasIntent(open))
            }
            val guard = AttachmentDispatchGuard(canDispatch, onPlatformDispatchStarted = { launches++ })
            val result =
                runBlocking {
                    openAttachmentExternally(
                        context,
                        file,
                        "text/plain",
                        "fixture.txt",
                        dispatchGuard = guard,
                    )
                }
            assertEquals(OpenAttachmentResult.DestinationNotVisible, result)
            assertEquals(0, launches)
        } finally {
            instrumentation.runOnMainSync { scope.cancel() }
            persistence.drain()
            file.delete()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    /** Holds only this fixture's IO continuations; the Android main thread remains free. */
    private class PausedPersistence : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()

        /** Captures a persistence continuation until the test explicitly releases it. */
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.add(block)
        }

        /** Releases cancelled continuations before deleting this fixture's private preferences. */
        fun drain() {
            while (true) (tasks.poll() ?: return).run()
        }
    }
}
