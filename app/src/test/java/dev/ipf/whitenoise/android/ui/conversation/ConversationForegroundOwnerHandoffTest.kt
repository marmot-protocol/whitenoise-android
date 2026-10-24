package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationForegroundOwnerHandoffTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun replacementAlreadyComposedWhilePausedWaitsForRealResume() {
        val fixture = Fixture()
        composeRule.setContent { fixture.Content() }
        composeRule.runOnIdle { fixture.pause() }
        val old = fixture.current
        composeRule.runOnIdle { fixture.current = fixture.newOwner() }
        composeRule.waitForIdle()
        assertEquals(0, fixture.handoffs)
        composeRule.runOnIdle { fixture.resume() }
        composeRule.waitForIdle()
        assertFalse(old.foregroundRestoreInProgress)
        assertTrue(fixture.current.foregroundRestoreInProgress)
        assertFalse(fixture.safeAtLastRestore)
        assertEquals(2, fixture.realResumeEffects)
        assertEquals(0, fixture.handoffs)
    }

    @Test
    fun replacementComposedAfterResumeTransfersGateWithoutReplayingResumeEffects() {
        val fixture = Fixture()
        composeRule.setContent { fixture.Content() }
        val old = fixture.current
        // One uninterrupted main-thread turn: the old composition receives ON_RESUME.
        composeRule.runOnIdle {
            fixture.pause()
            fixture.current = fixture.newOwner()
            fixture.resume()
        }
        composeRule.waitForIdle()
        assertFalse(old.foregroundRestoreInProgress)
        assertTrue(fixture.current.foregroundRestoreInProgress)
        assertFalse(fixture.safeAtLastRestore)
        assertEquals(1, fixture.handoffs)
        assertEquals(2, fixture.realResumeEffects)
    }

    @Test
    fun trueRouteTeardownCancelsTransferredGateAndDropsBinding() {
        val fixture = Fixture()
        composeRule.setContent { fixture.Content() }
        composeRule.runOnIdle {
            fixture.pause()
            fixture.current = fixture.newOwner()
            fixture.resume()
        }
        composeRule.waitForIdle()
        val transferred = fixture.current
        assertTrue(transferred.foregroundRestoreInProgress)
        composeRule.runOnIdle { fixture.mounted = false }
        composeRule.waitForIdle()
        assertFalse(transferred.foregroundRestoreInProgress)
        assertTrue(fixture.retained == null)
        assertEquals(1, fixture.disposals)
    }

    private class Fixture : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        var current by mutableStateOf(newOwner())
        var retained by mutableStateOf<ConversationForegroundRestoreBinding?>(null)
        var mounted by mutableStateOf(true)
        var realResumeEffects = 0
        var handoffs = 0
        var disposals = 0
        var safeAtLastRestore = true

        fun newOwner() = ConversationScrollCoordinator(Writer())

        fun pause() {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        @Composable
        fun Content() {
            if (!mounted) return
            val composedOwner = current

            fun restore() {
                val paused = retained
                retained = paused?.resume(composedOwner) { snapshot(composedOwner) }
                safeAtLastRestore =
                    ConversationForegroundRestoreBinding.canPresentRetainedFrame(paused, composedOwner, true)
            }
            conversationForegroundOwnerHandoffEffect(composedOwner, retained, this) {
                handoffs++
                restore()
            }
            ConversationComposerLifecycleEffect(
                observerKey = this,
                lifecycleOwner = this,
                composerFocused = false,
                searchOpen = false,
                hasActiveEditOrReplySession = false,
                onPause = {
                    retained = ConversationForegroundRestoreBinding.begin(composedOwner, snapshot(composedOwner))
                },
                onResume = { _, _ ->
                    realResumeEffects++
                    restore()
                },
                onObserverDisposed = {
                    retained?.cancel()
                    retained = null
                    disposals++
                },
            )
        }

        private fun snapshot(owner: ConversationScrollCoordinator) =
            ConversationForegroundSnapshot(
                owner.bookmark(ConversationScrollAnchor(0, 0, null, null)),
                ConversationForegroundGeometry(720, 0, 96),
            )
    }

    private class Writer : ConversationScrollWriter {
        override val firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = Unit

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = Unit
    }
}
