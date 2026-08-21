package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentOpenLifecycleTest {
    @Test
    fun pendingOpenWaitsUntilTheOwningLifecycleResumes() =
        runBlocking {
            val owner = AttachmentLifecycleOwner().apply { moveTo(Lifecycle.Event.ON_START) }
            val resumed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    owner.lifecycle.awaitResumedOrDestroyed()
                }

            assertFalse(resumed.isCompleted)
            owner.moveTo(Lifecycle.Event.ON_RESUME)
            assertTrue(resumed.await())
        }

    @Test
    fun destroyingTheOwningLifecycleDoesNotDispatchTheOpen() =
        runBlocking {
            val owner = AttachmentLifecycleOwner().apply { moveTo(Lifecycle.Event.ON_START) }
            val resumed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    owner.lifecycle.awaitResumedOrDestroyed()
                }

            owner.moveTo(Lifecycle.Event.ON_DESTROY)
            assertFalse(resumed.await())
        }
}

private class AttachmentLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_START) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        registry.handleLifecycleEvent(event)
    }
}
