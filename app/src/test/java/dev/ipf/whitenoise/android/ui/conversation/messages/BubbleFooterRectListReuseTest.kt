package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.conversation.LazyListConversationScrollWriter
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Exercises production footer/Markdown components when lazy slots are reused or reactivated.
 * The unpatched 1.12.1 artifact crashes in the first three cases; the source backport must not.
 * The centering driver models the snap/animate sequence and is not a full screen navigation test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleFooterRectListReuseTest {
    @get:Rule
    val composeRule = createComposeRule()

    private enum class Wrapper {
        /** Bubble emitted straight into the LazyColumn item: slots are reused across messages. */
        None,

        /** TimelineRow as shipped in versionCode 21: key(account, group, message id). */
        ShippedKey,
    }

    private val swapped = mutableStateMapOf<Int, Boolean>()

    /** Far snaps preserve RectList consistency while lazy items reuse another message's nodes. */
    @Test
    fun farSnapReusesAnotherMessagesBubbleWithoutCrashing() {
        farSnaps(renderTimeline(Wrapper.None))
    }

    /** Both phases of the modeled centering sequence complete without corrupting RectList. */
    @Test
    fun centeringJumpReusesAnotherMessagesBubbleWithoutCrashing() {
        centringJumps(renderTimeline(Wrapper.None))
    }

    /** With the shipped key, a row's own pooled slot is reactivated after its body changed off screen. */
    @Test
    fun shippedKeyReactivatesEditedBubbleWithoutCrashing() {
        jumpAwayEditAndReturn(renderTimeline(Wrapper.ShippedKey))
    }

    /** The shipped key already makes a slot reused by another message compose a fresh bubble. */
    @Test
    fun shippedKeyFarSnapAcrossMessagesKeepsRectListConsistent() {
        val timeline = renderTimeline(Wrapper.ShippedKey)
        farSnaps(timeline)
        composeRule.runOnIdle { assertEquals(FAR_TARGETS.last(), timeline.state.firstVisibleItemIndex) }
    }

    /** Returning to unchanged rows reuses their nodes without tripping the bug. */
    @Test
    fun shippedKeyReactivationWithoutEditKeepsRectListConsistent() {
        val timeline = renderTimeline(Wrapper.ShippedKey)
        jumpAwayEditAndReturn(timeline, edit = false)
        composeRule.runOnIdle { assertEquals(0, timeline.state.firstVisibleItemIndex) }
    }

    private class Timeline(
        val state: LazyListState,
        val scope: CoroutineScope,
    ) {
        val writer = LazyListConversationScrollWriter(state)
    }

    /** Snaps to each far target with the production scroll writer. */
    private fun farSnaps(timeline: Timeline) {
        FAR_TARGETS.forEach { target ->
            composeRule.runOnIdle { timeline.scope.launch { timeline.writer.scrollToItem(target, 0) } }
            composeRule.waitForIdle()
        }
    }

    /** Mirrors ConversationScrollCommandScope.animateScrollToItem: snap near a far target, then animate. */
    private fun centringJumps(timeline: Timeline) {
        FAR_TARGETS.forEach { target ->
            composeRule.runOnIdle {
                timeline.scope.launch {
                    val current = timeline.writer.firstVisibleItemIndex
                    if (abs(target - current) > APPROACH_ROWS) {
                        val approach = if (target > current) target - APPROACH_ROWS else target + APPROACH_ROWS
                        timeline.writer.scrollToItem(approach, 0)
                    }
                    timeline.writer.animateScrollToItem(target, 0)
                }
            }
            composeRule.waitForIdle()
        }
    }

    /** Leaves the newest rows, optionally edits them off screen, and jumps back so their slots reactivate. */
    private fun jumpAwayEditAndReturn(
        timeline: Timeline,
        edit: Boolean = true,
    ) {
        repeat(6) { round ->
            composeRule.runOnIdle { timeline.scope.launch { timeline.writer.scrollToItem(200, 0) } }
            composeRule.waitForIdle()
            // An off-screen edit that moves text between the two paragraphs, keeping the total size.
            if (edit) composeRule.runOnIdle { (0 until 30).forEach { swapped[it] = round % 2 == 0 } }
            composeRule.runOnIdle { timeline.scope.launch { timeline.writer.scrollToItem(0, 0) } }
            composeRule.waitForIdle()
        }
    }

    /** Renders a reversed, single-content-type timeline whose rows use [wrapper] around the bubble. */
    private fun renderTimeline(wrapper: Wrapper): Timeline {
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            WhiteNoiseTheme {
                LazyColumn(state = state, reverseLayout = true, modifier = Modifier.size(360.dp, 640.dp)) {
                    // One pool for every message row, as ConversationScreen's contentType does.
                    items((0 until ROWS).toList(), key = { "message-$it" }, contentType = { "message" }) { id ->
                        when (wrapper) {
                            Wrapper.None -> TimelineBubble(id)
                            // TimelineRow: Column → Column(onSizeChanged) → Box(padding) → keyed bubble.
                            Wrapper.ShippedKey ->
                                TimelineRowShell {
                                    key("account", "group", "message-$id") { TimelineBubble(id) }
                                }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return Timeline(state, scope)
    }

    /** The row chrome TimelineRow puts around its keyed bubble. */
    @Composable
    private fun TimelineRowShell(bubble: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().onSizeChanged { }) {
                Box(Modifier.padding(top = 2.dp)) { bubble() }
            }
        }
    }

    /** The text-bubble path of BubbleContentBlocks: collapsible footer layout over a markdown body. */
    @Composable
    private fun TimelineBubble(id: Int) {
        val mine = id % 3 == 0
        val flip = swapped[id] == true
        val document = remember(id, flip) { twoParagraphDocument(if (flip) id + 1 else id) }
        var lastLineLayout by remember(id) { mutableStateOf<TextLayoutResult?>(null) }
        val density = LocalDensity.current
        val bodyTextStyle = MaterialTheme.typography.bodyLarge
        val lineHeightPx = with(density) { bodyTextStyle.lineHeight.toPx() }
        val maxBodyHeightPx = lineHeightPx * MESSAGE_COLLAPSE_LINE_LIMIT
        val maxBodyHeight = with(density) { maxBodyHeightPx.toDp() }
        val contentColor = MaterialTheme.colorScheme.onSurface
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Column(Modifier.widthIn(max = 300.dp)) {
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BubbleCollapsibleFooterLayout(
                        maxBodyHeight = maxBodyHeight,
                        readMore = { Text("Read more", style = MaterialTheme.typography.bodyLarge) },
                        footer = {
                            MessageInlineFooter(
                                timeText = "12:${(id % 60).toString().padStart(2, '0')}",
                                color = contentColor,
                                showStatus = mine,
                                status = MessageStatus.Sent,
                                editedLabel = null,
                                onEditedClick = null,
                            )
                        },
                        lastLineWidth = lastLineLayout?.let { ceil(it.getLineRight(it.lineCount - 1)).toInt() },
                        lastLineBaseline = lastLineLayout?.let { it.getLineBaseline(it.lineCount - 1).toInt() },
                    ) {
                        // selectableMessageBody → readAloudMessageSemantics → MarkdownMessageBody.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.onSizeChanged { }) {
                                MarkdownMessageBody(document, onLastTextLayout = { lastLineLayout = it })
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ROWS = 300
        const val APPROACH_ROWS = 10
        val FAR_TARGETS = listOf(150, 12, 260, 40, 199, 3, 120, 280, 60, 0)

        // Synthetic prose, no chat content. Both paragraphs wrap to the bubble's max width and every
        // row has the same total line count; only which paragraph is longer alternates by row.
        const val SENTENCE = "Synthetic filler sentence for a long chat message that wraps across many lines. "

        /** Two wrapped paragraphs whose long/short order alternates with [id]. */
        fun twoParagraphDocument(id: Int): MarkdownDocumentFfi {
            val long = SENTENCE.repeat(3) + "Row $id."
            val short = SENTENCE.repeat(2) + "Row $id."
            val paragraphs = if (id % 2 == 0) listOf(long, short) else listOf(short, long)
            return MarkdownDocumentFfi(
                blocks = paragraphs.map { MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(it))) },
                truncated = false,
                blankLinesBefore = ByteArray(0),
            )
        }
    }
}
