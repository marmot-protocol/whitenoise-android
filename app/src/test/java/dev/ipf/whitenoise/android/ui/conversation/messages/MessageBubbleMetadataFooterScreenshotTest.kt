@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Exercises projected delivery state through the actual message bubble, rather than a footer mock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1100dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleMetadataFooterScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = fileFooterAppState(context)
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val composer = ComposerTextState(TextFieldValue(""))
    private val originalTimeZone = TimeZone.getDefault()
    private var originalTimeFormat: String? = null

    @Before
    fun pinTime() {
        originalTimeFormat = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun releaseFixture() {
        try {
            controller.onCleared()
        } finally {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, originalTimeFormat)
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun shortWordDeliveryStatesLight() = renderStateMatrix(dark = false, amoled = false)

    @Test
    fun shortWordDeliveryStatesDark() = renderStateMatrix(dark = true, amoled = false)

    @Test
    fun shortWordDeliveryStatesAmoled() = renderStateMatrix(dark = true, amoled = true)

    /** A genuinely projected unconfirmed row must suppress the pending glyph and retain its warning. */
    @Test
    fun narrowLargeRtlUnconfirmedFooterStaysInsideTheBubble() {
        val item = textMessage(60, "🙂", unconfirmed = true)
        render(listOf(item), dark = true, amoled = true, width = 200, fontScale = 1.6f, rtl = true)
        assertUnconfirmedFooter(item, "01:01", rtl = true)
        composeRule.onNodeWithContentDescription("Sending").assertDoesNotExist()
        capture("message_metadata_footer_narrow_large_rtl")
    }

    /** A reply's minimum width may widen the bubble but cannot detach metadata from its trailing edge. */
    @Test
    fun replyWidenedUnconfirmedFooterRemainsTerminal() {
        val item = textMessage(60, "Hi", unconfirmed = true, hasReply = true)
        render(listOf(item), dark = false, amoled = false)
        assertUnconfirmedFooter(item, "01:01", rtl = false)
        capture("message_metadata_footer_reply_widened")
    }

    @Test
    fun confirmedTransitionReusesOneTimestampAndRemovesTheWarning() {
        val unconfirmed = textMessage(60, "Hi", status = MessageStatus.Pending, unconfirmed = true)
        var item by mutableStateOf(unconfirmed)
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.width(320.dp)) { Bubble(item) }
            }
        }
        assertUnconfirmedFooter(unconfirmed, "01:01", rtl = false)
        composeRule.onNodeWithContentDescription("Sending").assertDoesNotExist()
        composeRule.runOnIdle {
            item =
                unconfirmed.copy(
                    status = MessageStatus.Sent,
                    projected = unconfirmed.projected?.copy(invalidationStatus = null),
                )
        }
        composeRule.onNodeWithText("Delivery not confirmed", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onAllNodesWithText("01:01", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Sent").assertExists()
    }

    /** Physical left-aligned Latin lines cannot share the footer's logical trailing edge in RTL. */
    @Test
    fun sentMultilineLatinPlainTextInRtlDoesNotOverlapItsFooter() {
        val body = "A Latin message with several wrapped lines of ordinary text.\nHi"
        val base = textMessage(60, body)
        // An unhydrated timeline document is empty, never null. Keep fallback parsing empty as
        // well, exercising the production plain-text path until Markdown becomes available.
        val emptyDocument = markdown("")
        val item =
            base.copy(
                record = base.record.copy(contentTokens = emptyDocument),
                projected = base.projected?.copy(contentTokens = emptyDocument),
            )
        render(listOf(item), dark = false, amoled = false, width = 220, rtl = true, hydrateMarkdown = false)
        capture("message_metadata_footer_sent_latin_plain_rtl")
        assertLatinFooterClearOfBody(body)
    }

    /** The actual projected Markdown paragraph follows the same logical extent contract. */
    @Test
    fun sentMultilineLatinMarkdownInRtlDoesNotOverlapItsFooter() {
        val body = "A Latin message with several wrapped lines of ordinary text.\nHi"
        render(listOf(textMessage(60, body)), dark = true, amoled = true, width = 220, rtl = true)
        capture("message_metadata_footer_sent_latin_markdown_rtl")
        assertLatinFooterClearOfBody(body)
    }

    /** A short RTL word still shares its final line with compact successful metadata. */
    @Test
    fun shortSuccessfulRtlTextRetainsCompactInlineMetadata() {
        val body = "שלום"
        render(listOf(textMessage(60, body)), dark = false, amoled = false, width = 220, rtl = true)
        val text = composeRule.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = composeRule.onNodeWithText("01:01", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("short RTL text should keep its compact inline footer", time.top < text.bottom)
        assertTrue("logical trailing timestamp must not intersect the RTL word", time.right <= text.left)
        capture("message_metadata_footer_short_sent_rtl")
    }

    private fun assertLatinFooterClearOfBody(body: String) {
        val bodyNode = composeRule.onNodeWithText(body, useUnmergedTree = true)
        val text = bodyNode.fetchSemanticsNode().boundsInRoot
        val results = mutableListOf<TextLayoutResult>()
        bodyNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val time = composeRule.onNodeWithText("01:01", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status =
            composeRule.onNodeWithContentDescription("Sent", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val footer =
            Rect(
                minOf(time.left, status.left),
                minOf(time.top, status.top),
                maxOf(time.right, status.right),
                maxOf(time.bottom, status.bottom),
            )
        val lines =
            (0 until layout.lineCount).map { line ->
                Rect(
                    text.left + layout.getLineLeft(line),
                    text.top + layout.getLineTop(line),
                    text.left + layout.getLineRight(line),
                    text.top + layout.getLineBottom(line),
                )
            }
        println("RTL message footer: text=$text, footer=$footer, lines=$lines")
        assertTrue("Latin text must actually wrap", layout.lineCount > 1)
        assertTrue("footer $footer must not intersect rendered text lines $lines", lines.none { it.overlaps(footer) })
    }

    /** The production collapse branch must place warning/time after its real Read More control. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h2200dp-mdpi")
    fun collapsedProjectedUnconfirmedMessageKeepsMetadataAfterReadMore() {
        val body = (1..70).joinToString("\n") { "Message line $it" }
        val item = textMessage(60, body, status = MessageStatus.Pending, unconfirmed = true)
        render(listOf(item), dark = true, amoled = true)
        val readMore = composeRule.onNodeWithText("Read More", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val warning =
            composeRule.onNodeWithText("Delivery not confirmed", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val time = composeRule.onNodeWithText("01:01", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val text = composeRule.onNodeWithText(body, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bubble =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(item.record.messageIdHex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val footerWidth = time.right - warning.left
        val expectedWidth = maxOf(text.right - text.left, readMore.right - readMore.left, footerWidth)
        val leadingInset = minOf(text.left, readMore.left, warning.left) - bubble.left
        val trailingInset = bubble.right - maxOf(text.right, readMore.right, time.right)
        println(
            "Production collapsed footer: body=$text, readMore=$readMore, warning=$warning, time=$time, bubble=$bubble",
        )
        assertTrue(
            "independent rows must not reserve unused inline width",
            bubble.right - bubble.left <= expectedWidth + leadingInset + trailingInset + 1.dp,
        )
        assertTrue("production warning must follow Read More", warning.top >= readMore.bottom)
        assertTrue("timestamp must remain terminal", time.bottom >= warning.bottom)
        assertTrue("warning and time must not intersect", warning.bottom <= time.top || warning.right <= time.left)
        composeRule.onNodeWithContentDescription("Sending").assertDoesNotExist()
        capture("message_metadata_footer_collapsed_unconfirmed")
    }

    private fun renderStateMatrix(
        dark: Boolean,
        amoled: Boolean,
    ) {
        val rows =
            listOf(
                textMessage(0, "Hi", status = MessageStatus.Received, mine = false),
                textMessage(60, "Hi", status = MessageStatus.Pending),
                textMessage(120, "Hi"),
                textMessage(180, "Hi", status = MessageStatus.Pending, unconfirmed = true),
                textMessage(240, "Hi", status = MessageStatus.Failed),
            )
        render(rows, dark, amoled)
        listOf("01:00", "01:01", "01:02", "01:03", "01:04").forEach {
            composeRule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(1)
        }
        assertUnconfirmedFooter(rows[3], "01:03", rtl = false)
        val warning =
            composeRule.onNodeWithText("Delivery not confirmed", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val time = composeRule.onNodeWithText("01:03", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(
            "warning and time share their last baseline when they fit",
            warning.bottom.value,
            time.bottom.value,
            1f,
        )
        val failedTime = composeRule.onNodeWithText("01:04", useUnmergedTree = true).getUnclippedBoundsInRoot()
        listOf("Retry", "Discard failed message").forEach {
            val control =
                composeRule.onNodeWithContentDescription(it, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("recovery controls must precede terminal metadata", control.bottom <= failedTime.top)
        }
        capture(
            "message_metadata_footer_states_${if (amoled) {
                "amoled"
            } else if (dark) {
                "dark"
            } else {
                "light"
            }}",
        )
    }

    private fun assertUnconfirmedFooter(
        item: TimelineMessage,
        time: String,
        rtl: Boolean,
    ) {
        composeRule.onAllNodesWithText("Delivery not confirmed", useUnmergedTree = true).assertCountEquals(1)
        val warning =
            composeRule.onNodeWithText("Delivery not confirmed", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val timestamp = composeRule.onNodeWithText(time, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bubble =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(item.record.messageIdHex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        listOf(warning, timestamp).forEach {
            assertTrue(
                "footer must stay horizontally inside its bubble",
                it.left >= bubble.left && it.right <= bubble.right,
            )
            assertTrue(
                "footer must stay vertically inside its bubble",
                it.top >= bubble.top && it.bottom <= bubble.bottom,
            )
        }
        assertTrue("no warning may follow the terminal timestamp", warning.bottom <= timestamp.bottom)
        assertTrue(
            "warning and timestamp must not intersect",
            warning.bottom <= timestamp.top ||
                if (rtl) timestamp.right <= warning.left else warning.right <= timestamp.left,
        )
    }

    private fun textMessage(
        index: Int,
        body: String,
        status: MessageStatus = MessageStatus.Sent,
        mine: Boolean = true,
        unconfirmed: Boolean = false,
        hasReply: Boolean = false,
    ): TimelineMessage =
        fileTimelineMessage(
            index = index,
            fileName = "",
            attachments = emptyList(),
            mine = mine,
            caption = body,
            hasReply = hasReply,
            status = status,
            invalidationStatus = "local_publish_failed".takeIf { unconfirmed },
        )

    private fun render(
        rows: List<TimelineMessage>,
        dark: Boolean,
        amoled: Boolean,
        width: Int = 320,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        hydrateMarkdown: Boolean = true,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Column(Modifier.width(width.dp).testTag("metadata-footer-gallery")) {
                        rows.forEach { Bubble(it, hydrateMarkdown = hydrateMarkdown) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Bubble(
        item: TimelineMessage,
        hydrateMarkdown: Boolean = true,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composer,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            inviteMutationInFlight = false,
            onJoinInvite = {},
            onDeclineInvite = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderAvatar = false,
            parseMarkdown = { source -> if (hydrateMarkdown) markdown(source) else markdown("") },
        )
    }

    private fun capture(name: String) {
        composeRule.onNodeWithTag("metadata-footer-gallery").captureRoboImage("src/test/snapshots/$name.png")
    }
}
