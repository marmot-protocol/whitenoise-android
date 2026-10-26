package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_FULL_SCREEN_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowView
import org.robolectric.util.ReflectionHelpers
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/** Real route coverage: the composer callback must snap, not animate, an accepted send from history. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationAcceptedSendRouteScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var controller: ConversationController? = null
    private var publishCount = 0
    private val originalTimeZone = TimeZone.getDefault()

    /** Releases every conversation-owned job and restores process-wide timestamp formatting. */
    @After
    fun releaseFixture() {
        controller?.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    /** Real tail sends must paint intermediate geometry. */
    @Test
    fun acceptedSendAtLiveTailPaintsAnIntermediateFlight() = assertLiveTailFlight("Short send", 0)

    /** Multiline contraction uses the actual transcript viewport. */
    @Test
    fun acceptedMultilineSendWithKeyboardClearancePaintsAnIntermediateFlight() {
        assertLiveTailFlight("First line\nSecond line\nThird line", 240)
    }

    /** Disabling motion during a real accepted send immediately retires its decorative copy. */
    @Test
    fun liveSystemDisableSettlesTheAcceptedSendRoute() = assertLiveTailFlight("Live suppression", 0, suppressDuringFlight = true)

    /** A measured target can retire while motion remains enabled; remaining composer geometry must snap. */
    @Test
    fun retiredTargetSnapsAnAlreadyPaintedSendAndKeepsTheNextDraft() =
        assertLiveTailFlight("First line\nSecond line\nThird line", 240, retireDuringFlight = true)

    private fun retiredPillInternalGeometry(): Pair<androidx.compose.ui.geometry.Rect, List<androidx.compose.ui.graphics.Color>> {
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
        val surfaceBounds = surface.fetchSemanticsNode().boundsInRoot
        val editorBounds =
            composeRule
                .onNodeWithTag(
                    dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_EDITOR_VIEWPORT_TAG,
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
                .translate(-surfaceBounds.topLeft)
        val pixels = surface.captureToImage().toPixelMap()
        val corners =
            buildList {
                for (y in 0 until minOf(16, pixels.height)) {
                    for (x in 0 until minOf(24, pixels.width)) {
                        add(pixels[x, y])
                        add(pixels[pixels.width - 1 - x, y])
                    }
                }
            }
        return editorBounds to corners
    }

    private class LiveTailFrames {
        val painted = CopyOnWriteArrayList<ComposerSendFlightGeometry>()
        var motionOwner: ComposerSendMotion? = null
        val boundaries = mutableListOf<String>()
        var earlyRetiredHeight: Float? = null
        var earlyRetiredInternalGeometry: Pair<androidx.compose.ui.geometry.Rect, List<androidx.compose.ui.graphics.Color>>? = null
    }

    private fun advanceLiveTailFrames(
        frames: LiveTailFrames,
        resolver: android.content.ContentResolver,
        suppressDuringFlight: Boolean,
        retireDuringFlight: Boolean,
    ) {
        repeat(30) {
            if (suppressDuringFlight && it == 6) {
                assertTrue("the production flight must be active before suppression", frames.motionOwner?.flight != null)
                assertTrue("suppression must follow a genuinely painted moving frame", frames.painted.any { it.progress > 0f && it.progress < 1f })
                composeRule.runOnIdle {
                    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                    resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
                }
            }
            if (retireDuringFlight && it == 6) {
                assertTrue("retirement must follow a genuinely painted flight", frames.painted.any { it.progress > 0f && it.progress < 1f })
                composeRule.runOnIdle { checkNotNull(frames.motionOwner).clear("test-destination-retired") }
                assertTrue("this fallback must not depend on disabling motion", frames.motionOwner?.enabled == true)
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            if (retireDuringFlight && it == 8) {
                frames.earlyRetiredInternalGeometry = retiredPillInternalGeometry()
                frames.earlyRetiredHeight =
                    composeRule
                        .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                        .fetchSemanticsNode()
                        .boundsInRoot.height
            }
            frames.boundaries += "frame-$it ${frames.motionOwner?.diagnosticState}"
        }
    }

    private fun assertLiveTailResult(
        conversation: ConversationController,
        text: String,
        frames: LiveTailFrames,
        suppressDuringFlight: Boolean,
        retireDuringFlight: Boolean,
    ) {
        assertTrue(
            "a real tail send must paint moving geometry, not merely create a flight" +
                " (painted frames=${frames.painted.size}; owner boundaries=${frames.boundaries})",
            frames.painted.any {
                it.progress > 0f && it.progress < 1f && !motionRectsMatch(it.bounds, it.canonicalFrame)
            },
        )
        if (suppressDuringFlight || retireDuringFlight) assertEquals(null, frames.motionOwner?.flight)
        if (retireDuringFlight) {
            val settledHeight =
                composeRule
                    .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            assertEquals("retiring the flight must snap the remaining composer geometry", settledHeight, checkNotNull(frames.earlyRetiredHeight), 1f)
            assertEquals(
                "inner editor insets and painted pill corners must already be settled",
                retiredPillInternalGeometry(),
                checkNotNull(frames.earlyRetiredInternalGeometry),
            )
        }
        assertEquals(1, publishCount)
        assertEquals(1, conversation.timeline.count { it.record.plaintext == text })
        composeRule.onNodeWithText(text).assertIsDisplayed()
        composeRule.onNodeWithTag("conversation.send.motion.active").assertDoesNotExist()
        if (retireDuringFlight) {
            composeRule.onNode(hasSetTextAction()).performTextInput("Next draft")
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText("Next draft").assertIsDisplayed()
        }
    }

    private fun assertLiveTailFlight(
        text: String,
        keyboardClearanceDp: Int,
        suppressDuringFlight: Boolean = false,
        retireDuringFlight: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val previousScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val previousRealDrawTraversals =
            ReflectionHelpers.callStaticMethod<Boolean>(ShadowView::class.java, "areRealDrawTraversalsEnabled")
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            // Native graphics alone does not run real draw traversals on this API 36 fixture.
            // Observe the production Canvas through actual frames, not idle/layout callbacks alone.
            ShadowView.setUseRealDrawTraversals(true)
            val conversation = createConversation(draftText = text)
            val evidence = SendRouteEvidence()
            val frames = LiveTailFrames()
            mountHistoryConversation(
                conversation,
                evidence,
                liveTail = true,
                keyboardClearanceDp = keyboardClearanceDp,
                onPaintedGeometry = { frames.painted.add(it) },
                onMotionOwner = { frames.motionOwner = it },
            )
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.FollowingTail }
            // FollowingTail is the initial navigation intent, before reveal/layout gates have settled.
            // Keep driving frames until the real route admits motion, then freeze the send itself.
            composeRule.waitUntil(5_000) { frames.motionOwner?.enabled == true }
            frames.boundaries += "before-send ${frames.motionOwner?.diagnosticState}"
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            awaitConversationCondition { publishCount == 1 }
            frames.boundaries += "accepted ${frames.motionOwner?.diagnosticState}"
            advanceLiveTailFrames(frames, resolver, suppressDuringFlight, retireDuringFlight)
            assertLiveTailResult(conversation, text, frames, suppressDuringFlight, retireDuringFlight)
        } finally {
            ShadowView.setUseRealDrawTraversals(previousRealDrawTraversals)
            Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
    }

    /** Exercises the production Send button and measures the pending bubble above the composer. */
    @Test
    fun acceptedSendFromFarHistorySnapsToThePendingTail() {
        val conversation = createConversation()
        val appState = conversation.appState
        val evidence = SendRouteEvidence()
        mountHistoryConversation(conversation, evidence)
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.ReadingHistory }
        assertTrue(checkNotNull(evidence.viewport).canScrollBackward)
        evidence.writes.clear()
        composeRule.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        awaitConversationCondition { publishCount == 1 }
        repeat(30) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(SENT_TEXT).assertIsDisplayed()
        val sentId =
            conversation.timeline
                .single { it.record.plaintext == SENT_TEXT }
                .record.messageIdHex
        val bubbleBounds =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(sentId), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val composerBounds = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the complete pending bubble clears the composer", bubbleBounds.bottom <= composerBounds.top)
        assertEquals(1, publishCount)
        assertTrue(evidence.writes.isNotEmpty())
        assertTrue("accepted sends never animate across history", evidence.writes.none { it.animated })
        assertFalse("the newest edge is physically reached", checkNotNull(evidence.viewport).canScrollBackward)
        assertTrue(checkNotNull(evidence.viewport).mode is ConversationScrollMode.FollowingTail)
        assertEquals(
            "acceptance clears only the origin draft",
            null,
            appState.draftStore.get(ConversationTimelineTestIds.ACCOUNT_REF, ConversationTimelineTestIds.GROUP_ID),
        )
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_accepted_send_history_tail.png")
    }

    /**
     * Sending from a long message's Read more reader closes the reader once the send is accepted and reveals the
     * pending bubble above the full conversation's composer, through the same snap the main composer uses (#3268).
     */
    @Test
    fun acceptedSendFromReadMoreReaderClosesItAndRevealsThePendingTail() {
        val conversation = createConversation(longHistoryMessage = true)
        val evidence = SendRouteEvidence()
        mountHistoryConversation(conversation, evidence)
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.ReadingHistory }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The collapsed bubble also lays out a zero-size measurement copy of Read More; tap the visible one.
        val readMoreId =
            composeRule
                .onAllNodesWithText(context.getString(R.string.message_read_more))
                .fetchSemanticsNodes()
                .first { it.boundsInRoot.width > 0f }
                .id
        composeRule.onNode(SemanticsMatcher("visible Read More") { it.id == readMoreId }).performClick()
        composeRule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).assertIsDisplayed()
        evidence.writes.clear()

        composeRule.mainClock.autoAdvance = false
        composeRule
            .onNode(
                hasContentDescription(context.getString(R.string.send)) and
                    hasAnyAncestor(hasTestTag(MESSAGE_FULL_SCREEN_TAG)),
            ).performClick()
        awaitConversationCondition { publishCount == 1 }
        repeat(30) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).assertDoesNotExist()
        val sentId =
            conversation.timeline
                .single { it.record.plaintext == SENT_TEXT }
                .record.messageIdHex
        val bubbleBounds =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(sentId), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val composerBounds = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the pending bubble clears the conversation composer", bubbleBounds.bottom <= composerBounds.top)
        assertEquals("the reader send publishes exactly once", 1, publishCount)
        assertTrue("accepted reader sends never animate across history", evidence.writes.none { it.animated })
        assertTrue(checkNotNull(evidence.viewport).mode is ConversationScrollMode.FollowingTail)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_accepted_send_read_more_tail.png")
    }

    /**
     * Builds one synthetic authoritative window with typed durable acceptance and no relay publication. With
     * [longHistoryMessage], the restored history anchor is long enough to collapse behind Read more.
     */
    private fun createConversation(
        longHistoryMessage: Boolean = false,
        draftText: String = SENT_TEXT,
    ): ConversationController {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val records =
            List(60) { index ->
                val text =
                    if (longHistoryMessage && index == HISTORY_ANCHOR_INDEX) {
                        List(LONG_MESSAGE_LINES) { line -> "Long history line $line" }.joinToString("\n")
                    } else {
                        "History $index"
                    }
                timelineRecord((index + 1).toString(16).padStart(64, '0'), (index + 1).toULong(), text)
            }
        val subscriptions =
            ScriptedConversationLiveSubscriptions(
                listOf(ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))),
                conversationTimelineTestGroup(),
            )
        val appState = conversationTimelineTestAppState(subscriptions.subscriptions)
        appState.draftStore.set(
            ConversationTimelineTestIds.ACCOUNT_REF,
            ConversationTimelineTestIds.GROUP_ID,
            TextFieldValue(draftText),
        )
        val conversation =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
                markdownParser = { EMPTY_MARKDOWN_DOCUMENT },
                clockMillis = { 1_672_531_260_000L },
                textPublisher = { _, _, _, _ ->
                    publishCount++
                    SendSummaryFfi(
                        published = 0u,
                        messageIds = listOf("c3".repeat(32)),
                        acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                },
            )
        controller = conversation
        awaitConversationCondition { conversation.timeline.size == records.size }
        return conversation
    }

    /** Mounts the real route with a logical far-history bookmark and the actual production scroll writer. */
    private fun mountHistoryConversation(
        conversation: ConversationController,
        evidence: SendRouteEvidence,
        liveTail: Boolean = false,
        keyboardClearanceDp: Int = 0,
        onPaintedGeometry: ((ComposerSendFlightGeometry) -> Unit)? = null,
        onMotionOwner: ((ComposerSendMotion) -> Unit)? = null,
    ) {
        val historyId = (HISTORY_ANCHOR_INDEX + 1).toString(16).padStart(64, '0')
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConversationScrollEvidenceSink provides evidence,
                LocalComposerSendGeometryObserver provides onPaintedGeometry,
                LocalComposerSendOwnerObserver provides onMotionOwner,
            ) {
                WhiteNoiseTheme {
                    Box(Modifier.fillMaxSize().padding(bottom = keyboardClearanceDp.dp)) {
                        ConversationScreen(
                            appState = conversation.appState,
                            chat =
                                ChatListItem(
                                    group = conversationTimelineTestGroup(),
                                    latest = null,
                                    otherMemberAccount = null,
                                    memberCount = 1,
                                    memberSnapshot = conversationTimelineMemberSnapshot(),
                                ),
                            controller = conversation,
                            onBack = {},
                            restoredScrollSnapshot =
                                if (liveTail) {
                                    null
                                } else {
                                    ConversationScrollSnapshot(
                                        firstVisibleItemIndex = 49,
                                        firstVisibleItemScrollOffset = 0,
                                        anchorItemId = "msg:$historyId",
                                        anchorMessageIdHex = historyId,
                                    )
                                },
                        )
                    }
                }
            }
        }
    }

    /** Keeps identifiers private in test memory while observing the real writer and measured viewport. */
    private class SendRouteEvidence : ConversationScrollEvidenceSink {
        val writes = CopyOnWriteArrayList<ConversationScrollWriteEvidence>()
        var viewport: ConversationViewportEvidence? = null

        /** Retains the measured current frame for bubble/composer clearance assertions. */
        override fun onViewport(snapshot: ConversationViewportEvidence) {
            viewport = snapshot
        }

        /** Records every real list write so an animated history glide cannot pass as a snap. */
        override fun onWrite(write: ConversationScrollWriteEvidence) {
            writes += write
        }
    }

    private companion object {
        const val SENT_TEXT = "Accepted send from older history"
        const val HISTORY_ANCHOR_INDEX = 10
        const val LONG_MESSAGE_LINES = 70
    }
}
