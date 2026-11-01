@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionItem
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.PlannedCollectionItem
import dev.ipf.whitenoise.android.state.planAttachmentChunks
import dev.ipf.whitenoise.android.state.withChunkState
import dev.ipf.whitenoise.android.ui.conversation.AttachmentCollectionPanelActions
import dev.ipf.whitenoise.android.ui.conversation.AttachmentCollectionProgress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the large-collection staging grid and the collection progress panel (#2576) across themes,
 * right-to-left layout and large fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class AttachmentCollectionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The grid of a 45-item mixed collection with one item selected, light theme. */
    @Test
    fun collectionGridLight() = captureGrid("light")

    /** The same grid in the dark theme. */
    @Test
    fun collectionGridDark() = captureGrid("dark", dark = true)

    /** Large type in right-to-left layout keeps the bulk bar and badges readable. */
    @Test
    fun collectionGridDarkLargeRtl() = captureGrid("dark_large_rtl", dark = true, rtl = true, fontScale = 1.6f)

    /** Running, blocked and restored-paused panels, light theme. */
    @Test
    fun collectionPanelsLight() = capturePanels("light")

    /** The same panels in the dark theme. */
    @Test
    fun collectionPanelsDark() = capturePanels("dark", dark = true)

    /** Large type in right-to-left layout wraps the panel actions instead of clipping them. */
    @Test
    fun collectionPanelsLargeRtl() = capturePanels("large_rtl", rtl = true, fontScale = 1.6f)

    /** Renders [content] under the requested theme, direction and font scale. */
    private fun render(
        dark: Boolean,
        rtl: Boolean,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)
            }
        }
    }

    /** Records the staging screen's grid with one long-pressed cell. */
    private fun captureGrid(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val media = List(40) { PendingMediaSlot("slot-$it", Uri.parse("content://shot/photo/$it")) }
        val documents = List(5) { Uri.parse("content://shot/doc/report-$it.pdf") }
        render(dark, rtl, fontScale) {
            MediaPreviewContent(
                mediaSlots = media,
                documentUris = documents,
                chatTitle = "Team",
                onClose = {},
                onSend = { _, onResult -> onResult(true) },
                onRemoveMediaAt = {},
                onRemoveDocumentAt = {},
                onAddPhotos = {},
                onAddDocuments = {},
                onCollectionChange = {},
            )
        }
        composeRule.onAllNodesWithTag("conversation.media.collection.cell").onFirst().performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/attachment_collection_grid_$name.png")
    }

    /** Records the three panel states stacked as the composer accessory shows them. */
    private fun capturePanels(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val actions = AttachmentCollectionPanelActions({}, {}, {}, {}, {})
        render(dark, rtl, fontScale) {
            Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    AttachmentCollectionProgress(running(), actions, Modifier.padding(4.dp))
                    AttachmentCollectionProgress(blocked(), actions, Modifier.padding(4.dp))
                    AttachmentCollectionProgress(paused(), actions, Modifier.padding(4.dp))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/attachment_collection_panels_$name.png")
    }

    /** A 40-item operation whose first two chunks are sent and third is uploading. */
    private fun running(): AttachmentCollectionOperation {
        var operation = operation("running")
        operation = operation.accepted(0).accepted(1)
        return operation.withChunkState(
            operation.chunks[2].id,
            CollectionChunkState.UPLOADING,
            CollectionItemState.UPLOADING,
            0L,
        )
    }

    /** A 40-item operation held by its failed second chunk. */
    private fun blocked(): AttachmentCollectionOperation {
        val operation = operation("blocked").accepted(0)
        val second = operation.chunks[1].id
        return operation.withChunkState(second, CollectionChunkState.FAILED, CollectionItemState.FAILED, 0L)
    }

    /** A restored operation with one chunk sent and three items that can no longer be read. */
    private fun paused(): AttachmentCollectionOperation =
        operation("paused")
            .accepted(0)
            .copy(paused = true, unrecoverableItemIds = setOf("paused-12", "paused-13", "paused-30"))

    /** Marks chunk [index] accepted. */
    private fun AttachmentCollectionOperation.accepted(index: Int) =
        withChunkState(chunks[index].id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, 0L)

    /** A planned 36-photo, 4-document operation. */
    private fun operation(id: String): AttachmentCollectionOperation {
        val planned =
            List(40) { index ->
                val kind = if (index < 36) CollectionItemKind.MEDIA else CollectionItemKind.DOCUMENT
                PlannedCollectionItem("$id-$index", kind, 1_000L)
            }
        return AttachmentCollectionOperation(
            id = id,
            accountRef = "account",
            groupIdHex = "group",
            items = planned.map { CollectionItem(it.id, "content://shot/${it.id}", it.kind) },
            chunks = planAttachmentChunks(id, planned),
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )
    }
}
