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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewContent
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoPreview
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoQuality
import dev.ipf.whitenoise.android.ui.conversation.media.photoApprovalOutputQuality
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

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = app.getString(resId, *args)

    private fun uri(n: Int): Uri = Uri.parse("content://test/$n")

    private fun renderPreview(
        initialMedia: List<Uri>,
        preparingMedia: Set<Uri> = emptySet(),
        preparedLabels: Map<Uri, String> = emptyMap(),
        preparedQualities: Map<Uri, PreparedPhotoQuality> = emptyMap(),
        onEditMediaAt: ((Int) -> Unit)? = null,
        onSelectMediaQualityAt: ((Int, MediaQuality) -> Unit)? = null,
        onSend: (String, (Boolean) -> Unit) -> Unit = { _, onResult -> onResult(true) },
    ) {
        val initialSlots = initialMedia.mapIndexed { index, uri -> PendingMediaSlot("slot-$index", uri) }
        val preparingSlotIds = initialSlots.filter { it.uri in preparingMedia }.mapTo(mutableSetOf()) { it.id }
        val labelsBySlot = initialSlots.mapNotNull { slot -> preparedLabels[slot.uri]?.let { slot.id to it } }.toMap()
        val qualitiesBySlot =
            initialSlots.mapNotNull { slot -> preparedQualities[slot.uri]?.let { slot.id to it } }.toMap()
        initialMedia.forEach { stagedUri ->
            shadowOf(app.contentResolver).registerInputStreamSupplier(stagedUri) {
                ByteArrayInputStream(ByteArray(1))
            }
        }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                var media by remember { mutableStateOf(initialSlots) }
                MediaPreviewContent(
                    mediaSlots = media,
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
                    preparingPhotoSlotIds = preparingSlotIds,
                    preparedPhotoLabels = labelsBySlot,
                    preparedPhotoQualities = qualitiesBySlot,
                    onEditMediaAt = onEditMediaAt,
                    onSelectMediaQualityAt = onSelectMediaQualityAt,
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
    fun preparingPhotoDisablesSendUntilItsDraftArtifactIsReady() {
        val staged = uri(1)
        renderPreview(listOf(staged), preparingMedia = setOf(staged))

        composeRule.onNodeWithContentDescription(string(R.string.send)).assertIsNotEnabled()
    }

    @Test
    fun editablePhotoShowsEditActionAndEffectiveAttachmentQuality() {
        val staged = uri(1)
        var editedIndex: Int? = null
        val quality = "Standard · 2048 × 1536"
        renderPreview(
            initialMedia = listOf(staged),
            preparedLabels = mapOf(staged to quality),
            onEditMediaAt = { editedIndex = it },
        )

        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_edit_with_quality, quality))
            .performClick()
        assertEquals(0, editedIndex)
    }

    @Test
    fun photoQualityIsChosenFromApprovalWithTwoClearOptions() {
        val staged = uri(1)
        var selected: Pair<Int, MediaQuality>? = null
        renderPreview(
            initialMedia = listOf(staged),
            preparedQualities =
                mapOf(
                    staged to
                        PreparedPhotoQuality(
                            selectedQuality = MediaQuality.Standard,
                            standardDimensions = "2048 × 1536",
                            hdDimensions = "4096 × 3072",
                        ),
                ),
            onSelectMediaQualityAt = { index, quality -> selected = index to quality },
        )

        val standardDescription =
            string(
                R.string.photo_editor_announcement_quality,
                string(R.string.photo_editor_quality_standard),
            )
        composeRule.onAllNodesWithText(string(R.string.photo_editor_quality_hd)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(standardDescription).performClick()
        composeRule.onNodeWithText(string(R.string.photo_editor_quality)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.photo_editor_quality_standard)).assertIsSelected()
        composeRule.onNodeWithText("2048 × 1536").assertIsDisplayed()
        composeRule.onNodeWithText("4096 × 3072").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.photo_editor_quality_high)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.photo_editor_quality_original)).assertCountEquals(0)

        composeRule.onNodeWithText(string(R.string.photo_editor_quality_hd)).performClick()

        assertEquals(0 to MediaQuality.High, selected)
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
            preparedLabels = mapOf(duplicate to "Standard"),
            onEditMediaAt = { editedIndex = it },
        )

        composeRule.onNodeWithContentDescription(string(R.string.media_preview_position_badge, 2)).performClick()
        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_edit_with_quality, "Standard"))
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
