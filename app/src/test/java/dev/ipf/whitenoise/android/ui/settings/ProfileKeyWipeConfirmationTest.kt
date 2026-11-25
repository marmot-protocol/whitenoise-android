package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real-window pixel baselines for the final typed wipe gate.
 * ProfileKeyWipeConfirmationAndroidTest owns platform text input, IME and physical action coverage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileKeyWipeConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The final destructive gate keeps its disabled action and Cancel readable. */
    @Test
    fun wipeConfirmationLight() = captureWipeConfirmation(largeRtl = false)

    /** Icon-bearing confirmation actions remain reachable at 200% RTL text. */
    @Test
    fun wipeConfirmationLargeRtl() = captureWipeConfirmation(largeRtl = true)

    /**
     * On a 320 dp window at 200% RTL the shared dialog width leaves too little room for both actions on one row, so
     * they stack. Scrolling to the keyword field shows all of it and takes the title out of view: the title check is
     * what pins the title to the scrolling body, since without a keyboard the field would fit beside a fixed title.
     * Robolectric has no keyboard, so ProfileKeyWipeConfirmationAndroidTest checks the same field above the real IME.
     */
    @Test
    @Config(qualifiers = "en-w320dp-h640dp-mdpi")
    fun wipeConfirmationNarrowLargeRtlScrolledToField() {
        showWipeConfirmation(largeRtl = true)
        val confirm = composeRule.onNodeWithTag("profile_keys.wipe_confirm")
        val cancel = composeRule.onNodeWithText(context.getString(R.string.cancel))
        val confirmBounds = confirm.fetchSemanticsNode().boundsInRoot
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Actions should stack on a 320 dp window at 200% text: $confirmBounds, $cancelBounds",
            confirmBounds.bottom <= cancelBounds.top || cancelBounds.bottom <= confirmBounds.top,
        )
        val field = composeRule.onNodeWithText(context.getString(R.string.sign_out_and_wipe_confirm_field_label))
        // Scroll actions run on clock frames, so the clock advances only for the scroll itself.
        composeRule.mainClock.autoAdvance = true
        field.performScrollTo()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(1_000)
        assertWhole(field)
        confirm.assertIsDisplayed()
        cancel.assertIsDisplayed()
        val title = composeRule.onNodeWithText(context.getString(R.string.sign_out_and_wipe_confirm_title))
        assertTrue("The title should scroll with the body, not stay fixed", !isWhole(title))
        composeRule
            .onNode(isDialog())
            .captureRoboImage("src/test/snapshots/profile_keys_wipe_confirm_narrow_large_rtl_field.png")
    }

    /** Captures the dialog at rest, light at normal text or AMOLED at 200% RTL text. */
    private fun captureWipeConfirmation(largeRtl: Boolean) {
        showWipeConfirmation(largeRtl)
        composeRule.onNodeWithTag("profile_keys.wipe_confirm").assertIsDisplayed()
        val scale = if (largeRtl) "large_rtl" else "light"
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/profile_keys_wipe_confirm_$scale.png")
    }

    /** Opens the empty confirmation, light at normal text or AMOLED at 200% RTL text, and lets it settle. */
    private fun showWipeConfirmation(largeRtl: Boolean) {
        // Native dialog traversal must not chase continuously scheduled Compose frames.
        // Advance deterministically, retaining the real platform dialog and its window insets.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    ProfileKeyWipeConfirmationDialog(input = "", onInputChange = {}, onConfirm = {}, onDismiss = {})
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    /** True when the node's visible bounds cover its full laid-out size, give or take a pixel. */
    private fun isWhole(node: SemanticsNodeInteraction): Boolean {
        val visible = node.fetchSemanticsNode().boundsInRoot
        val full = node.getUnclippedBoundsInRoot()
        val density = composeRule.density.density
        return visible.width + 1 >= (full.right - full.left).value * density &&
            visible.height + 1 >= (full.bottom - full.top).value * density
    }

    /** Fails unless the node is displayed and not clipped by the scrolling body. */
    private fun assertWhole(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        assertTrue("Node is clipped: ${node.fetchSemanticsNode().boundsInRoot}", isWhole(node))
    }
}
