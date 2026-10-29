package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Recomposition budget for a warm send through the live conversation route. The screen body may run once, for the
 * timeline change itself, and unchanged rows must skip: a send that re-ran the whole conversation in several frames,
 * and every visible row and bubble with it, was the main-thread cost behind send jank on device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationSendRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var controller: ConversationController? = null
    private var publishCount = 0
    private val originalTimeZone = TimeZone.getDefault()

    /** Removes the counting tracer and releases conversation-owned jobs and timestamp formatting. */
    @After
    @OptIn(InternalComposeTracingApi::class)
    fun releaseFixture() {
        Composer.setTracer(null)
        controller?.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    /**
     * A warm send recomposes the screen body and the composer once, re-runs only the new row and its neighbour, and
     * recomposes no settled row's swipe glyph. The read anchor, unread badge, jump target, followed tail id, shared
     * media tiles and tail entrance all move after the row lands, and none of them may recompose the screen again.
     */
    @Test
    fun warmSendRecomposesTheScreenOnceAndOnlyTheNewRowAndItsNeighbour() {
        val context = mountAtLiveTail()
        send(context, expectedPublishes = 1)
        settle(60)
        composeRule.onNode(hasSetTextAction()).performTextInput("Second send")
        settle(30)
        val counts =
            countCompositions {
                send(context, expectedPublishes = 2)
                settle(40)
            }
        assertTrue("screen re-run: $counts", counts.exactly(CONVERSATION_SCREEN) <= 1)
        assertTrue("composer re-run: $counts", counts.of("composer.ComposerBar") <= 1)
        assertTrue("rows re-run: $counts", counts.of("TimelineRow") <= 2)
        assertTrue("bubbles re-run: $counts", counts.of("messages.MessageBubble") <= 2)
        assertTrue("swipe glyphs re-run: $counts", counts.of("MessageReplySwipeGlyph") <= 2)
    }

    /**
     * The first outgoing bubble in a chat of received messages fills the outgoing colour slots. That fill must not
     * recompose the received bubbles that already read their own slots.
     */
    @Test
    fun firstOutgoingBubbleDoesNotRecomposeTheReceivedBubbles() {
        val context = mountAtLiveTail()
        val counts =
            countCompositions {
                send(context, expectedPublishes = 1)
                settle(40)
            }
        assertTrue("rows re-run: $counts", counts.of("TimelineRow") <= 2)
        assertTrue("bubbles re-run: $counts", counts.of("messages.MessageBubble") <= 2)
    }

    /** The entrance modifier compares equal for the same entrance, row and claim source, so unchanged rows skip. */
    @Test
    fun tailEntranceModifierComparesEqualForTheSameRowAndClaim() {
        val entrance = ConversationTailEntrance(CoroutineScope(Dispatchers.Unconfined), rowSpacingPx = 0f)
        val claim: () -> String? = { null }
        assertEquals(
            Modifier.conversationTailEntranceMotion(entrance, "m1", claim),
            Modifier.conversationTailEntranceMotion(entrance, "m1", claim),
        )
        assertEquals(
            Modifier.conversationTailEntranceMotion(entrance, "m1"),
            Modifier.conversationTailEntranceMotion(entrance, "m1"),
        )
        assertNotEquals(
            Modifier.conversationTailEntranceMotion(entrance, "m1", claim),
            Modifier.conversationTailEntranceMotion(entrance, "m2", claim),
        )
    }

    /** Mounts the live route with motion on, waits for it to follow the tail and settles it frame by frame. */
    private fun mountAtLiveTail(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val conversation = createConversation()
        val evidence = TailEvidence()
        mount(conversation, evidence)
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { evidence.viewport?.mode is ConversationScrollMode.FollowingTail }
        composeRule.mainClock.autoAdvance = false
        settle(20)
        return context
    }

    /** Presses the production Send button and waits for the scripted publisher to accept it. */
    private fun send(
        context: Context,
        expectedPublishes: Int,
    ) {
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        awaitConversationCondition { publishCount == expectedPublishes }
    }

    /** Advances whole frames and drives each one through the production draw, so draw-time writes happen too. */
    private fun settle(frames: Int) {
        repeat(frames) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            composeRule.onRoot().captureToImage()
        }
    }

    /** Counts composable bodies run during [block], keyed by the compiler's function name. */
    @OptIn(InternalComposeTracingApi::class)
    private fun countCompositions(block: () -> Unit): CompositionCounts {
        val counts = CompositionCounts()
        Composer.setTracer(
            object : CompositionTracer {
                /** Records every body the composer runs while this tracer is installed. */
                override fun isTraceInProgress() = true

                /** Counts one composable body by its compiler-provided name. */
                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    counts.add(info.substringBefore(" ("))
                }

                /** Body ends need no bookkeeping for a count. */
                override fun traceEventEnd() = Unit
            },
        )
        try {
            block()
        } finally {
            Composer.setTracer(null)
        }
        return counts
    }

    /** Builds a 60-message live-tail conversation whose publisher accepts every send as pending. */
    private fun createConversation(): ConversationController {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val records =
            List(60) { index ->
                timelineRecord((index + 1).toString(16).padStart(64, '0'), (index + 1).toULong(), "History $index")
            }
        val timeline = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val subscriptions = ScriptedConversationLiveSubscriptions(listOf(timeline), conversationTimelineTestGroup())
        val appState = conversationTimelineTestAppState(subscriptions.subscriptions)
        appState.draftStore.set(
            ConversationTimelineTestIds.ACCOUNT_REF,
            ConversationTimelineTestIds.GROUP_ID,
            TextFieldValue("First send"),
        )
        val conversation =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
                markdownParser = { text -> plainParagraph(text) },
                clockMillis = { 1_672_531_260_000L },
                textPublisher = { _, _, _, _ ->
                    publishCount++
                    SendSummaryFfi(
                        published = 0u,
                        messageIds = listOf("c$publishCount".repeat(32)),
                        acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                },
            )
        controller = conversation
        awaitConversationCondition { conversation.timeline.size == records.size }
        return conversation
    }

    /** Mounts the production conversation route at its live tail. */
    private fun mount(
        conversation: ConversationController,
        evidence: TailEvidence,
    ) {
        val group = conversationTimelineTestGroup()
        val latest = conversation.timeline.last().record
        val projection =
            chatRow(group.groupIdHex).copy(
                groupName = group.name,
                lastMessage = null,
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                lastReadMessageIdHex = latest.messageIdHex,
                lastReadTimelineAt = latest.recordedAt,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalConversationScrollEvidenceSink provides evidence) {
                WhiteNoiseTheme {
                    ConversationScreen(
                        appState = conversation.appState,
                        chat =
                            ChatListItem(
                                group = group,
                                latest = null,
                                otherMemberAccount = null,
                                memberCount = 1,
                                memberSnapshot = conversationTimelineMemberSnapshot(),
                                projection = projection,
                            ),
                        controller = conversation,
                        onBack = {},
                    )
                }
            }
        }
    }
}

/** The compiler's name for the conversation screen body itself. */
private const val CONVERSATION_SCREEN = "dev.ipf.whitenoise.android.ui.conversation.ConversationScreen"

/** Parses text into one plain paragraph with soft breaks, like the engine's renderer for unformatted sends. */
private fun plainParagraph(text: String): MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        truncated = false,
        blocks =
            listOf(
                MarkdownBlockFfi.Paragraph(
                    text.split('\n').flatMapIndexed { index, line ->
                        buildList {
                            if (index > 0) add(MarkdownInlineFfi.SoftBreak)
                            add(MarkdownInlineFfi.Text(line))
                        }
                    },
                ),
            ),
        blankLinesBefore = ByteArray(0),
    )

/** Composable body counts by fully qualified function name. */
private class CompositionCounts {
    private val counts = linkedMapOf<String, Int>()

    /** Counts one body run for [name]. */
    fun add(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    /** Total runs of functions whose qualified name ends with [suffix]. */
    fun of(suffix: String): Int = counts.entries.filter { it.key.endsWith(suffix) }.sumOf { it.value }

    /** Runs of the one function named [qualifiedName], excluding the local functions and lambdas nested in it. */
    fun exactly(qualifiedName: String): Int = counts[qualifiedName] ?: 0

    /** Lists the conversation functions that ran, most frequent first, for assertion messages. */
    override fun toString(): String =
        counts.entries
            .filter { it.key.startsWith("dev.ipf.whitenoise.android.ui.conversation") }
            .sortedByDescending { it.value }
            .take(12)
            .joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }
}

/** Observes the measured viewport so the test starts from the live tail. */
private class TailEvidence : ConversationScrollEvidenceSink {
    var viewport: ConversationViewportEvidence? = null

    /** Retains the latest measured viewport. */
    override fun onViewport(snapshot: ConversationViewportEvidence) {
        viewport = snapshot
    }

    /** List writes are not part of this budget. */
    override fun onWrite(write: ConversationScrollWriteEvidence) = Unit
}
