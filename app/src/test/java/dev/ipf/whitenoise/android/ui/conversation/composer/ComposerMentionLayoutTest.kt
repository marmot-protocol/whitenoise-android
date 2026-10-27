package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real composer geometry reserves the editor before constrained mention and emoji suggestions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w780dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerMentionLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var observed = TextFieldValue()
    private var sent: String? = null
    private val candidates =
        (1..23).map { index ->
            MentionComposer.Candidate(
                accountIdHex = index.toString(16).padStart(64, '0'),
                npub = "npub1" + "q".repeat(57) + "qpzry9x8gf2tvdw0s3jn54kh"[index - 1],
                displayName = "Member $index",
                nip05 = null,
            )
        }

    /** Five offered rows cannot consume the editor, and the last row remains scrollable and selectable. */
    @Test
    fun largeRosterKeepsEditorAndSendWhileLastSuggestionScrolls() {
        render()
        val originalHeight = pillHeight()
        openMentions()
        assertUsableEditor(originalHeight)
        capture("large_light")
        selectAndSend(5)
    }

    /** A small roster still inserts and sends the same canonical token with the normal compact editor. */
    @Test
    fun smallRosterKeepsNormalInsertionAndFocus() {
        render(memberCount = 2, dark = true)
        val originalHeight = pillHeight()
        openMentions()
        assertUsableEditor(originalHeight)
        capture("small_dark")
        selectAndSend(2)
    }

    /** Enlarged RTL text retains editor and Send geometry while suggestions use a bounded viewport. */
    @Test
    fun largeTextRtlRosterKeepsTheEditorReachable() {
        render(dark = true, amoled = true, rtl = true, fontScale = 1.5f)
        val originalHeight = pillHeight()
        openMentions()
        assertUsableEditor(originalHeight)
        capture("large_amoled_rtl")
        selectAndSend(5)
    }

    /** The landscape keyboard remainder keeps a usable input row and scroll-to-select behavior. */
    @Test
    fun compactLandscapeRosterDoesNotFlattenTheEditor() {
        render(width = 720, height = 190, dark = true)
        val originalHeight = pillHeight()
        openMentions()
        assertUsableEditor(originalHeight)
        capture("landscape_dark")
        selectAndSend(5)
    }

    /** Reply chrome retains its measured space when mentions open, and cancellation remains reachable. */
    @Test
    fun replyAndMentionsKeepTheEditorAndCancelVisible() {
        render(replying = true, height = 560)
        val originalHeight = pillHeight()
        openMentions()
        assertUsableEditor(originalHeight)
        composeRule.onNodeWithText("Parent message").assertIsDisplayed()
        selectAndSend(5)
    }

    /** Edit-mode suggestions preserve the same canonical payload and reachable Save action. */
    @Test
    fun editedMessageKeepsItsInputHeightAndMentionInsertion() {
        render(editing = true, height = 560)
        val originalHeight = pillHeight()
        openMentions()
        assertUsableEditor(originalHeight)
        selectAndSend(5)
    }

    /** Manual expansion still hides suggestions; returning to automatic restores them without losing the draft. */
    @Test
    fun manualResizeAndReturnPreserveTheQuery() {
        render(height = 600)
        openMentions()
        val automaticHeight = composerNodeHeight(composeRule.onNodeWithTag(TAG))
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            swipe(center, center - Offset(0f, 240f), durationMillis = 320)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Member 1").assertDoesNotExist()
        val returnDistance = composerNodeHeight(composeRule.onNodeWithTag(TAG)) - automaticHeight
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            swipe(center, center + Offset(0f, returnDistance), durationMillis = 320)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Member 1").assertIsDisplayed()
        assertEquals("Hi @", observed.text)
        selectAndSend(5)
    }

    /** Shortcode suggestions share the allocation rule and cannot squeeze the editor in a small viewport. */
    @Test
    fun shortcodeSuggestionsPreserveCompactEditorHeight() {
        render(width = 720, height = 190)
        val originalHeight = pillHeight()
        composeRule.onNode(hasSetTextAction()).performTextReplacement(":smile")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(EMOJI_SUGGESTIONS_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        assertUsableEditor(originalHeight)
    }

    /** A full automatic draft must not starve the mention list while its text viewport scrolls. */
    @Test
    fun longDraftLeavesAScrollableMentionRow() {
        render()
        val draft = (1..30).joinToString("\n") { "Draft line $it" } + "\n@"
        composeRule.onNode(hasSetTextAction()).performClick().performTextReplacement(draft)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Member 1").assertIsDisplayed()
        composeRule.onNodeWithText("Member 5").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed().performClick()
        assertEquals(draft.dropLast(1) + "@${candidates[4].npub} ", sent)
    }

    /** The same long-draft reserve keeps shortcode suggestions visible instead of measuring them to zero. */
    @Test
    fun longDraftLeavesShortcodeSuggestionsVisible() {
        render(dark = true)
        val draft = (1..30).joinToString("\n") { "Draft line $it" } + "\n:smile"
        composeRule.onNode(hasSetTextAction()).performClick().performTextReplacement(draft)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(EMOJI_SUGGESTIONS_TEST_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(EMOJI_SUGGESTIONS_TEST_TAG).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed()
        capture("long_draft_dark")
    }

    /** Enters the trigger through the production field, retaining real focus and user-edit callbacks. */
    private fun openMentions() {
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("@")
        composeRule.waitForIdle()
    }

    /** Checks actual visible bounds rather than the source's chosen measurement implementation. */
    private fun assertUsableEditor(originalHeight: Float) {
        val field = composeRule.onNode(hasSetTextAction())
        field.assertIsDisplayed().assertIsFocused()
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        send.assertIsDisplayed()
        assertTrue("suggestions must not shrink the editor", pillHeight() >= originalHeight - 1f)
        assertTrue("the active text line must stay visible", field.fetchSemanticsNode().boundsInRoot.height >= 20f)
        assertTrue("Send must keep a usable visible target", send.fetchSemanticsNode().boundsInRoot.height >= 32f)
    }

    /** Scrolls the last offered member into view, then verifies canonical insertion, focus and Send. */
    private fun selectAndSend(member: Int) {
        composeRule.onNodeWithText("Member $member").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.onNodeWithText("Member 1").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        assertEquals("Hi @${candidates[member - 1].npub} ", sent)
    }

    /** Reads the editor independently of the suggestion area. */
    private fun pillHeight(): Float {
        val node = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode()
        return node.size.height.toFloat()
    }

    /** Records only synthetic production-composer content for public review. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_mentions_$name.png")
    }

    /** Hosts the real composer in an IME-sized remainder without inventing keyboard behavior in Robolectric. */
    private fun render(
        memberCount: Int = 23,
        width: Int = 360,
        height: Int = 400,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        replying: Boolean = false,
        editing: Boolean = false,
    ) {
        observed = TextFieldValue("Hi ", TextRange(3))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                    Surface(Modifier.width(width.dp).height(height.dp)) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                replyingTo = replyRecord().takeIf { replying },
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { body, _ -> sent = body },
                                onPickFromGallery = {},
                                onPickDocument = {},
                                initialDraft = observed,
                                onDraftChange = { observed = it },
                                editingMessageId = "edit-message".takeIf { editing },
                                editingInitialText = "Hi ".takeIf { editing },
                                mentionCandidates = candidates.take(memberCount),
                                mentionPickerEnabled = true,
                                modifier = Modifier.testTag(TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Supplies a synthetic reply owner without persistence or network access. */
    private fun replyRecord() =
        AppMessageRecordFfi(
            messageIdHex = "parent",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Parent message",
            contentTokens =
                MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private companion object {
        const val TAG = "mention-composer"
    }
}
