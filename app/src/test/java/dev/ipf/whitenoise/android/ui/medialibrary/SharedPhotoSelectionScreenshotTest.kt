package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures the real selection bar and tile overlays over stand-in artwork, and drives them as TalkBack would. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SharedPhotoSelectionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val first = tile("message-1", 0)
    private val second = tile("message-1", 1)
    private val third = tile("message-2", 0)
    private val clip = tile("message-3", 0).copy(isVideo = true)

    /** Photos toggle as checkboxes, the video stays unavailable, and the bar counts and shares the set. */
    @Test
    fun selectingPhotosDark() {
        val share = SharedPhotoShare()
        var shares = 0
        share.start = { shares += 1 }
        share.selection.toggle(first)
        share.selection.toggle(third)
        render(share)

        composeRule.onNodeWithText("2 photos selected").assertExists()
        tileNode(second).assert(toggleState(ToggleableState.Off)).performClick()
        tileNode(second).assert(toggleState(ToggleableState.On))
        composeRule.onNodeWithText("3 photos selected").assertExists()
        composeRule.onNodeWithContentDescription("Video, can’t be selected").assertIsNotEnabled()
        tileNode(first).assert(toggleState(ToggleableState.On))
        composeRule.onNodeWithTag("shared.selection.share").assertIsEnabled().performClick()
        assertEquals(1, shares)
        assertEquals(listOf(first, third, second), share.selection.photos)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_photo_selection_dark.png")

        composeRule.onNodeWithTag("shared.selection.close").performClick()
        assertFalse(share.selection.active)
        composeRule.onNodeWithTag("shared.selection.bar").assertDoesNotExist()
        composeRule.onNodeWithTag("shared.filter.All").assertExists()
    }

    /** Outside selection mode a photo offers Select photo to accessibility services. */
    @Test
    fun selectPhotoActionStartsSelection() {
        val share = SharedPhotoShare()
        render(share)

        composeRule.onNodeWithTag("shared.selection.bar").assertDoesNotExist()
        val action =
            composeRule
                .onNodeWithTag("photo.${first.messageIdHex}#${first.attachmentIndex}")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        assertEquals("Select photo", action.label)
        composeRule.runOnIdle { action.action() }

        assertTrue(share.selection.isSelected(first))
        composeRule.onNodeWithText("1 photo selected").assertExists()
        tileNode(first).performSemanticsAction(SemanticsActions.OnClick)
        assertFalse(share.selection.active)
    }

    /** While the set is staged the bar shows progress and the selection cannot change. */
    @Test
    fun sharingLightRtlLargeText() {
        val share = SharedPhotoShare()
        share.selection.toggle(first)
        share.begin(kotlinx.coroutines.Job())
        render(share, dark = false, rtl = true, fontScale = 2f)

        composeRule.onNodeWithTag("shared.selection.sharing").assertExists()
        composeRule.onNodeWithTag("shared.selection.share").assertDoesNotExist()
        tileNode(second).assertIsNotEnabled()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_photo_selection_sharing_rtl_200.png")
    }

    private fun tileNode(tile: SharedMediaTile) = composeRule.onNodeWithTag("shared.selection.tile.${tile.messageIdHex}#${tile.attachmentIndex}")

    private fun toggleState(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    /** The production scaffold, bar and overlays over flat stand-in artwork, without media IO. */
    private fun render(
        share: SharedPhotoShare,
        dark: Boolean = true,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    SharedContentScaffold(
                        category = SharedContentCategory.Media,
                        filter = SharedVisualFilter.All,
                        onFilter = {},
                        onBack = {},
                        loading = false,
                        selectionBar = share.selectionBar(loading = false),
                    ) {
                        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(first to ARTWORK[0], second to ARTWORK[1], third to ARTWORK[2], clip to ARTWORK[3])
                                .forEach { (tile, color) -> StandInTile(share, tile, color) }
                        }
                    }
                }
            }
        }
    }

    @Suppress("TestFunctionName")
    @androidx.compose.runtime.Composable
    private fun StandInTile(
        share: SharedPhotoShare,
        tile: SharedMediaTile,
        color: Color,
    ) {
        val selecting = share.selection.active
        val selected = share.selection.isSelected(tile)
        Box(Modifier.size(84.dp).aspectRatio(1f)) {
            Box(
                Modifier
                    .matchParentSize()
                    .selectedTileInset(selected)
                    .background(color)
                    .testTag("photo.${tile.messageIdHex}#${tile.attachmentIndex}")
                    .selectPhotoAction(enabled = !selecting && !tile.isVideo) { share.selection.toggle(tile) },
            ) {
                if (tile.isVideo) Text("▶", Modifier.padding(8.dp))
            }
            if (selecting) {
                SharedTileSelectionOverlay(
                    tile = tile,
                    selected = selected,
                    enabled = !share.sharing,
                    description = if (tile.isVideo) "Video, can’t be selected" else "Photo from Alice",
                    onToggle = { share.selection.toggle(tile) },
                )
            }
        }
    }

    private fun tile(
        messageIdHex: String,
        attachmentIndex: Int,
    ) = SharedMediaTile(
        messageIdHex,
        attachmentIndex,
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://example.org/$messageIdHex")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = "photo.jpg",
            mediaType = "image/jpeg",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        ),
        false,
        1_700_000_000uL,
        "alice",
        false,
    )

    private companion object {
        val ARTWORK = listOf(Color(0xFF3F6E8C), Color(0xFFC98A4B), Color(0xFF5E8C4A), Color(0xFF6B4F7A))
    }
}
