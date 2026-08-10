package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaPreviewCaptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composerDraftSeedsTheCaptionField() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaPreviewContent(
                    mediaSlots = listOf(PendingMediaSlot("slot-1", Uri.parse("content://test/photo-1"))),
                    documentUris = emptyList(),
                    chatTitle = "Chat",
                    onClose = {},
                    onSend = { _, _ -> },
                    onRemoveMediaAt = {},
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                    initialCaption = "typed before attaching",
                )
            }
        }

        composeRule.onNodeWithText("typed before attaching").assertExists()
    }

    @Test
    fun captionStaysEmptyWithoutADraft() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaPreviewContent(
                    mediaSlots = listOf(PendingMediaSlot("slot-1", Uri.parse("content://test/photo-1"))),
                    documentUris = emptyList(),
                    chatTitle = "Chat",
                    onClose = {},
                    onSend = { _, _ -> },
                    onRemoveMediaAt = {},
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                )
            }
        }

        composeRule.onNodeWithText("typed before attaching").assertDoesNotExist()
    }
}
