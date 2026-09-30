package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    onPoll = {},
                )
            }
        }
        composeRule
            .onNodeWithTag("conversation.attachment.menu")
            .captureRoboImage("src/test/snapshots/composer_attachment_menu.png")
    }

    /** An open keyboard raises Add; every group action must remain above it and accept a finger scroll. */
    @Test
    fun raisedAttachmentButtonKeepsPollReachableByTouchScrolling() {
        var polls = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ComposerAttachmentMenu(
                    IntRect(16, 220, 56, 268),
                    true,
                    { dismissals++ },
                    onCamera = {},
                    onGallery = {},
                    onFiles = {},
                    onLocation = {},
                    onUser = {},
                    onContact = {},
                    onPoll = { polls++ },
                )
            }
        }
        val menu = composeRule.onNodeWithTag("conversation.attachment.menu")
        val bounds = menu.getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("menu must fit above Add, got $height", height.value <= 202f)
        menu.captureRoboImage("src/test/snapshots/composer_attachment_menu_short_amoled.png")
        val scroll = composeRule.onNode(hasScrollAction())
        val axis = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the menu needs overflow", axis.maxValue() > 0f)
        repeat(4) {
            scroll.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        assertEquals(axis.maxValue(), axis.value(), 0.5f)
        menu.captureRoboImage("src/test/snapshots/composer_attachment_menu_poll_scrolled_amoled.png")
        composeRule.onNodeWithText("Create poll").assertIsDisplayed()
        composeRule.onNodeWithText("Create poll").performTouchInput { click() }
        assertEquals(1, polls)
        assertEquals(1, dismissals)
    }

    /** Long labels must remain reachable in the same small viewport at 200% text and RTL. */
    @Test
    fun raisedAttachmentButtonKeepsPollReachableWithLargeRtlText() {
        var polls = 0
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true, fontScale = 2f) {
                    ComposerAttachmentMenu(
                        IntRect(304, 220, 344, 268),
                        true,
                        {},
                        onCamera = {},
                        onGallery = {},
                        onFiles = {},
                        onLocation = {},
                        onUser = {},
                        onContact = {},
                        onPoll = { polls++ },
                    )
                }
            }
        }
        val scroll = composeRule.onNode(hasScrollAction())
        repeat(6) {
            scroll.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Create poll").assertIsDisplayed()
        val labelBounds = composeRule.onNodeWithText("Create poll").getUnclippedBoundsInRoot()
        assertTrue("the popup must actually render large text", (labelBounds.bottom - labelBounds.top).value >= 32f)
        composeRule
            .onNodeWithTag("conversation.attachment.menu")
            .captureRoboImage("src/test/snapshots/composer_attachment_menu_scrolled_large_rtl.png")
        composeRule.onNodeWithText("Create poll").performTouchInput { click() }
        assertEquals(1, polls)
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
        assertEquals(682, provider.maxHeight(780, 48))
        assertEquals(674, upper.maxHeight(780, 48))
    }

    /** Resizing cannot leave a menu below the visible window, even with a stale captured anchor. */
    @Test
    fun menuClampsToTheResizedWindow() {
        val provider = ComposerAttachmentMenuPositionProvider(IntRect(16, 700, 56, 748), 10, 8)
        val height = provider.maxHeight(400, 48)
        assertEquals(382, height)
        assertEquals(
            IntOffset(16, 10),
            provider.calculatePosition(IntRect.Zero, IntSize(360, 400), LayoutDirection.Ltr, IntSize(220, height)),
        )
    }
}
