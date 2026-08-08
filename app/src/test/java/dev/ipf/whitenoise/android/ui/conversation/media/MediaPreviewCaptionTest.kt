package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaPreviewCaptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val photoUri = Uri.parse("content://test/photo-1")

    @Before
    fun registerPreviewImage() {
        val bytes =
            ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        shadowOf(app.contentResolver).registerInputStreamSupplier(photoUri) {
            ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun composerDraftSeedsTheCaptionField() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaPreviewContent(
                    mediaUris = listOf(photoUri),
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
                    mediaUris = listOf(photoUri),
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
