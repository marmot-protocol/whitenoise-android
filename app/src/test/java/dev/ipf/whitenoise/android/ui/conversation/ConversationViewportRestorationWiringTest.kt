package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.notifiedMessagePreview
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun savedEffectWaitsForSeedThenWinsOverUnreadAndDoesNotReplayOnOwnerReplacement() {
        val fixture = EffectFixture()
        try {
            setEffectContent(fixture)
            composeRule.waitForIdle()
            assertTrue(fixture.writer.writes.isEmpty())
            composeRule.runOnUiThread { fixture.publishAuthoritativePage() }
            composeRule.waitForIdle()
            assertEquals(listOf(1 to 23, 1 to 23), fixture.writer.writes)
            assertEquals(1, fixture.completions)
            assertTrue(fixture.anchored.value)
            assertEquals(ConversationScrollMode.ReadingHistory("2".repeat(64), 23), fixture.coordinator.value.mode)
            composeRule.runOnUiThread {
                fixture.coordinator.value = ConversationScrollCoordinator(fixture.writer)
            }
            composeRule.waitForIdle()
            assertEquals(2, fixture.writer.writes.size)
            assertEquals(1, fixture.completions)
            assertEquals(0, fixture.scripted.timelineSubscriptionOpenCount)
        } finally {
            fixture.controller.onCleared()
        }
    }

    @Test
    fun disposedSavedEffectCannotCompleteWhenAuthoritativePageArrivesLate() {
        val fixture = EffectFixture()
        try {
            setEffectContent(fixture)
            composeRule.waitForIdle()
            assertTrue(fixture.writer.writes.isEmpty())
            composeRule.runOnUiThread { fixture.shown.value = false }
            composeRule.waitForIdle()
            composeRule.runOnUiThread { fixture.publishAuthoritativePage() }
            composeRule.waitForIdle()
            assertTrue(fixture.writer.writes.isEmpty())
            assertFalse(fixture.anchored.value)
            assertEquals(0, fixture.completions)
            assertEquals(0, fixture.scripted.timelineSubscriptionOpenCount)
        } finally {
            fixture.controller.onCleared()
        }
    }

    private fun setEffectContent(fixture: EffectFixture) {
        composeRule.setContent {
            if (fixture.shown.value) {
                val list = rememberLazyListState()
                val viewport = remember(list) { ConversationTimelineViewport(list) }
                val owner =
                    rememberConversationViewportRestorationOwner(
                        fixture.controller,
                        fixture.coordinator.value,
                        fixture.gate,
                    )
                LazyColumn(state = list, modifier = Modifier.height(240.dp)) {
                    items(fixture.controller.timeline.size) { Text("row-$it", Modifier.height(40.dp)) }
                }
                ConversationViewportRestorationEffects(
                    fixture.controller,
                    viewport,
                    owner,
                    fixture.inputs(),
                    ConversationViewportRestorationCallbacks(
                        navigation = ConversationViewportNavigation({ 1 }) { 0 },
                        onAnchored = {
                            fixture.completions++
                            fixture.anchored.value = true
                        },
                        retireUnreadDivider = { error("Saved restore must take precedence over unread entry") },
                    ),
                )
            }
        }
    }

    private class EffectFixture {
        val scripted = ScriptedConversationLiveSubscriptions(emptyList(), conversationTimelineTestGroup())
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = conversationTimelineTestGroup(),
                initialTimelinePreview = notifiedMessagePreview(),
                startOnConstruction = false,
            )
        val writer = RecordingWriter()
        val coordinator = mutableStateOf(ConversationScrollCoordinator(writer))
        val gate = ConversationPostInitialReanchorGate()
        val anchored = mutableStateOf(false)
        val shown = mutableStateOf(true)
        var completions = 0

        fun inputs() =
            ConversationViewportRestorationInputs(
                scrollRestore = ConversationScrollSnapshot(1, 23),
                presentation = ConversationViewportPresentation(anchored.value, false),
                structure = controller.conversationTimelineStructure(),
                entryUnread = ConversationEntryUnreadSnapshot(3, "1".repeat(64)),
                entryProjectionAvailable = true,
                notificationOpenRequestId = 0,
                seedTailAwaitingAuthoritative = false,
            )

        fun publishAuthoritativePage() =
            runBlocking {
                val records = (1..3).map { timelineRecord(it.toString().repeat(64), it.toULong()) }
                controller.applyTimelinePage(
                    page = timelinePage(*records.toTypedArray()),
                    replaceWindow = true,
                    updatePagination = true,
                )
            }
    }

    private class RecordingWriter : ConversationScrollWriter {
        override var firstVisibleItemIndex = 0
        val writes = mutableListOf<Pair<Int, Int>>()

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            writes += index to scrollOffset
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = scrollToItem(index, scrollOffset)
    }

    private class NoopWriter : ConversationScrollWriter {
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
