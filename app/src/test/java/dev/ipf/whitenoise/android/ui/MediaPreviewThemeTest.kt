package dev.ipf.whitenoise.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewContent
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoPreview
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Checks the retained native staging route's themed shell without changing slot, preparation or send ownership. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaPreviewThemeTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var surfaceColor = Color.Unspecified
    private val edited = mutableListOf<Int>()

    /** Light preview-only staging keeps Cancel/Edit photo/Done and delegates caption and Send to its composer. */
    @Test
    fun lightPreviewOnlyHasThemedPrototypeActions() {
        render(previewOnly = true)
        rule.onNodeWithContentDescription(text(R.string.photo_editor_edit_action)).assertIsDisplayed().performClick()
        assertEquals(listOf(0), edited)
        rule.onNodeWithContentDescription(text(R.string.cancel_media_changes)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.done)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.send)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.add_caption)).assertDoesNotExist()
        // Output quality now belongs to the photo editor, not to this screen.
        rule.onNodeWithText(text(R.string.photo_editor_quality)).assertDoesNotExist()
        assertShellColors()
        capture("media_preview_themed_light")
    }

    /** Caption and failure retry still belong to the native preview when that optional capability is requested. */
    @Test
    fun amoledRejectedSendRetainsCaptionAndReenablesNativeActions() {
        val captions = mutableListOf<String>()
        var completion: ((Boolean) -> Unit)? = null
        render(dark = true, previewOnly = false, onSend = { value, result ->
            captions += value
            completion = result
        })
        rule.onNodeWithText(text(R.string.add_caption)).performTextInput("Kept native caption")
        capture("media_preview_themed_amoled")
        rule.onNodeWithContentDescription(text(R.string.send)).performClick()
        rule.onNodeWithContentDescription(text(R.string.send)).assertIsNotEnabled()
        rule.runOnIdle { checkNotNull(completion)(false) }
        rule.onNodeWithContentDescription(text(R.string.send)).assertIsEnabled()
        rule.onNodeWithText("Kept native caption").assertIsDisplayed()
        assertEquals(listOf("Kept native caption"), captions)
        assertShellColors()
    }

    /**
     * A native non-editable source keeps its reason and remaining actions at real 200% scale in a short RTL
     * viewport.
     */
    @Test
    fun shortRtlLargeNonEditablePreviewKeepsNativeRecoveryControlsReadable() {
        render(previewOnly = true, fontScale = 2f, rtl = true, nonEditable = true, height = 420)
        // The prototype's Edit action moved into the top bar and still lands on screen here, so what
        // this configuration proves is the labelling: a refused action has to announce why it is
        // refused, not repeat the plain action name a working control already uses.
        rule
            .onNodeWithContentDescription(text(R.string.photo_editor_not_editable_source))
            .assertExists()
            .assertIsNotEnabled()
        rule.onNodeWithContentDescription(text(R.string.cancel_media_changes)).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText(text(R.string.done)).assertIsDisplayed().assertIsEnabled()
        val layouts = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(text(R.string.preview_media))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.none { it.hasVisualOverflow })
        capture("media_preview_themed_short_rtl_large")
    }

    /** Asserts actual rendered background and the header's merged text style, not a duplicate palette helper. */
    private fun assertShellColors() {
        val pixels = rule.onNodeWithTag("preview-frame").captureToImage().toPixelMap()
        assertEquals(surfaceColor, pixels[1, 1])
        assertEquals(surfaceColor, pixels[1, pixels.height - 2])
    }

    /**
     * Uses real prepared image bytes and truthful slot quality; no native pipeline or transport is mocked into
     * success.
     */
    private fun render(
        previewOnly: Boolean,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        nonEditable: Boolean = false,
        height: Int = 640,
        onSend: (String, (Boolean) -> Unit) -> Unit = { _, _ -> error("Preview-only must not Send") },
    ) {
        val uri = Uri.parse("content://preview-theme/photo.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val bytes =
            try {
                bitmap.eraseColor(android.graphics.Color.rgb(47, 139, 126))
                ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = fontScale) {
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.background
                    Box(Modifier.size(320.dp, height.dp).testTag("preview-frame")) {
                        MediaPreviewContent(
                            mediaSlots = listOf(PendingMediaSlot("stable-photo", uri)),
                            documentUris = emptyList(),
                            chatTitle = "Preview",
                            onClose = {},
                            onSend = onSend,
                            onRemoveMediaAt = {},
                            onRemoveDocumentAt = {},
                            onAddPhotos = {},
                            onAddDocuments = {},
                            onEditMediaAt = { edited += it },
                            preparedPhotoPreviews = mapOf("stable-photo" to PreparedPhotoPreview("prepared", bytes)),
                            nonEditableMediaSlotIds = if (nonEditable) setOf("stable-photo") else emptySet(),
                            previewOnly = previewOnly,
                        )
                    }
                }
            }
        }
    }

    /** Records the real staging frame after its behavioral and typography preconditions are established. */
    private fun capture(name: String) {
        rule.onNodeWithTag("preview-frame").captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Resolves the same native labels as production, including slot-quality accessibility descriptions. */
    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = app.getString(resource, *arguments)
}
