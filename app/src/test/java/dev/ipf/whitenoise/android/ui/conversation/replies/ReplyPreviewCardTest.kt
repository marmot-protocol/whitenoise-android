package dev.ipf.whitenoise.android.ui.conversation.replies

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReplyPreviewCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun unavailableOriginalIsPersistentClickableAndDoesNotExposeAnIdentity() {
        var clicks = 0
        render(
            senderTitle = "missing-parent-id",
            body = "",
            mediaKind = ReplyMediaKind.None,
            originalUnavailable = true,
            onClick = { clicks++ },
        )

        composeRule.onNodeWithText(context.getString(R.string.reply)).assertIsDisplayed()
        composeRule
            .onNode(
                hasText(context.getString(R.string.reply)) and
                    hasText(context.getString(R.string.toast_original_message_unavailable)),
            ).assertHasClickAction()
        composeRule
            .onNodeWithText(context.getString(R.string.toast_original_message_unavailable))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
            .assertIsDisplayed()
        composeRule.onNodeWithText("missing-parent-id").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun typedDocumentsUseTheSharedResolverAndKeepTheFilenamePrimary() {
        render(
            senderTitle = "Alice",
            body = "File",
            mediaKind = ReplyMediaKind.Document,
            mediaFileName = "release.apk",
            mediaType = "application/vnd.android.package-archive",
        )

        composeRule.onNodeWithText("release.apk · APK").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.reply_media_document)).assertDoesNotExist()
    }

    @Test
    fun legacyOpaqueDocumentKeepsTheLocalizedCoarseFallback() {
        render(
            senderTitle = "Alice",
            body = "File",
            mediaKind = ReplyMediaKind.Document,
        )

        composeRule.onNodeWithText(context.getString(R.string.reply_media_document)).assertIsDisplayed()
    }

    @Test
    fun establishedPhotoVideoAndVoiceLabelsRemainUnchanged() {
        val cases =
            listOf(
                ReplyMediaKind.Photo to R.string.reply_media_photo,
                ReplyMediaKind.Video to R.string.reply_media_video,
                ReplyMediaKind.Voice to R.string.reply_media_voice,
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    cases.forEach { (kind, _) ->
                        ReplyPreviewCard(
                            senderTitle = "Alice",
                            isOwn = false,
                            body = "ignored",
                            mediaKind = kind,
                            onClick = null,
                            onDismiss = null,
                        )
                    }
                }
            }
        }

        cases.forEach { (_, label) ->
            composeRule.onNodeWithText(context.getString(label)).assertIsDisplayed()
        }
    }

    @Test
    fun safeExtensionAndMimeResolutionCoverPdfMarkdownAndUnknownFiles() {
        val cases =
            listOf(
                Triple("notes.pdf", "application/pdf", "notes.pdf · PDF"),
                Triple("README.md", "text/markdown", "README.md · Markdown"),
                Triple("board.pcb", "application/vnd.acme.machine-part", "board.pcb · PCB"),
            )

        cases.forEach { (fileName, mediaType, expected) ->
            val presentation = resolveAttachmentPresentation(mediaType, fileName)
            val actual = replyAttachmentPreviewText(fileName, presentation)
            assertEquals(expected, actual)
            assertTrue(actual?.startsWith(fileName) == true)
        }
        val hostile =
            replyAttachmentPreviewText(
                "../../report.\u202Eapk",
                resolveAttachmentPresentation("application/octet-stream", "../../report.\u202Eapk"),
            )
        assertEquals("report.apk", hostile)
        assertFalse(hostile.orEmpty().contains(".."))
    }

    private fun render(
        senderTitle: String,
        body: String,
        mediaKind: ReplyMediaKind,
        mediaFileName: String? = null,
        mediaType: String? = null,
        originalUnavailable: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ReplyPreviewCard(
                    senderTitle = senderTitle,
                    isOwn = false,
                    body = body,
                    mediaKind = mediaKind,
                    mediaFileName = mediaFileName,
                    mediaType = mediaType,
                    originalUnavailable = originalUnavailable,
                    onClick = onClick,
                    onDismiss = null,
                )
            }
        }
    }
}
