package dev.ipf.whitenoise.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.LocalPreviewMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
    private val previewPng: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.WHITE)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = app.getString(resId, *args)

    private fun uri(n: Int): Uri = Uri.parse("content://test/$n")

    private fun renderPreview(
        initialMedia: List<Uri>,
        onSend: (String, (Boolean) -> Unit) -> Unit = { _, onResult -> onResult(true) },
        metadata: Map<Uri, LocalPreviewMetadata>? = null,
        onEditMediaAt: (Int, Uri) -> Unit = { _, _ -> },
    ) {
        initialMedia.forEach { uri ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
                ByteArrayInputStream(previewPng)
            }
        }
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
                    previewMetadataOverride = metadata,
                    onEditMediaAt = onEditMediaAt,
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
        renderPreview(
            listOf(uri(1)),
            onSend = { caption, onResult ->
                sentCaption = caption
                onResult(true)
            },
        )
        composeRule.onNodeWithText(string(R.string.add_caption)).performTextInput("hello")
        composeRule.onNodeWithContentDescription(string(R.string.send)).performClick()
        composeRule.waitForIdle()
        assertEquals("hello", sentCaption)
    }

    @Test
    fun rejectedSendReEnablesThePreview() {
        var onResult: ((Boolean) -> Unit)? = null
        renderPreview(
            listOf(uri(1)),
            onSend = { _, result -> onResult = result },
        )
        val send = composeRule.onNodeWithContentDescription(string(R.string.send))
        send.performClick()
        composeRule.waitForIdle()
        send.assertIsNotEnabled()
        composeRule.runOnIdle { checkNotNull(onResult).invoke(false) }
        composeRule.waitForIdle()
        send.assertIsEnabled()
    }

    @Test
    fun addTileIsAvailableForGrowingTheSelection() {
        renderPreview(listOf(uri(1)))
        composeRule.onNodeWithContentDescription(string(R.string.media_attachment_add_more)).assertIsDisplayed()
    }

    @Test
    fun tappingAStaticImagePreviewOpensItsEditor() {
        val image = uri(1)
        var edited: Pair<Int, Uri>? = null
        renderPreview(
            initialMedia = listOf(image),
            metadata =
                mapOf(
                    image to
                        LocalPreviewMetadata(
                            isVideo = false,
                            displayName = null,
                            canEdit = true,
                            isUnsupportedImage = false,
                        ),
                ),
            onEditMediaAt = { index, uri -> edited = index to uri },
        )

        composeRule.onNodeWithContentDescription(string(R.string.image_editor_edit)).performClick()

        composeRule.runOnIdle { assertEquals(0 to image, edited) }
    }

    @Test
    fun animatedOrUnsupportedImagesClearlyOfferSendUnchanged() {
        val image = uri(1)
        renderPreview(
            initialMedia = listOf(image),
            metadata =
                mapOf(
                    image to
                        LocalPreviewMetadata(
                            isVideo = false,
                            displayName = null,
                            canEdit = false,
                            isUnsupportedImage = true,
                        ),
                ),
        )

        composeRule.onNodeWithText(string(R.string.image_editor_unsupported)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_edit)).assertDoesNotExist()
    }
}
