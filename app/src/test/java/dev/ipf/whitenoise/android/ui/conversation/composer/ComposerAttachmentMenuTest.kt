package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The native non-focusable popup retains above-Add geometry and window fallback in both layout directions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerAttachmentMenuTest {
    @get:Rule val composeRule = createComposeRule()

    /** Native command surface matches prototype and retains additional sources. */
    @Test
    fun nativeCommandSurfaceMatchesPrototypeAndRetainsAdditionalSources() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerAttachmentMenu(
                    IntRect(16, 700, 56, 748),
                    true,
                    {},
                    onCamera = {},
                    onGallery = {},
                    onFiles = {},
                    onLocation = {},
                    onUser = {},
                    onContact = {},
                )
            }
        }
        composeRule
            .onNodeWithTag("conversation.attachment.menu")
            .captureRoboImage("src/test/snapshots/composer_attachment_menu.png")
    }

    /** Menu has ten pixel gap and uses below fallback when needed. */
    @Test
    fun menuHasTenPixelGapAndUsesBelowFallbackWhenNeeded() {
        val source = IntRect(16, 700, 56, 748)
        val provider = ComposerAttachmentMenuPositionProvider(source, 10, 8)
        assertEquals(
            IntOffset(16, 354),
            provider.calculatePosition(IntRect.Zero, IntSize(360, 780), LayoutDirection.Ltr, IntSize(220, 336)),
        )
        assertEquals(
            IntOffset(8, 354),
            provider.calculatePosition(IntRect.Zero, IntSize(360, 780), LayoutDirection.Rtl, IntSize(220, 336)),
        )
        val upper = ComposerAttachmentMenuPositionProvider(IntRect(16, 40, 56, 88), 10, 8)
        assertEquals(
            IntOffset(16, 98),
            upper.calculatePosition(IntRect.Zero, IntSize(360, 780), LayoutDirection.Ltr, IntSize(220, 336)),
        )
    }
}
