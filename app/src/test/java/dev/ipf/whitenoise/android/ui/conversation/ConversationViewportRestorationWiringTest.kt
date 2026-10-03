package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationViewportRestorationWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sameGroupAccountOrControllerReplacementDisposesOnlyTheOldOwner() {
        val identity = mutableStateOf<Any>(Any())
        val revision = mutableStateOf(0)
        val coordinator = ConversationScrollCoordinator(NoopWriter())
        val gate = ConversationPostInitialReanchorGate()
        val observed = arrayOfNulls<ConversationViewportRestorationOwner>(1)
        composeRule.setContent {
            revision.value
            observed[0] = rememberConversationViewportRestorationOwner(identity.value, coordinator, gate)
        }
        composeRule.waitForIdle()
        val first = requireNotNull(observed[0])
        composeRule.runOnUiThread { revision.value++ }
        composeRule.waitForIdle()
        assertSame(first, observed[0])
        composeRule.runOnUiThread { identity.value = Any() }
        composeRule.waitForIdle()
        val second = requireNotNull(observed[0])
        assertNotSame(first, second)
        assertFalse(first.isActive)
        assertTrue(second.isActive)
        composeRule.runOnUiThread { identity.value = Any() }
        composeRule.waitForIdle()
        assertFalse(second.isActive)
        assertTrue(requireNotNull(observed[0]).isActive)
    }

    @Test
    fun leavingTheScreenInvalidatesLateCompletions() {
        val shown = mutableStateOf(true)
        val coordinator = ConversationScrollCoordinator(NoopWriter())
        val gate = ConversationPostInitialReanchorGate()
        val observed = arrayOfNulls<ConversationViewportRestorationOwner>(1)
        composeRule.setContent {
            if (shown.value) observed[0] = rememberConversationViewportRestorationOwner(this, coordinator, gate)
        }
        composeRule.waitForIdle()
        val owner = requireNotNull(observed[0])
        composeRule.runOnUiThread { shown.value = false }
        composeRule.waitForIdle()
        assertFalse(owner.isActive)
        val position = requireNotNull(conversationViewportEntryPosition(listOf("item" to "message"), null, 1))
        val structure = ConversationTimelineStructure(listOf("item" to "message"), 0)
        assertFalse(owner.completeInitialPosition(position, structure, 720))
    }

    private class NoopWriter : ConversationScrollWriter {
        override val firstVisibleItemIndex = 0

        override suspend fun scrollToItem(index: Int, scrollOffset: Int) = Unit

        override suspend fun animateScrollToItem(index: Int, scrollOffset: Int) = Unit
    }
}
