package dev.ipf.whitenoise.android.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewContent
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoPreview
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoQuality
import dev.ipf.whitenoise.android.ui.conversation.media.StagedCollectionOrder
import dev.ipf.whitenoise.android.ui.conversation.media.photoApprovalOutputQuality
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

    /** A real one-pixel PNG, so the preview decodes a bitmap instead of holding its spinner. */
    private fun decodableImageBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun renderPreview(
        initialMedia: List<Uri>,
        preparingMedia: Set<Uri> = emptySet(),
        preparedQualities: Map<Uri, PreparedPhotoQuality> = emptyMap(),
        onEditMediaAt: ((Int) -> Unit)? = null,
        onSelectMediaQuality: ((String, MediaQuality) -> Unit)? = null,
        onSend: (String, (Boolean) -> Unit) -> Unit = { _, onResult -> onResult(true) },
        onClose: () -> Unit = {},
        previewOnly: Boolean = false,
    ) {
        val initialSlots = initialMedia.mapIndexed { index, uri -> PendingMediaSlot("slot-$index", uri) }
        val preparingSlotIds = initialSlots.filter { it.uri in preparingMedia }.mapTo(mutableSetOf()) { it.id }
        val qualitiesBySlot =
            initialSlots.mapNotNull { slot -> preparedQualities[slot.uri]?.let { slot.id to it } }.toMap()
        initialMedia.forEach { stagedUri ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(stagedUri) {
                ByteArrayInputStream(decodableImageBytes())
            }
        }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                var media by remember { mutableStateOf(initialSlots) }
                MediaPreviewContent(
                    mediaSlots = media,
                    documentUris = emptyList(),
                    chatTitle = "Test chat",
                    onClose = onClose,
                    onSend = onSend,
                    onRemoveMediaAt = { index ->
                        media = media.toMutableList().apply { if (index in indices) removeAt(index) }
                    },
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                    preparingPhotoSlotIds = preparingSlotIds,
                    preparedPhotoQualities = qualitiesBySlot,
                    onEditMediaAt = onEditMediaAt,
                    onSelectMediaQuality = onSelectMediaQuality,
                    previewOnly = previewOnly,
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
    fun uncheckingAFrameAndPressingDoneRemovesItAndRenumbers() {
        // The shelf tap is the only production entry into this screen and it always previews.
        renderPreview(listOf(uri(1), uri(2), uri(3)), previewOnly = true)
        val inclusionTargets = composeRule.onAllNodesWithTag("conversation.media.inclusion.target")
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            inclusionTargets.fetchSemanticsNodes().isNotEmpty()
        }
        inclusionTargets.onFirst().performClick()
        composeRule.onNodeWithText(string(R.string.done)).performClick()
        // Exclusions apply one frame at a time across recompositions, and a single idle pass
        // returned early on the Play flavour's dispatcher, so wait for the removal itself.
        val removedBadge = string(R.string.media_preview_position_badge, 3)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithContentDescription(removedBadge).fetchSemanticsNodes().isEmpty()
        }
        composeRule
            .onNodeWithContentDescription(string(R.string.media_preview_position_badge, 1))
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun cancelUsesTheMediaChangesDescriptionAndKeepsEveryFrame() {
        var closed = 0
        renderPreview(listOf(uri(1), uri(2)), onClose = { closed++ })
        composeRule.onNodeWithContentDescription(string(R.string.cancel_media_changes)).performClick()
        assertEquals(1, closed)
        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 2)).assertIsDisplayed()
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
    fun preparingPhotoDisablesSendUntilItsDraftArtifactIsReady() {
        val staged = uri(1)
        renderPreview(listOf(staged), preparingMedia = setOf(staged))

        composeRule.onNodeWithContentDescription(string(R.string.send)).assertIsNotEnabled()
    }

    @Test
    fun editablePhotoShowsEditAction() {
        val staged = uri(1)
        var editedIndex: Int? = null
        renderPreview(
            initialMedia = listOf(staged),
            onEditMediaAt = { editedIndex = it },
        )

        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_edit_action))
            .performClick()
        assertEquals(0, editedIndex)
    }

    @Test
    fun selectedLowAndOriginalTiersKeepTheirTruthfulOutputProfiles() {
        assertEquals(
            MediaQuality.Low,
            photoApprovalOutputQuality(MediaQuality.Low, MediaQuality.Standard),
        )
        assertEquals(
            MediaQuality.Original,
            photoApprovalOutputQuality(MediaQuality.Original, MediaQuality.High),
        )
        assertEquals(
            MediaQuality.Standard,
            photoApprovalOutputQuality(MediaQuality.High, MediaQuality.Standard),
        )
        assertEquals(
            MediaQuality.High,
            photoApprovalOutputQuality(MediaQuality.Standard, MediaQuality.High),
        )
    }

    @Test
    fun duplicateUriOccurrencesKeepIndependentPreviewPositions() {
        val duplicate = uri(1)
        var editedIndex: Int? = null
        renderPreview(
            initialMedia = listOf(duplicate, duplicate),
            onEditMediaAt = { editedIndex = it },
        )

        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 2)).performClick()
        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_edit_action))
            .performClick()
        assertEquals(1, editedIndex)
    }

    @Test
    fun editedPhotoReturnKeepsThePreviouslySelectedAlbumPosition() {
        val slots = listOf(PendingMediaSlot("slot-0", uri(1)), PendingMediaSlot("slot-1", uri(2)))
        slots.forEach { slot ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(slot.uri) {
                ByteArrayInputStream(ByteArray(1))
            }
        }
        var previewVisible by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                val stateHolder = rememberSaveableStateHolder()
                if (previewVisible) {
                    stateHolder.SaveableStateProvider("preview") {
                        MediaPreviewContent(
                            mediaSlots = slots,
                            documentUris = emptyList(),
                            chatTitle = "Test chat",
                            onClose = {},
                            onSend = { _, onResult -> onResult(true) },
                            onRemoveMediaAt = {},
                            onRemoveDocumentAt = {},
                            onAddPhotos = {},
                            onAddDocuments = {},
                        )
                    }
                }
            }
        }
        val secondBadge = string(R.string.media_preview_position_badge, 2)
        composeRule.onNodeWithContentDescription(secondBadge).performClick().assertIsSelected()

        composeRule.runOnIdle { previewVisible = false }
        composeRule.runOnIdle { previewVisible = true }

        composeRule.onNodeWithContentDescription(secondBadge).assertIsSelected()
    }

    @Test
    fun preparedArtifactReplacesHeroAndThumbnailForTheSameStableSlot() {
        val staged = uri(1)
        val slot = PendingMediaSlot("stable-slot", staged)
        val original = imageBytes(Color.BLUE)
        val edited = imageBytes(Color.RED)
        shadowOf(app.contentResolver).registerInputStreamSupplier(staged) {
            ByteArrayInputStream(original)
        }
        var prepared by mutableStateOf<Map<String, PreparedPhotoPreview>>(emptyMap())
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                MediaPreviewContent(
                    mediaSlots = listOf(slot),
                    documentUris = emptyList(),
                    chatTitle = "Test chat",
                    onClose = {},
                    onSend = { _, onResult -> onResult(true) },
                    onRemoveMediaAt = {},
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                    preparedPhotoPreviews = prepared,
                )
            }
        }

        val preparedDescription = string(R.string.photo_editor_prepared)
        composeRule
            .onAllNodesWithContentDescription(preparedDescription, useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.runOnIdle {
            prepared = mapOf(slot.id to PreparedPhotoPreview(revision = "edited", bytes = edited))
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithContentDescription(preparedDescription, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == 2
        }
        composeRule
            .onAllNodesWithContentDescription(preparedDescription, useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test
    fun addTileIsAvailableForGrowingTheSelection() {
        renderPreview(listOf(uri(1)))
        composeRule.onNodeWithContentDescription(string(R.string.media_attachment_add_more)).assertIsDisplayed()
    }

    /** The latest shelf a bulk edit published, in send order. */
    private var collectionOrder: StagedCollectionOrder? = null

    /** Renders [mediaCount] photos then [documentCount] documents with bulk editing wired to local state. */
    private fun renderCollection(
        mediaCount: Int,
        documentCount: Int,
    ) {
        val slots = List(mediaCount) { PendingMediaSlot("slot-$it", uri(it)) }
        val documents = List(documentCount) { Uri.parse("content://test/doc/report-$it.pdf") }
        slots.forEach { slot ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(slot.uri) {
                ByteArrayInputStream(decodableImageBytes())
            }
        }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                var media by remember { mutableStateOf(slots) }
                var docs by remember { mutableStateOf(documents) }
                MediaPreviewContent(
                    mediaSlots = media,
                    documentUris = docs,
                    chatTitle = "Test chat",
                    onClose = {},
                    onSend = { _, onResult -> onResult(true) },
                    onRemoveMediaAt = {},
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                    onCollectionChange = { order ->
                        collectionOrder = order
                        media = order.media
                        docs = order.documents
                    },
                )
            }
        }
    }

    /** The selection summary node's current text. */
    private fun assertCount(
        selected: Int,
        total: Int,
    ) {
        composeRule
            .onNodeWithTag("conversation.media.collection.count")
            .assertTextEquals(string(R.string.media_collection_selected_count, selected, total))
    }

    /** One hundred mixed items open on a lazily composed grid that shows `selected / total`. */
    @Test
    fun hundredMixedItemsOpenOnAVirtualizedGridWithCounts() {
        renderCollection(mediaCount = 80, documentCount = 20)
        composeRule.onNodeWithTag("conversation.media.collection.grid").assertIsDisplayed()
        composeRule
            .onNodeWithText(app.resources.getQuantityString(R.plurals.media_collection_total, 100, 100))
            .assertIsDisplayed()
        assertCount(0, 100)
        val composed = composeRule.onAllNodesWithTag("conversation.media.collection.cell").fetchSemanticsNodes().size
        assertTrue("only visible cells compose, got $composed", composed in 1 until 100)
    }

    /** Select all, clear selection and remove selected act on the whole collection and keep counts honest. */
    @Test
    fun bulkSelectClearAndRemoveSelected() {
        renderCollection(mediaCount = 12, documentCount = 3)
        composeRule.onNodeWithText(string(R.string.media_collection_select_all)).performClick()
        assertCount(15, 15)
        composeRule.onNodeWithText(string(R.string.media_collection_clear_selection)).performClick()
        assertCount(0, 15)
        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_photo, 2, 15))
            .performTouchInput { longClick() }
        assertCount(1, 15)
        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_photo, 2, 15))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.media_collection_selected),
                ),
            )
        composeRule.onNodeWithText(string(R.string.media_collection_remove_selected)).performClick()
        composeRule.waitForIdle()
        assertEquals((0 until 12).filter { it != 1 }.map { "slot-$it" }, collectionOrder?.media?.map { it.id })
        assertCount(0, 14)
    }

    /** Move later swaps a selected item with its neighbour; it keeps its identity and its selection. */
    @Test
    fun moveLaterReordersWithStableIdentity() {
        renderCollection(mediaCount = 12, documentCount = 0)
        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_photo, 1, 12))
            .performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.media_collection_move_later)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("slot-1", "slot-0", "slot-2"), collectionOrder?.media?.take(3)?.map { it.id })
        assertCount(1, 12)
        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_photo, 2, 12))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    /** Documents announce their name and position; every cell offers move actions to accessibility services. */
    @Test
    fun cellsCarryTalkBackLabelsAndMoveActions() {
        renderCollection(mediaCount = 80, documentCount = 20)
        composeRule.onNodeWithTag("conversation.media.collection.grid").performScrollToIndex(99)
        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_document, "report-19.pdf", 100, 100))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))
    }

    /** A tap with nothing selected opens the single-item preview at that item; the toggle returns to the grid. */
    @Test
    fun tappingACellOpensTheSingleItemPreview() {
        renderCollection(mediaCount = 12, documentCount = 0)
        composeRule.onNodeWithContentDescription(string(R.string.media_collection_item_photo, 3, 12)).performClick()
        composeRule.onNodeWithTag("conversation.media.preview.pager").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.media_collection_show_grid)).performClick()
        composeRule.onNodeWithTag("conversation.media.collection.grid").assertIsDisplayed()
    }

    /** At a compact height the grid, its bulk bar and the send bar all still fit on screen. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun compactHeightKeepsGridCountAndSendVisible() {
        renderCollection(mediaCount = 40, documentCount = 5)
        composeRule.onNodeWithTag("conversation.media.collection.count").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation.media.collection.grid").assertIsDisplayed()
    }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
