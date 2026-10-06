package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the production presentation mapper with real, media-only projected messages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w400dp-h800dp-mdpi")
class KeptMessageMediaTest {
    @get:Rule val composeRule = createComposeRule()

    /** A file without a caption is recognizable instead of leaving an empty card. */
    @Test
    fun mediaOnlyFileShowsItsName() {
        render(keptMediaTestMessage("application/pdf", SWIPE_TEST_FILE_NAME))
        composeRule.onNodeWithText(SWIPE_TEST_FILE_NAME).assertIsDisplayed()
        composeRule.onNodeWithText("File · Open the original to view").assertIsDisplayed()
    }

    /** An image remains recognizable with its caption and without a cached thumbnail. */
    @Test
    fun captionedImageShowsTypeNameAndCaption() {
        render(keptMediaTestMessage("image/png", "Sketch.png", "The revised sketch"))
        composeRule.onNodeWithText("Sketch.png").assertIsDisplayed()
        composeRule.onNodeWithText("Image · Open the original to view").assertIsDisplayed()
        composeRule.onNodeWithText("The revised sketch").assertIsDisplayed()
    }

    /** Audio-only messages identify their type without offering unimplemented playback controls. */
    @Test
    fun audioOnlyMessageIsRecognizable() {
        render(keptMediaTestMessage("audio/ogg", "Voice note.ogg"))
        composeRule.onNodeWithText("Voice note.ogg").assertIsDisplayed()
        composeRule.onNodeWithText("Audio · Open the original to view").assertIsDisplayed()
    }

    /** Nameless video media has a localized fallback title. */
    @Test
    fun unnamedVideoHasAUsefulTitle() {
        render(keptMediaTestMessage("video/mp4", ""))
        composeRule.onNodeWithText("Video", substring = false).assertIsDisplayed()
    }

    /** Native tombstones remove the kept content immediately, even if the row still exists. */
    @Test
    fun deletionPrunesTheKeptMedia() {
        var item by mutableStateOf(keptMediaTestMessage("image/png", "Sketch.png"))
        composeRule.setContent { WhiteNoiseTheme { KeptMediaTestHost(item) } }
        composeRule.onNodeWithText("Sketch.png").assertIsDisplayed()
        composeRule.runOnIdle { item = item.copy(projected = item.projected!!.copy(deleted = true)) }
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertDoesNotExist()
        composeRule.runOnIdle { item = item.copy(projected = item.projected!!.copy(deleted = false)) }
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertDoesNotExist()
    }

    /** Expiry or removal from the authoritative timeline clears the reference, not just the preview. */
    @Test
    fun missingRowPrunesTheKeptMedia() {
        var item by mutableStateOf<TimelineMessage?>(keptMediaTestMessage("audio/ogg", "Voice.ogg"))
        composeRule.setContent { WhiteNoiseTheme { KeptMediaTestHost(item) } }
        composeRule.onNodeWithText("Voice.ogg").assertIsDisplayed()
        composeRule.runOnIdle { item = null }
        composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertDoesNotExist()
    }

    /** The existing jump action continues to identify the exact kept account, conversation and message. */
    @Test
    fun goToOriginalUsesTheKeptTarget() {
        val item = keptMediaTestMessage("application/pdf", SWIPE_TEST_FILE_NAME)
        var opened: KeptMessageKey? = null
        composeRule.setContent { WhiteNoiseTheme { KeptMediaTestHost(item, onOpen = { opened = it }) } }
        composeRule.onNodeWithTag(KEPT_MESSAGE_MENU_TAG).performClick()
        composeRule.onNodeWithText("Go to Message").performClick()
        assertEquals(KeptMessageKey(SWIPE_TEST_ACCOUNT_REF, SWIPE_TEST_GROUP_ID, SWIPE_TEST_MESSAGE_ID), opened)
    }

    /** Installs one fixture in the real card. */
    private fun render(item: TimelineMessage) {
        composeRule.setContent { WhiteNoiseTheme { KeptMediaTestHost(item) } }
    }
}
