package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
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
import kotlinx.coroutines.CompletableDeferred
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
    private var deferMarkdownHydration = false
    private var formattedHydration = false
    private var restorePlainAfterInvalidation = false
    private var receiveDuringFlight = false
    private var emitIncomingWindow: (() -> Unit)? = null
    private val markdownHydrationGate = CompletableDeferred<Unit>()
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

    /** Real native-shaped paragraph hydration completes before the first accepted paint. */
    @Test
    fun hydratedParagraphBeforeFirstFrameKeepsTheSendFlight() = assertLiveTailFlight("Hydrated before paint", 0)

    /** Replacing the plain leaf during travel must preserve the same accepted destination owner. */
    @Test
    fun hydratedParagraphDuringFlightKeepsTheSendFlight() {
        deferMarkdownHydration = true
        assertLiveTailFlight("Hydrated during travel", 0)
    }

    /** A row arriving mid-flight moves the accepted row only once it is canonical, and painted rows never overlap. */
    @Test
    fun incomingRowDuringFlightKeepsWholePaintedRowsSeparate() {
        receiveDuringFlight = true
        assertLiveTailFlight("Sending while another row arrives", 0)
    }

    /** Formatted hydration mid-flight retires only the matching flight copy and reveals the canonical styled text. */
    @Test
    fun formattedHydrationRetiresOnlyTheMatchingCopyAndRevealsCanonicalInk() {
        deferMarkdownHydration = true
        formattedHydration = true
        assertLiveTailFlight("**Styled send**", 0)
    }

    /** A plain document replacing a retired formatted destination never restarts the flight, and shows raw markdown. */
    @Test
    fun formattedDestinationThenPlainRestorationCannotResurrectTheSameFlight() {
        deferMarkdownHydration = true
        formattedHydration = true
        restorePlainAfterInvalidation = true
        assertLiveTailFlight("**Styled send**", 0)
    }

    /** A trailing keyboard-suggestion space is trimmed from the accepted text without dropping the ink flight. */
    @Test
    fun trailingKeyboardSpaceKeepsTheAcceptedInkFlight() = assertLiveTailFlight("Keyboard suggestion ", 0)

    /** Multiline contraction uses the actual transcript viewport. */
    @Test
    fun acceptedMultilineSendWithKeyboardClearancePaintsAnIntermediateFlight() {
        assertLiveTailFlight("First line\nSecond line\nThird line", 240)
    }

    /** Disabling motion during a real accepted send immediately retires its decorative copy. */
    @Test
    fun liveSystemDisableSettlesTheAcceptedSendRoute() {
        assertLiveTailFlight("Live suppression", 0, suppressDuringFlight = true)
    }

    /** A measured target can retire while motion remains enabled; remaining composer geometry must snap. */
    @Test
    fun retiredTargetSnapsAnAlreadyPaintedSendAndKeepsTheNextDraft() =
        assertLiveTailFlight("First line\nSecond line\nThird line", 240, retireDuringFlight = true)

    /** Captures the editor viewport bounds inside the composer pill and the colours painted in its top corners. */
    private fun retiredPillInternalGeometry(): Pair<Rect, List<Color>> {
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

    /** Releases the deferred markdown parse at frame 4, once moving ink has painted, and awaits the publish. */
    private fun hydrateDuringFlight(
        index: Int,
        frames: LiveTailFrames,
    ) {
        if (!deferMarkdownHydration || index != 4) return
        assertTrue("hydrate only after real moving ink", frames.painted.any { it.progress > 0f && it.progress < 1f })
        markdownHydrationGate.complete(Unit)
        awaitConversationCondition { publishCount == 1 }
    }

    /** At frame 8, asserts the formatted ink retired the owner, then replays the row with a plain document. */
    private fun restorePlainDestination(
        index: Int,
        frames: LiveTailFrames,
    ) {
        if (!restorePlainAfterInvalidation || index != 8) return
        assertEquals("Formatted native ink retired the matching owner", null, frames.motionOwner?.activePresentationId)
        composeRule.onNodeWithText("Styled send").assertIsDisplayed()
        val conversation = checkNotNull(controller)
        val accepted = conversation.timeline.single { it.record.plaintext == "**Styled send**" }
        val projected =
            accepted.projected ?: timelineRecord(
                accepted.record.messageIdHex,
                accepted.record.recordedAt,
                accepted.record.plaintext,
            ).copy(direction = accepted.record.direction, sender = accepted.record.sender)
        val plain =
            MarkdownDocumentFfi(
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("**Styled send**")))),
                truncated = false,
                blankLinesBefore = ByteArray(0),
            )
        composeRule.runOnIdle {
            conversation.testApplyLiveTimelineChangesAndRegisterStreams(
                listOf(
                    dev.ipf.marmotkit.TimelineMessageChangeFfi.Upsert(
                        trigger = dev.ipf.marmotkit.TimelineUpdateTriggerFfi.NEW_MESSAGE,
                        message = projected.copy(contentTokens = plain),
                    ),
                ),
            )
        }
    }

    /** At frame 6, asserts a painted flight is active, then sets the animator scale to zero and notifies observers. */
    private fun suppressAtFrame(
        index: Int,
        frames: LiveTailFrames,
        resolver: android.content.ContentResolver,
    ) {
        if (index != 6) return
        assertTrue("the production flight must be active before suppression", frames.motionOwner?.flight != null)
        assertTrue(
            "suppression must follow a genuinely painted moving frame",
            frames.painted.any { it.progress > 0f && it.progress < 1f },
        )
        composeRule.runOnIdle {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
    }

    /** At frame 6, asserts a flight has painted, then retires its destination with motion still enabled. */
    private fun retireAtFrame(
        index: Int,
        frames: LiveTailFrames,
    ) {
        if (index != 6) return
        assertTrue(
            "retirement must follow a genuinely painted flight",
            frames.painted.any { it.progress > 0f && it.progress < 1f },
        )
        composeRule.runOnIdle { checkNotNull(frames.motionOwner).clear("test-destination-retired") }
        assertTrue("this fallback must not depend on disabling motion", frames.motionOwner?.enabled == true)
    }

    /** At frame 10, emits the incoming window mid-flight and waits for the received row to reach the timeline. */
    private fun receiveAtFrame(
        index: Int,
        frames: LiveTailFrames,
    ) {
        if (!receiveDuringFlight || index != 10) return
        assertTrue("the accepted send is really in flight", frames.motionOwner?.flight != null)
        assertTrue("the accepted copy already painted", frames.painted.any { it.progress > 0f && it.progress < 1f })
        checkNotNull(emitIncomingWindow).invoke()
        awaitConversationCondition { checkNotNull(controller).timeline.any { it.record.plaintext == INCOMING_TEXT } }
    }

    /**
     * Asserts the last three tail rows paint as whole, non-overlapping rows once the landing copy settles, and records
     * whether the accepted row moved after its first canonical exposure.
     */
    private fun assertPaintedTailSeparation(
        frames: LiveTailFrames,
        index: Int,
    ) {
        val motion = checkNotNull(frames.motionOwner)
        val settledCopy = motion.lastRenderedGeometry?.takeIf { it.settled }
        // A still-traveling copy may cross the entrance; only landing/canonical paint owns row slots.
        if (motion.flight != null && settledCopy == null) return
        val rows =
            checkNotNull(controller).timeline.takeLast(3).map { item ->
                val bounds =
                    if (motion.presentation(item.presentationId).coversNative) {
                        checkNotNull(settledCopy).bounds
                    } else {
                        composeRule
                            .onNodeWithTag(
                                messageBubbleColumnTestTag(item.record.messageIdHex),
                                useUnmergedTree = true,
                            ).fetchSemanticsNode()
                            .boundsInWindow
                    }
                item to bounds
            }
        val outgoing = rows.single { it.first.record.direction == "sent" }.second
        if (frames.firstCanonicalExposureIndex == null) {
            frames.firstCanonicalExposureIndex = index
            frames.firstCanonicalExposureTop = outgoing.top
        } else if (kotlin.math.abs(outgoing.top - checkNotNull(frames.firstCanonicalExposureTop)) > 1f) {
            frames.incomingMovedAfterExposure = true
        }
        val visibleRows = rows.filterNot { it.second.isEmpty }.sortedBy { it.second.top }
        assertTrue("the actual tail exposes multiple whole rows", visibleRows.size >= 2)
        for ((upper, lower) in visibleRows.zipWithNext()) {
            assertTrue(
                "whole landing/canonical rows cannot overlap: $upper then $lower",
                upper.second.bottom <= lower.second.top + 1f,
            )
        }
    }

    /** Steps 30 frames, applying each scripted mid-flight event, drawing natively and collecting evidence. */
    private fun advanceLiveTailFrames(
        frames: LiveTailFrames,
        resolver: android.content.ContentResolver,
        suppressDuringFlight: Boolean,
        retireDuringFlight: Boolean,
    ) {
        repeat(30) { index ->
            hydrateDuringFlight(index, frames)
            restorePlainDestination(index, frames)
            if (suppressDuringFlight) suppressAtFrame(index, frames, resolver)
            if (retireDuringFlight) retireAtFrame(index, frames)
            receiveAtFrame(index, frames)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            // Drive the actual production Canvas at this clock frame through native View.draw.
            composeRule.onRoot().captureToImage()
            if (receiveDuringFlight && index >= 10) assertPaintedTailSeparation(frames, index)
            if (formattedHydration && index >= 6) assertEquals(null, frames.motionOwner?.activePresentationId)
            if (restorePlainAfterInvalidation && index in listOf(6, 8, 10)) {
                composeRule.onRoot().captureRoboImage("src/test/snapshots/send_invalid_restore_$index.png")
            }
            if (retireDuringFlight && index == 8) {
                frames.earlyRetiredInternalGeometry = retiredPillInternalGeometry()
                frames.earlyRetiredHeight =
                    composeRule
                        .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                        .fetchSemanticsNode()
                        .boundsInRoot.height
            }
            frames.boundaries += "frame-$index ${frames.motionOwner?.diagnosticState}"
        }
    }

    /** Asserts the flight painted moving geometry, the send published and hydrated once, and each variant settled. */
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
        if (receiveDuringFlight) {
            assertTrue("the real route exposes a landing or canonical row", frames.firstCanonicalExposureIndex != null)
            assertTrue(
                "incoming moves after first canonical exposure: ${frames.firstCanonicalExposureIndex}",
                frames.incomingMovedAfterExposure,
            )
        }
        if (suppressDuringFlight || retireDuringFlight) assertEquals(null, frames.motionOwner?.flight)
        if (retireDuringFlight) {
            val settledHeight =
                composeRule
                    .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            assertEquals(
                "retiring the flight must snap the remaining composer geometry",
                settledHeight,
                checkNotNull(frames.earlyRetiredHeight),
                1f,
            )
            assertEquals(
                "inner editor insets and painted pill corners must already be settled",
                retiredPillInternalGeometry(),
                checkNotNull(frames.earlyRetiredInternalGeometry),
            )
        }
        assertEquals(1, publishCount)
        val acceptedText = text.trimEnd(' ')
        assertEquals(1, conversation.timeline.count { it.record.plaintext == acceptedText })
        assertTrue(
            "actual paragraph hydration reached the mounted row",
            conversation.timeline.any {
                it.record.plaintext == acceptedText &&
                    it.record.contentTokens.blocks
                        .isNotEmpty()
            },
        )
        val expectedText = if (formattedHydration && !restorePlainAfterInvalidation) "Styled send" else acceptedText
        composeRule.onNodeWithText(expectedText).assertIsDisplayed()
        composeRule.onNodeWithTag("conversation.send.motion.active").assertDoesNotExist()
        if (retireDuringFlight) {
            composeRule.onNode(hasSetTextAction()).performTextInput("Next draft")
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText("Next draft").assertIsDisplayed()
        }
    }

    /**
     * Mounts the live-tail route with the given draft, freezes frame time, sends it and drives the flight to its end,
     * restoring the global animator scale and draw traversals afterwards.
     */
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
            // Native capture uses View.draw: this host has no real PixelCopy window surface.
            ShadowView.setUseRealDrawTraversals(false)
            val conversation = createConversation(draftText = text)
            val evidence = SendRouteEvidence()
            val frames = LiveTailFrames()
            mountHistoryConversation(
                conversation,
                evidence,
                liveTail = true,
                keyboardClearanceDp = keyboardClearanceDp,
                onPaintedGeometry = { frames.painted.add(it) },
                onMotionOwner = { owner ->
                    frames.motionOwner = owner
                    owner.onLandingDiagnostic = { kind, diagnostic ->
                        // Log each immutable observation immediately: retirement can fail before final assertions.
                        if (frames.landingDiagnosticCount++ < 160) {
                            println(
                                "SEND_RESOLVE clockMs=${composeRule.mainClock.currentTime} kind=$kind " +
                                    "rectBits=${diagnosticRectBits(diagnostic)} $diagnostic",
                            )
                        }
                    }
                },
            )
            composeRule.waitForIdle()
            composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.FollowingTail }
            // FollowingTail is the initial navigation intent, before reveal/layout gates have settled.
            // Keep driving frames until the real route admits motion, then freeze the send itself.
            composeRule.waitUntil(5_000) {
                composeRule.onRoot().captureToImage()
                frames.motionOwner?.enabled == true
            }
            frames.boundaries += "before-send ${frames.motionOwner?.diagnosticState}"
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            awaitConversationCondition {
                if (deferMarkdownHydration) {
                    frames.motionOwner?.flight != null
                } else {
                    publishCount == 1
                }
            }
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
        val timelineSubscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val subscriptions =
            ScriptedConversationLiveSubscriptions(
                listOf(timelineSubscription),
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
                markdownParser = { text ->
                    if (deferMarkdownHydration && text == draftText) markdownHydrationGate.await()
                    plainParagraphDocument(text)
                },
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
        emitIncomingWindow = incomingWindowEmitter(conversation, timelineSubscription, records, draftText)
        awaitConversationCondition { conversation.timeline.size == records.size }
        return conversation
    }

    /**
     * Parses text into a one-paragraph markdown document with soft breaks, or a bold run for the styled send when
     * formatted hydration is on.
     */
    private fun plainParagraphDocument(text: String): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blocks =
                if (formattedHydration && text == "**Styled send**") {
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Styled send")))),
                        ),
                    )
                } else {
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            text.split('\n').flatMapIndexed { index, line ->
                                buildList {
                                    if (index > 0) add(MarkdownInlineFfi.SoftBreak)
                                    add(MarkdownInlineFfi.Text(line))
                                }
                            },
                        ),
                    )
                },
            blankLinesBefore = ByteArray(0),
        )

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
                                    projection = if (liveTail) liveTailProjection(conversation) else null,
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

    private companion object {
        const val SENT_TEXT = "Accepted send from older history"
        const val HISTORY_ANCHOR_INDEX = 10
        const val LONG_MESSAGE_LINES = 70
    }
}

private const val INCOMING_TEXT = "A received row while sending"

/**
 * Returns an action that republishes the timeline window with the accepted row plus a newly received row, then waits
 * for the subscription to consume it.
 */
private fun incomingWindowEmitter(
    conversation: ConversationController,
    timelineSubscription: ScriptedConversationTimelineSubscription,
    records: List<TimelineMessageRecordFfi>,
    draftText: String,
): () -> Unit =
    {
        val accepted = conversation.timeline.single { it.record.plaintext == draftText.trimEnd(' ') }
        val own =
            accepted.projected ?: timelineRecord(
                accepted.record.messageIdHex,
                accepted.record.recordedAt,
                accepted.record.plaintext,
            ).copy(
                direction = accepted.record.direction,
                sender = accepted.record.sender,
                contentTokens = accepted.record.contentTokens,
            )
        val incoming = timelineRecord("d4".repeat(32), accepted.record.recordedAt + 1uL, INCOMING_TEXT)
        val callsBefore = timelineSubscription.nextWindowCallCount
        timelineSubscription.emitWindow(timelinePage(*(records + own + incoming).toTypedArray()))
        awaitConversationCondition { timelineSubscription.nextWindowCallCount > callsBefore }
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(10))
    }

/** Rect.toString rounds coordinates; raw IEEE754 bits retain exact guard inputs. */
private fun diagnosticRectBits(value: ComposerSendLandingDiagnostic): String =
    listOf(
        value.sourceSurface,
        value.sourceComposer,
        value.sourceViewport?.bounds,
        value.measured?.frame,
        value.measured?.text,
        value.measured?.composer,
        value.measured?.viewport?.bounds,
        value.expectedFrameBefore,
        value.expectedTextBefore,
        value.resolved?.bounds,
        value.resolved?.textTarget,
        value.resolved?.canonicalFrame,
        value.resolved?.canonicalText,
        value.resolved?.composer,
    ).joinToString("|") { rect ->
        rect?.let {
            listOf(it.left.toRawBits(), it.top.toRawBits(), it.right.toRawBits(), it.bottom.toRawBits())
                .joinToString(",")
        } ?: "null"
    }

private class LiveTailFrames {
    var landingDiagnosticCount = 0
    val painted = CopyOnWriteArrayList<ComposerSendFlightGeometry>()
    var motionOwner: ComposerSendMotion? = null
    val boundaries = mutableListOf<String>()
    var firstCanonicalExposureIndex: Int? = null
    var firstCanonicalExposureTop: Float? = null
    var incomingMovedAfterExposure = false
    var earlyRetiredHeight: Float? = null
    var earlyRetiredInternalGeometry: Pair<Rect, List<Color>>? = null
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

/** Builds a chat-row projection for the test group whose last-read marker points at the newest timeline record. */
private fun liveTailProjection(conversation: ConversationController): dev.ipf.marmotkit.ChatListRowFfi {
    val group = conversationTimelineTestGroup()
    val latest = conversation.timeline.last().record
    return dev.ipf.whitenoise.android.state.chatRow(group.groupIdHex).copy(
        groupName = group.name,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = latest.messageIdHex,
        lastReadTimelineAt = latest.recordedAt,
    )
}
