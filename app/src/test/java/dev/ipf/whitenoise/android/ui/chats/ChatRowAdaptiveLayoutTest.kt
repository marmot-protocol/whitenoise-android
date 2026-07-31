package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w240dp-h480dp-mdpi")
class ChatRowAdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val nowText by lazy { context.getString(R.string.relative_time_now) }
    private val timestampAt = (System.currentTimeMillis() / 1_000L).toULong()

    @Test
    fun normalDensityUsesCompactRowHeight() {
        render()

        val rowBounds =
            composeRule
                .onNodeWithTag(ROW_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertEquals(72f, rowBounds.height, 0.5f)
    }

    @Test
    fun longTitleEllipsizesBeforeTimestampWhilePreviewUsesSpaceBelowIt() {
        render()

        val titleBounds =
            composeRule
                .onNodeWithText(TITLE, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val timestampBounds =
            composeRule
                .onNodeWithText(nowText, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val previewBounds =
            composeRule
                .onNodeWithTag(PREVIEW_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(titleBounds.right <= timestampBounds.left)
        assertTrue(previewBounds.right > titleBounds.right)
    }

    @Test
    fun supportingMetadataReservesOnlyItsOwnRow() {
        render(showMetadata = true)

        val titleBounds =
            composeRule
                .onNodeWithText(TITLE, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val timestampBounds =
            composeRule
                .onNodeWithText(nowText, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val previewBounds =
            composeRule
                .onNodeWithTag(PREVIEW_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val metadataBounds =
            composeRule
                .onNodeWithTag(METADATA_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(titleBounds.right <= timestampBounds.left)
        assertTrue(previewBounds.right <= metadataBounds.left)
    }

    @Test
    fun rtlMapsTimestampAndSupportingMetadataToTheTrailingSide() {
        render(
            layoutDirection = LayoutDirection.Rtl,
            showMetadata = true,
        )

        val titleBounds =
            composeRule
                .onNodeWithText(TITLE, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val timestampBounds =
            composeRule
                .onNodeWithText(nowText, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val previewBounds =
            composeRule
                .onNodeWithTag(PREVIEW_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val metadataBounds =
            composeRule
                .onNodeWithTag(METADATA_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(timestampBounds.right <= titleBounds.left)
        assertTrue(metadataBounds.right <= previewBounds.left)
    }

    @Test
    fun selectionModeReplacesTimestampAndSupportingMetadata() {
        render(
            showMetadata = true,
            selectionMode = true,
        )

        composeRule.onNodeWithText(nowText).assertDoesNotExist()
        composeRule.onNodeWithTag(METADATA_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule
            .onNodeWithTag(CHAT_ROW_SELECTION_INDICATOR_TAG, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun largeFontScaleKeepsBothRowsNonOverlappingAtNarrowWidth() {
        render(
            showMetadata = true,
            fontScale = 1.6f,
        )

        val titleBounds =
            composeRule
                .onNodeWithText(TITLE, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val timestampBounds =
            composeRule
                .onNodeWithText(nowText, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val previewBounds =
            composeRule
                .onNodeWithTag(PREVIEW_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val metadataBounds =
            composeRule
                .onNodeWithTag(METADATA_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue(titleBounds.right <= timestampBounds.left)
        assertTrue(previewBounds.right <= metadataBounds.left)
    }

    private fun render(
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        showMetadata: Boolean = false,
        selectionMode: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides layoutDirection,
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                MaterialTheme {
                    ChatRowLayout(
                        title = TITLE,
                        timestampAt = timestampAt,
                        rowHasUnread = true,
                        selectionMode = selectionMode,
                        selected = true,
                        leadingContent = {
                            Box(Modifier.size(44.dp))
                        },
                        supportingContent = {
                            Text(
                                text = PREVIEW,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().testTag(PREVIEW_TAG),
                            )
                        },
                        supportingMetadata =
                            if (showMetadata) {
                                {
                                    Box(
                                        Modifier
                                            .size(width = 28.dp, height = 16.dp)
                                            .testTag(METADATA_TAG),
                                    )
                                }
                            } else {
                                null
                            },
                        modifier = Modifier.width(240.dp).testTag(ROW_TAG),
                    )
                }
            }
        }
    }

    private companion object {
        const val TITLE = "A very long conversation title that must never overlap"
        const val PREVIEW = "A very long supporting preview that should reach beneath the timestamp"
        const val PREVIEW_TAG = "chat-row-preview"
        const val METADATA_TAG = "chat-row-supporting-metadata"
        const val ROW_TAG = "chat-row"
    }
}
