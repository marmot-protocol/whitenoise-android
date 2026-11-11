package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
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
 * Invariant gate: pressing Done in the staged-attachment preview removes exactly the entries the user
 * unchecked, through the draft's own index-based removal callbacks. Checked siblings, including repeated
 * picks of one document URI, keep their original identity and order.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w720dp-h780dp-mdpi")
class MediaPreviewExclusionCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** A document entry tagged with its original identity; [uri] alone cannot tell repeated picks apart. */
    private data class TaggedDocument(
        val tag: String,
        val uri: Uri,
    )

    private class Draft(
        var media: List<PendingMediaSlot>,
        var documents: List<TaggedDocument>,
        var closed: Int = 0,
    )

    private val documentA = Uri.parse("content://test/document-a")
    private val documentB = Uri.parse("content://test/document-b")

    private fun document(tag: String): TaggedDocument {
        val uri = if (tag.startsWith("A")) documentA else documentB
        return TaggedDocument(tag, uri)
    }

    private fun renderDraft(
        media: List<PendingMediaSlot>,
        documents: List<TaggedDocument>,
    ): Draft {
        val draft = Draft(media, documents)
        media.forEach { slot ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(slot.uri) {
                ByteArrayInputStream(decodableImageBytes())
            }
        }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                var slots by remember { mutableStateOf(media) }
                var docs by remember { mutableStateOf(documents) }
                MediaPreviewContent(
                    mediaSlots = slots,
                    documentUris = docs.map { it.uri },
                    chatTitle = "Test chat",
                    onClose = { draft.closed++ },
                    onSend = { _, onResult -> onResult(true) },
                    onRemoveMediaAt = { index ->
                        slots = slots.filterIndexed { itemIndex, _ -> itemIndex != index }
                        draft.media = slots
                    },
                    onRemoveDocumentAt = { index ->
                        docs = docs.filterIndexed { itemIndex, _ -> itemIndex != index }
                        draft.documents = docs
                    },
                    onAddPhotos = {},
                    onAddDocuments = {},
                    previewOnly = true,
                )
            }
        }
        return draft
    }

    /** Opens the frame at 1-based [position] from the filmstrip and unchecks it. */
    private fun exclude(position: Int) {
        val thumbnail =
            composeRule.onNodeWithContentDescription(app.getString(R.string.media_preview_position_badge, position))
        thumbnail.performClick()
        composeRule.waitForIdle()
        thumbnail.assertIsSelected()
        val inclusion = composeRule.onNodeWithTag(INCLUSION_TARGET)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching { inclusion.fetchSemanticsNode() }.isSuccess
        }
        inclusion.performClick()
        composeRule.waitForIdle()
    }

    private fun applyExclusions(draft: Draft) {
        // Invoke the button's click action directly: a touch at its bounds can land on the filmstrip overlay.
        composeRule
            .onNode(hasText(app.getString(R.string.done)) and hasClickAction())
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        // Removals apply one per recomposition; Done closes the screen once the last one lands.
        runCatching { composeRule.waitUntil(timeoutMillis = 5_000L) { draft.closed >= 1 } }.onFailure { cause ->
            throw AssertionError(
                "Done never closed the preview: closed=${draft.closed}, " +
                    "media=${draft.media.map { it.id }}, documents=${draft.documents.map { it.tag }}",
                cause,
            )
        }
        assertEquals("Done closes the preview exactly once", 1, draft.closed)
    }

    @Test
    fun excludingTheFirstOfTwoDuplicateDocumentsKeepsTheSecond() {
        val draft = renderDraft(emptyList(), listOf(document("A1"), document("A2")))

        exclude(1)
        applyExclusions(draft)

        assertEquals(listOf("A2"), draft.documents.map { it.tag })
    }

    @Test
    fun excludingTheMiddleOfThreeDuplicateDocumentsKeepsBothNeighbours() {
        val draft = renderDraft(emptyList(), listOf(document("A1"), document("A2"), document("A3")))

        exclude(2)
        applyExclusions(draft)

        assertEquals(listOf("A1", "A3"), draft.documents.map { it.tag })
    }

    @Test
    fun mixedMediaAndDuplicateDocumentsLoseOnlyTheUncheckedEntries() {
        val photo = Uri.parse("content://test/photo")
        val other = Uri.parse("content://test/other-photo")
        val media =
            listOf(
                PendingMediaSlot("slot-0", photo),
                PendingMediaSlot("slot-1", photo),
                PendingMediaSlot("slot-2", other),
            )
        val documents = listOf(document("A1"), document("B1"), document("A2"), document("A3"))
        val draft = renderDraft(media, documents)

        // Send order: slot-0, slot-1, slot-2, A1, B1, A2, A3.
        exclude(2)
        exclude(4)
        exclude(6)
        applyExclusions(draft)

        assertEquals(listOf("slot-0", "slot-2"), draft.media.map { it.id })
        assertEquals(listOf("B1", "A3"), draft.documents.map { it.tag })
    }

    private fun decodableImageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private companion object {
        const val INCLUSION_TARGET = "conversation.media.inclusion.target"
    }
}
