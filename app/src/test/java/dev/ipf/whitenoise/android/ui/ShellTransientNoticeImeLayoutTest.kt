package dev.ipf.whitenoise.android.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-global confirmation is shown by the shell, but the keyboard belongs to whatever field the
 * destination still has focused, so the host — not each success emitter — owes the notice a place
 * above the effective bottom obstruction.
 *
 * Scope boundary: these are Robolectric tests. They drive real inset state through
 * [ViewCompat.dispatchApplyWindowInsets], which is what `WindowInsets.ime` reads from, but they
 * cannot reproduce the platform's IME animation ordering. That still needs a device pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShellTransientNoticeImeLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View

    /** An open keyboard must not be allowed to cover the confirmation it raced. */
    @Test
    fun noticeStaysAboveAnOpenKeyboard() {
        renderWithFormBar()
        showNotice()
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)

        assertTrue(noticeBounds().bottom <= rootBounds().bottom - IME_PX.dp + TOLERANCE)
    }

    /** With no keyboard the notice still clears the navigation bar, exactly once. */
    @Test
    fun noticeClearsTheNavigationBarWhenNoKeyboardIsOpen() {
        renderWithFormBar()
        showNotice()
        dispatchInsets(imeBottomPx = 0, navigationBottomPx = NAVIGATION_PX)

        val gap = rootBounds().bottom - noticeBounds().bottom
        assertTrue("expected one navigation-bar gap, got $gap", (gap - NAVIGATION_PX.dp).absoluteValue() <= TOLERANCE)
    }

    /**
     * The form's own action bar already follows the same insets. The notice sits below it and has
     * cleared them for both, so the bar must rest directly on the notice rather than lifting a
     * second keyboard height above it.
     */
    @Test
    fun aFormActionBarDoesNotLiftOverTheSameInsetTwice() {
        renderWithFormBar()
        showNotice()
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)

        val bar = composeRule.onNodeWithTag(FORM_BAR_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        val notice = noticeBounds()
        assertTrue("action bar overlaps the notice", bar.bottom <= notice.top + TOLERANCE)
        assertTrue("action bar floats above the notice", (notice.top - bar.bottom).absoluteValue() <= TOLERANCE)
    }

    /** A wide, short window is the landscape case, where the keyboard eats most of the height. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-mdpi")
    fun noticeStaysAboveTheKeyboardInLandscape() {
        renderWithFormBar()
        showNotice()
        dispatchInsets(imeBottomPx = LANDSCAPE_IME_PX, navigationBottomPx = NAVIGATION_PX)

        assertTrue(noticeBounds().bottom <= rootBounds().bottom - LANDSCAPE_IME_PX.dp + TOLERANCE)
    }

    /** Larger type makes the notice taller; it must grow upward, never down into the keyboard. */
    @Test
    fun largeFontNoticeStillClearsTheKeyboard() {
        renderWithFormBar(fontScale = 2f)
        showNotice()
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)

        assertTrue(noticeBounds().bottom <= rootBounds().bottom - IME_PX.dp + TOLERANCE)
    }

    /** Without a notice the host must leave the destination's own inset handling untouched. */
    @Test
    fun anAbsentNoticeLeavesTheFormBarFollowingTheKeyboardItself() {
        renderWithFormBar()
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)

        val bar = composeRule.onNodeWithTag(FORM_BAR_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(bar.bottom <= rootBounds().bottom - IME_PX.dp + TOLERANCE)
    }

    /** Publishes the app-global confirmation the host owns. */
    private fun showNotice() {
        composeRule.runOnUiThread {
            notice.value = TransientNotice(id = 1L, title = AppText.Plain(NOTICE_TITLE))
        }
        composeRule.waitForIdle()
    }

    /** Bounds of the shell-owned confirmation. */
    private fun noticeBounds() =
        composeRule
            .onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

    /** Bounds of the whole window under test. */
    private fun rootBounds() = composeRule.onRoot().getUnclippedBoundsInRoot()

    /** Applies a navigation-bar and keyboard inset the way the platform would. */
    private fun dispatchInsets(
        imeBottomPx: Int,
        navigationBottomPx: Int,
    ) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBottomPx))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationBottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** Renders the shell host over a destination whose bottom bar follows the same insets. */
    private fun renderWithFormBar(fontScale: Float = 1f) {
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme(fontScale = fontScale) {
                ShellTransientNoticeLayout(notice = notice.value) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .height(FORM_BAR_HEIGHT)
                                .testTag(FORM_BAR_TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val notice = mutableStateOf<TransientNotice?>(null)

    /** Absolute size of a signed Dp difference. */
    private fun Dp.absoluteValue(): Dp = if (value < 0f) -this else this

    private companion object {
        const val FORM_BAR_TAG = "form-action-bar"
        const val NOTICE_TITLE = "Profile saved"
        const val IME_PX = 300
        const val LANDSCAPE_IME_PX = 160
        const val NAVIGATION_PX = 48
        val FORM_BAR_HEIGHT = 64.dp
        val TOLERANCE = 1.dp
    }
}
