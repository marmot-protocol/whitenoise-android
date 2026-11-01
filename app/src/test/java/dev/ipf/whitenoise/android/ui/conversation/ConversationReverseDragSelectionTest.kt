package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Covers hold-drag selection through the production reversed conversation list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ConversationReverseDragSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var viewportBounds = Rect.Zero
    private var lastPointer = Offset.Zero
    private lateinit var edgeEvidence: EdgeEvidence
    private lateinit var edgeController: ConversationController
    private lateinit var edgeSubscription: ScriptedConversationTimelineSubscription

    /** A top-edge hold selects older rows and settles at the natural oldest boundary before capture. */
    @Test
    fun topEdgeHoldFromNewestRevealsOlderRowsAndExtendsSelection() {
        withScrollableConversation { surface, evidence, ids ->
            val initial = evidence.latest()
            val anchor = ids.last()
            holdAtEdge(anchor, top = true)
            val older = evidence.latest()
            assertTrue("top must reveal older rows", older.anchor.listIndex > initial.anchor.listIndex)
            assertSelectionAtPointer(surface, ids, anchor)
            repeat(160) {
                if (evidence.latest().canScrollForward) advanceFrames(1)
            }
            assertTrue("capture must reach the oldest loaded window", !evidence.latest().canScrollForward)
            assertSelectionAtPointer(surface, ids, anchor)
            releaseDrag()
            assertRange(surface, ids, anchor)
            composeRule
                .onNodeWithTag("reverse-drag-conversation-host")
                .captureRoboImage("src/test/snapshots/conversation_top_edge_drag_selection.png")
        }
    }

    /** A middle-window bottom hold reveals newer rows; reversing across the anchor replaces the range. */
    @Test
    fun bottomEdgeHoldFromMiddleRevealsNewerRowsAndReversesAcrossAnchor() {
        withScrollableConversation { surface, evidence, ids ->
            transcript().performScrollToIndex(24)
            composeRule.waitForIdle()
            val initial = evidence.latest()
            val anchor =
                initial.visibleItems
                    .map { it.key.removePrefix("msg:") }
                    .filter(ids::contains)
                    .let { it[it.size / 2] }
            holdAtEdge(anchor, top = false)
            assertTrue("bottom must reveal newer rows", evidence.latest().anchor.listIndex < initial.anchor.listIndex)
            assertSelectionAtPointer(surface, ids, anchor)
            val newerSelected = surface.selectedMessages.keys.toSet() - anchor
            assertTrue("bottom hold must select newer rows", newerSelected.isNotEmpty())
            moveToEdge(top = true)
            advanceFrames(150)
            assertTrue(
                "reversal must cross the anchor toward older rows",
                evidence.latest().anchor.listIndex > initial.anchor.listIndex,
            )
            assertSelectionAtPointer(surface, ids, anchor)
            releaseDrag()
            assertRange(surface, ids, anchor)
            composeRule
                .onNodeWithTag("reverse-drag-conversation-host")
                .captureRoboImage("src/test/snapshots/conversation_reversed_edge_drag_selection.png")
            assertTrue(
                "the previous side of the anchor must be deselected",
                newerSelected.intersect(surface.selectedMessages.keys).isEmpty(),
            )
        }
    }

    /** Continued edge frames at either natural bound stop without changing the settled selected range. */
    @Test
    fun naturalHistoryBoundsAndReleaseStopAutoScroll() {
        withScrollableConversation { surface, evidence, ids ->
            holdAtEdge(ids.last(), top = true, frames = 250)
            val oldest = evidence.latest()
            assertTrue("all loaded history must be reachable", !oldest.canScrollForward)
            val atOldest = surface.selectedMessages.keys.toSet()
            val selectionWrites = AtomicInteger(0)
            val observer =
                Snapshot.registerApplyObserver { changed, _ ->
                    if (changed.any { it === surface.selectedMessages }) selectionWrites.incrementAndGet()
                }
            try {
                advanceFrames(20)
            } finally {
                observer.dispose()
            }
            assertEquals("stationary range must not rewrite selection each frame", 0, selectionWrites.get())
            assertEquals(oldest.anchor, evidence.latest().anchor)
            assertEquals(atOldest, surface.selectedMessages.keys.toSet())
            moveToEdge(top = false)
            advanceFrames(250)
            assertTrue("newest tail must be reachable", !evidence.latest().canScrollBackward)
            releaseDrag()
            val released = evidence.latest().anchor
            val selected = surface.selectedMessages.keys.toSet()
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.waitForIdle()
            assertEquals(released, evidence.latest().anchor)
            assertEquals(selected, surface.selectedMessages.keys.toSet())
            composeRule
                .onNodeWithTag("reverse-drag-conversation-host")
                .captureRoboImage("src/test/snapshots/conversation_tail_edge_drag_selection.png")
        }
    }

    /** A new range gesture renews quiet paging and admits a subscription-delivered older row while held. */
    @Test
    fun topEdgeHoldRenewsBlockedOlderPrefetch() {
        withScrollableConversation(olderPageAvailable = true) { surface, _, ids ->
            composeRule.runOnUiThread { edgeController.automaticPaging.older.recordFailure() }
            composeRule.runOnIdle {
                assertTrue(edgeController.automaticOlderPagingBlocked)
                assertEquals(0, edgeSubscription.backwardsCallCount)
            }
            holdAtEdge(ids.last(), top = true, frames = 250)
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                edgeSubscription.backwardsCallCount > 0 &&
                    edgeController.timeline.any { it.record.messageIdHex == "ab".repeat(32) }
            }
            advanceFrames(30)
            assertEquals("one renewed paging request reaches the subscription", 1, edgeSubscription.backwardsCallCount)
            assertTrue("the stationary range includes the new row", "ab".repeat(32) in surface.selectedMessages)
            assertSelectionAtPointer(surface, listOf("ab".repeat(32)) + ids, ids.last())
            releaseDrag()
        }
    }

    /** Loaded older rows join a stationary edge range, and removing its anchor cancels the frame loop. */
    @Test
    @Config(qualifiers = "en-w360dp-h420dp-mdpi")
    fun olderPageWhileHeldExtendsTheRangeAndAnchorRemovalStopsScrolling() {
        withScrollableConversation { surface, evidence, ids ->
            val controller = edgeController
            val anchor = ids.last()
            holdAtEdge(anchor, top = true, frames = 250)
            assertTrue(!evidence.latest().canScrollForward)
            val older = timelineRecord("ab".repeat(32), 0uL, "Newly loaded older message")
            val initialRecords =
                ids.mapIndexed { index, id -> timelineRecord(id, (index + 1).toULong(), "edge message ${index + 1}") }
            composeRule.runOnUiThread {
                runBlocking {
                    controller.applyTimelinePage(
                        timelinePage(older, *initialRecords.toTypedArray()),
                        replaceWindow = false,
                        updatePagination = true,
                    )
                }
            }
            advanceFrames(30)
            assertTrue(controller.timeline.any { it.record.messageIdHex == older.messageIdHex })
            assertTrue("stationary pointer must include loaded history", older.messageIdHex in surface.selectedMessages)
            assertSelectionAtPointer(surface, listOf(older.messageIdHex) + ids, anchor)
            composeRule
                .onNodeWithTag("reverse-drag-conversation-host")
                .captureRoboImage("src/test/snapshots/conversation_loaded_edge_drag_selection.png")
            composeRule.runOnUiThread {
                runBlocking {
                    controller.applyTimelinePage(
                        timelinePage(older, *initialRecords.filterNot { it.messageIdHex == anchor }.toTypedArray()),
                        replaceWindow = true,
                        updatePagination = true,
                    )
                }
            }
            advanceFrames(5)
            assertTrue("removing the active anchor must retire selection", surface.selectedMessages.isEmpty())
            val retired = evidence.latest().anchor
            advanceFrames(30)
            assertEquals(retired, evidence.latest().anchor)
            releaseDrag()
            assertTrue(surface.selectedMessages.isEmpty())
        }
    }

    /** Mounts a full-height real route with more rows than fit, using the production viewport observer. */
    @Suppress("LongMethod") // Controller, page, and Compose lifetime must be retired by one fixture owner.
    private fun withScrollableConversation(
        olderPageAvailable: Boolean = false,
        block: (ConversationSurfaceState, EdgeEvidence, List<String>) -> Unit,
    ) {
        val group = conversationTimelineTestGroup()
        val members = conversationTimelineMemberSnapshot()
        val records =
            (1..48).map { index ->
                timelineRecord(
                    messageId = index.toString(16).padStart(64, '0'),
                    timelineAt = index.toULong(),
                    plaintext = "edge message $index",
                )
            }
        val subscription =
            ScriptedConversationTimelineSubscription(
                snapshotPage = timelinePage(*records.toTypedArray()).copy(hasMoreBefore = olderPageAvailable),
                backwardsPage =
                    timelinePage(
                        timelineRecord("ab".repeat(32), 0uL, "Subscription-delivered older message"),
                        *records.toTypedArray(),
                    ),
            )
        edgeSubscription = subscription
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val appState = conversationTimelineTestAppState(scripted.subscriptions)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = members,
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                groupRecoveryStatusReader = { _, groupId ->
                    GroupRecoveryStatusFfi(
                        groupIdHex = groupId,
                        automaticRecoveryFailed = false,
                        pendingReinvites = 0u,
                        failedReinvites = 0u,
                        rejoinInvitations = emptyList(),
                    )
                },
                startOnConstruction = true,
            )
        edgeController = controller
        val surface = ConversationSurfaceState()
        val evidence = EdgeEvidence()
        edgeEvidence = evidence
        val app = ApplicationProvider.getApplicationContext<Context>()
        val originalZone = TimeZone.getDefault()
        val originalFormat = Settings.System.getString(app.contentResolver, Settings.System.TIME_12_24)
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
            awaitConversationCondition { controller.timeline.size == records.size }
            val chat = ChatListItem(group, null, null, 1, memberSnapshot = members)
            composeRule.setContent {
                CompositionLocalProvider(LocalConversationScrollEvidenceSink provides evidence) {
                    WhiteNoiseTheme {
                        Box(Modifier.fillMaxSize().testTag("reverse-drag-conversation-host")) {
                            ConversationScreen(
                                appState = appState,
                                chat = chat,
                                controller = controller,
                                onBack = {},
                                surfaceState = surface,
                                restoredScrollSnapshot =
                                    ConversationScrollSnapshot(
                                        firstVisibleItemIndex = 0,
                                        firstVisibleItemScrollOffset = 0,
                                        anchorItemId = "msg:${records.last().messageIdHex}",
                                        anchorMessageIdHex = records.last().messageIdHex,
                                    ),
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            viewportBounds = transcript().fetchSemanticsNode().boundsInRoot
            block(surface, evidence, records.map { it.messageIdHex })
        } finally {
            composeRule.mainClock.autoAdvance = true
            try {
                controller.onCleared()
            } finally {
                try {
                    Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, originalFormat)
                } finally {
                    TimeZone.setDefault(originalZone)
                }
            }
        }
    }

    /** Starts the real long hold, then leaves the pointer stationary while production frame callbacks scroll. */
    private fun holdAtEdge(
        anchor: String,
        top: Boolean,
        frames: Int = 90,
    ) {
        val point =
            composeRule
                .onNodeWithTag(messageBubbleRowTestTag(anchor))
                .fetchSemanticsNode()
                .boundsInRoot.center
        composeRule.onNodeWithTag("reverse-drag-conversation-host").performTouchInput {
            down(point)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            move()
        }
        composeRule.mainClock.autoAdvance = false
        moveToEdge(top)
        advanceFrames(frames)
    }

    /** Uses the actual measured list bounds, above the selection/composer chrome, for physical edge input. */
    private fun moveToEdge(top: Boolean) {
        lastPointer =
            Offset(
                viewportBounds.center.x,
                if (top) viewportBounds.top + 8f else viewportBounds.top + edgeEvidence.latest().viewportHeightPx - 8f,
            )
        composeRule.onNodeWithTag("reverse-drag-conversation-host").performTouchInput { moveTo(lastPointer) }
    }

    /** Advances real Compose frame callbacks without another pointer move. */
    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    /** Releases the original pointer and allows normal idle synchronization again. */
    private fun releaseDrag() {
        composeRule.onNodeWithTag("reverse-drag-conversation-host").performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    /** Resolves the endpoint from physical row bounds, independently of the production reversed-offset helper. */
    private fun assertSelectionAtPointer(
        surface: ConversationSurfaceState,
        ids: List<String>,
        anchor: String,
    ) {
        val rows =
            edgeEvidence
                .latest()
                .visibleItems
                .map { it.key.removePrefix("msg:") }
                .filter(ids::contains)
        val endpoint =
            rows.minBy { id ->
                val bounds = composeRule.onNodeWithTag(messageBubbleRowTestTag(id)).fetchSemanticsNode().boundsInRoot
                when {
                    lastPointer.y < bounds.top -> bounds.top - lastPointer.y
                    lastPointer.y > bounds.bottom -> lastPointer.y - bounds.bottom
                    else -> 0f
                }
            }
        val anchorIndex = ids.indexOf(anchor)
        val endIndex = ids.indexOf(endpoint)
        val range = minOf(anchorIndex, endIndex)..maxOf(anchorIndex, endIndex)
        assertEquals(
            "stationary pointer must select through the current physical edge row",
            range.map(ids::get).toSet(),
            surface.selectedMessages.keys.toSet(),
        )
    }

    /** The selected IDs must remain one inclusive eligible interval containing the original anchor. */
    private fun assertRange(
        surface: ConversationSurfaceState,
        ids: List<String>,
        anchor: String,
    ) {
        val selected = surface.selectedMessages.keys.toSet()
        assertTrue(selected.contains(anchor))
        val indices = selected.map(ids::indexOf)
        assertEquals(ids.subList(indices.min(), indices.max() + 1).toSet(), selected)
    }

    /** Resolves the production list, whose semantics expose its real scroll position and bounds. */
    private fun transcript(): SemanticsNodeInteraction =
        composeRule
            .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE, useUnmergedTree = true)

    /** Records actual post-layout positions without adding a production scroll-control seam. */
    private class EdgeEvidence : ConversationScrollEvidenceSink {
        private val samples = CopyOnWriteArrayList<ConversationViewportEvidence>()

        /** Captures each observed viewport in test memory only. */
        override fun onViewport(snapshot: ConversationViewportEvidence) {
            samples += snapshot
        }

        /** The list writer is not used to simulate any selection edge scroll. */
        override fun onWrite(write: ConversationScrollWriteEvidence) = Unit

        /** Returns the latest measured viewport after explicit frame advancement. */
        fun latest(): ConversationViewportEvidence = samples.last()
    }

    /** An upward drag keeps the anchor and every crossed message selected after release. */
    @Test
    @Suppress("LongMethod") // The real screen requires its controller, timeline, and composition owners together.
    fun draggingFromLowerMessageToHigherMessageSelectsTheProductionRange() {
        val group = conversationTimelineTestGroup()
        val members = conversationTimelineMemberSnapshot()
        val records =
            (1..4).map { index ->
                timelineRecord(
                    messageId = index.toString().repeat(64),
                    timelineAt = index.toULong(),
                    plaintext = "message-$index",
                )
            }
        val subscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val scripted = ScriptedConversationLiveSubscriptions(listOf(subscription), group)
        val appState = conversationTimelineTestAppState(scripted.subscriptions)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialMemberSnapshot = members,
                startOnConstruction = false,
            )
        val surfaceState = ConversationSurfaceState()
        try {
            composeRule.runOnUiThread {
                runBlocking {
                    controller.applyTimelinePage(
                        page = timelinePage(*records.toTypedArray()),
                        replaceWindow = true,
                        updatePagination = true,
                    )
                }
                controller.markAuthoritativeTimelinePublishedForTest()
            }
            val chat =
                ChatListItem(
                    group = group,
                    latest = null,
                    otherMemberAccount = null,
                    memberCount = 1,
                    memberSnapshot = members,
                )
            composeRule.setContent {
                WhiteNoiseTheme {
                    Box(Modifier.fillMaxSize().testTag("reverse-drag-conversation-host")) {
                        ConversationScreen(
                            appState = appState,
                            chat = chat,
                            controller = controller,
                            onBack = {},
                            surfaceState = surfaceState,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            val lowerId = records[3].messageIdHex
            val higherId = records[1].messageIdHex
            val middleId = records[2].messageIdHex
            val lower =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(lowerId))
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val higher =
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(higherId))
                    .fetchSemanticsNode()
                    .boundsInRoot.center
            val root = composeRule.onNodeWithTag("reverse-drag-conversation-host")
            root.performTouchInput {
                down(lower)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
                move()
            }
            root.performTouchInput {
                moveTo(higher)
                up()
            }
            composeRule.runOnIdle {
                assertEquals(setOf(lowerId, middleId, higherId), surfaceState.selectedMessages.keys.toSet())
            }
        } finally {
            controller.onCleared()
        }
    }
}
