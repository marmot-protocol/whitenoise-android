package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageAttachmentSaveTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun saveActionIsShownForAttachmentAndInvokesCallback() {
        var saveClicks = 0
        renderActionMenu(canSave = true, onSave = { saveClicks++ })

        composeRule.onNodeWithText(string(R.string.shared_media_save)).assertIsDisplayed().performClick()

        assertEquals(1, saveClicks)
    }

    @Test
    fun saveActionIsHiddenWithoutAttachment() {
        renderActionMenu(canSave = false)

        composeRule.onNodeWithText(string(R.string.shared_media_save)).assertDoesNotExist()
    }

    @Test
    fun videoSaveReusesTheMaterializedFile() {
        val saveBody = saveMediaBody()

        assertTrue(
            "video saves must stream the materialized file instead of resolving another ByteArray",
            "MediaReferenceSupport.isVideoMedia(resolvedReference)" in saveBody &&
                "materializeVideoAttachment(" in saveBody &&
                "saveVideoToGallery(" in saveBody,
        )
    }

    @Test
    fun documentSaveReusesTheMaterializedFile() {
        val saveBody = saveMediaBody()

        assertTrue(
            "document saves must join the transfer and stream its reusable artifact",
            "materializeDocumentAttachment(" in saveBody &&
                "requestAttachmentTransfer(" in saveBody &&
                "saveDocumentToDownloads(" in saveBody,
        )
    }

    @Test
    fun saveSurvivesNavigationAndRejectsConcurrentLaunches() {
        val saveBody = saveAttachmentsBody()

        assertTrue(
            "attachment saves must survive navigation and ignore a second invocation while active",
            "if (mediaReferences.isEmpty() || attachmentSaveInFlight) return" in saveBody &&
                "attachmentSaveInFlight = true" in saveBody &&
                "appState.launchMutation" in saveBody &&
                Regex("finally\\s*\\{\\s*attachmentSaveInFlight = false").containsMatchIn(saveBody),
        )
    }

    @Test
    fun saveOutcomeDistinguishesCompletePartialAndFailedAlbums() {
        assertEquals(
            MessageAttachmentSaveOutcome.Complete,
            MessageAttachmentSaveOutcome.from(savedCount = 2, totalCount = 2),
        )
        assertEquals(
            MessageAttachmentSaveOutcome.Partial,
            MessageAttachmentSaveOutcome.from(savedCount = 1, totalCount = 2),
        )
        assertEquals(
            MessageAttachmentSaveOutcome.Failed,
            MessageAttachmentSaveOutcome.from(savedCount = 0, totalCount = 2),
        )
    }

    private fun renderActionMenu(
        canSave: Boolean,
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageActionMenu(
                    expanded = true,
                    anchorBoundsInWindow = null,
                    anchorWindowYPx = 0f,
                    canReply = false,
                    canReact = false,
                    canDelete = false,
                    canEdit = false,
                    canForward = false,
                    canSelect = false,
                    canCopyText = false,
                    canSpeak = false,
                    canSelectText = false,
                    canSave = canSave,
                    quickReactionEmojis = emptyList(),
                    onDismissRequest = {},
                    onReact = {},
                    onOpenEmojiPicker = {},
                    onReply = {},
                    onEdit = {},
                    onForward = {},
                    onSelect = {},
                    onSelectText = {},
                    onCopyText = {},
                    onSpeak = {},
                    onSave = onSave,
                    onInfo = {},
                    onDelete = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(resId: Int): String = ApplicationProvider.getApplicationContext<Application>().getString(resId)

    private fun messageBubbleSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).first(File::exists).readText()

    private fun messageAttachmentSaveSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MessageAttachmentSave.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MessageAttachmentSave.kt"),
        ).first(File::exists).readText()

    private fun saveAttachmentsBody(): String = messageBubbleSource().functionBody("saveAttachments")

    private fun saveMediaBody(): String = messageAttachmentSaveSource().functionBody("saveMessageMediaAttachments")
}
