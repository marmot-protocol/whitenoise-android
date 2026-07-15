package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import android.content.ClipData
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageTextSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectTextActionIsAdjacentToCopyAndInvokesItsCallback() {
        var selectTextClicks = 0
        renderActionMenu(canCopyText = true, onSelectText = { selectTextClicks++ })

        composeRule.onNodeWithText(string(R.string.select_text)).assertIsDisplayed().performClick()

        assertEquals(1, selectTextClicks)
        composeRule.onNodeWithText(string(R.string.copy_text)).assertIsDisplayed()
    }

    @Test
    fun textActionsAreHiddenWhenTheBubbleHasNoText() {
        renderActionMenu(canCopyText = false)

        composeRule.onNodeWithText(string(R.string.select_text)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.copy_text)).assertDoesNotExist()
    }

    @Test
    fun selectionClipboardExitsModeAfterTheCopyCompletes() =
        runTest {
            val delegate = RecordingClipboard()
            var copyCompletions = 0
            val clipboard = ExitOnCopyClipboard(delegate) { copyCompletions++ }
            val selectedText = ClipEntry(ClipData.newPlainText("message selection", "partial text"))

            clipboard.setClipEntry(selectedText)

            assertEquals(selectedText, delegate.clipEntry)
            assertEquals(1, copyCompletions)
        }

    @Test
    fun nearestNonWhitespaceOffsetKeepsThePressedWord() {
        assertEquals(7, nearestNonWhitespaceOffset("hello world", 7))
    }

    @Test
    fun nearestNonWhitespaceOffsetSeedsAtTheClosestWordAcrossWhitespace() {
        assertEquals(8, nearestNonWhitespaceOffset("hello   world", 6))
        assertEquals(8, nearestNonWhitespaceOffset("hello   world", 7))
    }

    @Test
    fun nearestNonWhitespaceOffsetReturnsNullForTextWithoutAWord() {
        assertEquals(null, nearestNonWhitespaceOffset("   \n", 1))
        assertEquals(null, nearestNonWhitespaceOffset("", 0))
    }

    @Test
    fun selectionSeedUsesThePressedWordAcrossMultipleTextNodes() {
        val firstKey = Any()
        val secondKey = Any()
        val layouts = mutableMapOf<Any, SelectableTextLayout>()
        composeRule.setContent {
            WhiteNoiseTheme {
                Row {
                    CaptureSelectableText(firstKey, "alpha") { layouts[firstKey] = it }
                    Spacer(Modifier.width(12.dp))
                    CaptureSelectableText(secondKey, "beta gamma") { layouts[secondKey] = it }
                }
            }
        }
        composeRule.waitForIdle()

        val second = checkNotNull(layouts[secondKey])
        val gammaBounds = second.layoutResult.getBoundingBox(6)
        val press = second.coordinates.localToWindow(gammaBounds.center)

        assertEquals(TextRange(10, 15), textSelectionSeedRange(layouts.values, press))
    }

    @Composable
    private fun CaptureSelectableText(
        key: Any,
        text: String,
        onCaptured: (SelectableTextLayout) -> Unit,
    ) {
        val tracker = remember { SelectableTextLayoutTracker() }

        fun reportIfReady() {
            val layoutResult = tracker.layoutResult ?: return
            val coordinates = tracker.coordinates ?: return
            onCaptured(SelectableTextLayout(key, layoutResult, coordinates))
        }

        Text(
            text = text,
            modifier =
                Modifier.onGloballyPositioned {
                    tracker.coordinates = it
                    reportIfReady()
                },
            onTextLayout = {
                tracker.layoutResult = it
                reportIfReady()
            },
        )
    }

    private fun renderActionMenu(
        canCopyText: Boolean,
        onSelectText: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageActionMenu(
                    expanded = true,
                    anchorWindowYPx = 0f,
                    alignEnd = false,
                    canReply = false,
                    canReact = false,
                    canDeleteForMe = false,
                    canDeleteForEveryone = false,
                    canEdit = false,
                    canForward = false,
                    canSelect = false,
                    canCopyText = canCopyText,
                    quickReactionEmojis = emptyList(),
                    onDismissRequest = {},
                    onReact = {},
                    onOpenEmojiPicker = {},
                    onReply = {},
                    onEdit = {},
                    onForward = {},
                    onSelect = {},
                    onSelectText = onSelectText,
                    onCopyText = {},
                    onInfo = {},
                    onDeleteForMe = {},
                    onDeleteForEveryone = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(resId)

    private class RecordingClipboard : Clipboard {
        var clipEntry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = clipEntry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            this.clipEntry = clipEntry
        }
    }
}
