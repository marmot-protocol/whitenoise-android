package dev.ipf.whitenoise.android.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral coverage for the staged-media preview: badge numbering follows
 * send order, removal renumbers and re-anchors the selection, and the caption
 * rides the send callback.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaPreviewContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = app.getString(resId, *args)

    private fun uri(n: Int): Uri = Uri.parse("content://test/$n")

    private fun renderPreview(
        initialMedia: List<Uri>,
        onSend: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                var media by remember { mutableStateOf(initialMedia) }
                MediaPreviewContent(
                    mediaUris = media,
                    documentUris = emptyList(),
                    chatTitle = "Test chat",
                    onClose = {},
                    onSend = onSend,
                    onRemoveMediaAt = { index ->
                        media = media.toMutableList().apply { if (index in indices) removeAt(index) }
                    },
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                )
            }
        }
    }

    @Test
    fun badgesNumberItemsInSendOrder() {
        renderPreview(listOf(uri(1), uri(2), uri(3)))
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 2)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 3)).assertIsDisplayed()
    }

    @Test
    fun removingCurrentItemRenumbersAndKeepsASelection() {
        renderPreview(listOf(uri(1), uri(2), uri(3)))
        composeRule.onNodeWithContentDescription(string(R.string.media_attachment_remove)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 3)).assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(string(R.string.media_preview_position_badge, 1))
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun tappingAThumbnailMovesTheSelection() {
        renderPreview(listOf(uri(1), uri(2)))
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 2)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 2)).assertIsSelected()
    }

    @Test
    fun sendCarriesTheTypedCaption() {
        var sentCaption: String? = null
        renderPreview(listOf(uri(1)), onSend = { sentCaption = it })
        composeRule.onNodeWithText(string(R.string.add_caption)).performTextInput("hello")
        composeRule.onNodeWithContentDescription(string(R.string.send)).performClick()
        composeRule.waitForIdle()
        assertEquals("hello", sentCaption)
    }

    @Test
    fun addTileIsAvailableForGrowingTheSelection() {
        renderPreview(listOf(uri(1)))
        composeRule.onNodeWithContentDescription(string(R.string.media_attachment_add_more)).assertIsDisplayed()
    }
}
