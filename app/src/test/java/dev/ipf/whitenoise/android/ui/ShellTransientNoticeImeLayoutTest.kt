package dev.ipf.whitenoise.android.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.common.GLOBAL_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.common.TOP_NOTICE_MAX_HEIGHT_FRACTION
import dev.ipf.whitenoise.android.ui.common.TOP_NOTICE_MIN_MAX_HEIGHT
import dev.ipf.whitenoise.android.ui.common.TOP_NOTICE_REGION_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell shows an app-global confirmation in its top region (#3312): below the status bar and any persistent
 * top strip, above the destination. The keyboard and the bottom chrome belong to the destination, so a banner
 * appearing, clearing or being dismissed must leave a keyboard-following bottom bar exactly where it was, with no
 * second inset applied and no bottom spacer left behind.
 *
 * Scope boundary: these are Robolectric tests. They drive real inset state through
 * [ViewCompat.dispatchApplyWindowInsets], which is what `WindowInsets.ime` reads from, but they cannot reproduce
 * the platform's IME animation ordering. TopNoticeComposerBoundsAndroidTest covers that on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ShellTransientNoticeImeLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View
    private val notice = mutableStateOf<TransientNotice?>(null)
    private var noticeTitle = NOTICE_TITLE

    /** The banner sits directly under the status bar and the destination starts directly under the banner. */
    @Test
    fun noticeSitsBelowTheStatusBarAboveTheDestination() {
        renderWithFormBar()
        dispatchInsets(imeBottomPx = 0, navigationBottomPx = NAVIGATION_PX)
        showNotice()

        val notice = noticeBounds()
        assertEquals(STATUS_PX.dp.value, notice.top.value, TOLERANCE.value)
        assertEquals(notice.bottom.value, contentBounds().top.value, TOLERANCE.value)
    }

    /** A persistent strip such as playback keeps the status bar, and the banner follows it without a second gap. */
    @Test
    fun noticeFollowsThePersistentTopStrip() {
        renderWithFormBar(persistentStrip = true)
        dispatchInsets(imeBottomPx = 0, navigationBottomPx = NAVIGATION_PX)
        showNotice()

        val strip = composeRule.onNodeWithTag(STRIP_TAG).getUnclippedBoundsInRoot()
        val notice = noticeBounds()
        assertEquals(strip.bottom.value, notice.top.value, TOLERANCE.value)
        assertEquals(notice.bottom.value, contentBounds().top.value, TOLERANCE.value)
    }

    /** With the keyboard open, the form bar keeps its bounds while a banner appears and clears. */
    @Test
    fun aFormActionBarKeepsItsBoundsWhileANoticeAppearsAndClearsOverAnOpenKeyboard() {
        renderWithFormBar()
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)
        val before = barBounds()

        showNotice()
        assertEquals(before, barBounds())
        assertTrue(noticeBounds().bottom <= before.top)

        clearNotice()
        assertEquals(before, barBounds())
    }

    /** The bar follows the keyboard exactly once with a banner visible: no duplicate keyboard or navigation inset. */
    @Test
    fun theFormBarClearsTheKeyboardExactlyOnceWithANoticeVisible() {
        renderWithFormBar()
        showNotice()
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)

        val gap = rootBounds().bottom - barBounds().bottom
        assertEquals(IME_PX.dp.value, gap.value, TOLERANCE.value)

        dispatchInsets(imeBottomPx = 0, navigationBottomPx = NAVIGATION_PX)
        val restingGap = rootBounds().bottom - barBounds().bottom
        assertEquals(NAVIGATION_PX.dp.value, restingGap.value, TOLERANCE.value)
    }

    /** Clearing the banner returns the destination to its full height: no bottom spacer remains. */
    @Test
    fun clearingTheNoticeLeavesNoSpacerBehind() {
        renderWithFormBar()
        dispatchInsets(imeBottomPx = 0, navigationBottomPx = NAVIGATION_PX)
        val before = contentBounds()

        showNotice()
        clearNotice()

        assertEquals(before, contentBounds())
        composeRule.onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).assertDoesNotExist()
    }

    /** A wide, short window is the landscape case: the bar stays put and the banner stays above it. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-mdpi")
    fun landscapeKeyboardKeepsTheBarAndTheBannerApart() {
        renderWithFormBar()
        dispatchInsets(imeBottomPx = LANDSCAPE_IME_PX, navigationBottomPx = NAVIGATION_PX)
        val before = barBounds()

        showNotice()

        assertEquals(before, barBounds())
        assertTrue(noticeBounds().bottom <= before.top)
    }

    /** Large text grows the banner, but it stays bounded and the bar below keeps its bounds. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h480dp-mdpi")
    fun largeFontNoticeIsBoundedAndLeavesTheBarInPlace() {
        renderWithFormBar(fontScale = 2f, longNotice = true)
        dispatchInsets(imeBottomPx = IME_PX, navigationBottomPx = NAVIGATION_PX)
        val before = barBounds()

        showNotice()

        val region = composeRule.onNodeWithTag(TOP_NOTICE_REGION_TAG).getUnclippedBoundsInRoot()
        val visibleHeight = SHORT_WINDOW_HEIGHT - IME_PX.dp
        val bound = maxOf(visibleHeight * TOP_NOTICE_MAX_HEIGHT_FRACTION, TOP_NOTICE_MIN_MAX_HEIGHT)
        assertTrue("banner height ${region.height} exceeds $bound", region.height <= bound + TOLERANCE)
        assertEquals(before, barBounds())
    }

    /** Publishes the app-global confirmation the host owns. */
    private fun showNotice() {
        composeRule.runOnUiThread { notice.value = TransientNotice(id = 1L, title = AppText.Plain(noticeTitle)) }
        composeRule.waitForIdle()
    }

    /** Retires the confirmation, as its timeout or an explicit dismissal does. */
    private fun clearNotice() {
        composeRule.runOnUiThread { notice.value = null }
        composeRule.waitForIdle()
    }

    /** Bounds of the shell-owned confirmation. */
    private fun noticeBounds(): DpRect =
        composeRule
            .onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

    /** Bounds of the keyboard-following form bar. */
    private fun barBounds(): DpRect = composeRule.onNodeWithTag(FORM_BAR_TAG).getUnclippedBoundsInRoot()

    /** Bounds of the destination below the shell's top region. */
    private fun contentBounds(): DpRect = composeRule.onNodeWithTag(CONTENT_TAG).getUnclippedBoundsInRoot()

    /** Bounds of the whole window under test. */
    private fun rootBounds() = composeRule.onRoot().getUnclippedBoundsInRoot()

    /** Applies status-bar, navigation-bar and keyboard insets the way the platform would. */
    private fun dispatchInsets(
        imeBottomPx: Int,
        navigationBottomPx: Int,
    ) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
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
    private fun renderWithFormBar(
        fontScale: Float = 1f,
        persistentStrip: Boolean = false,
        longNotice: Boolean = false,
    ) {
        noticeTitle = if (longNotice) LONG_NOTICE_TITLE else NOTICE_TITLE
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme(fontScale = fontScale) {
                ShellTransientNoticeLayout(
                    notice = notice.value,
                    persistentTopContent = {
                        if (persistentStrip) {
                            Box(
                                Modifier
                                    .statusBarsPadding()
                                    .fillMaxWidth()
                                    .height(STRIP_HEIGHT)
                                    .testTag(STRIP_TAG),
                            )
                        }
                    },
                    persistentTopContentConsumesStatusBars = persistentStrip,
                ) {
                    Column(Modifier.fillMaxSize().testTag(CONTENT_TAG)) {
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
        }
        composeRule.waitForIdle()
    }

    /** Height of a bounds rectangle. */
    private val DpRect.height: Dp
        get() = bottom - top

    private companion object {
        const val FORM_BAR_TAG = "form-action-bar"
        const val CONTENT_TAG = "shell-destination"
        const val STRIP_TAG = "persistent-strip"
        const val NOTICE_TITLE = "Profile saved"
        val LONG_NOTICE_TITLE = "Invitation accepted. ".repeat(12)
        const val IME_PX = 300
        const val LANDSCAPE_IME_PX = 160
        const val NAVIGATION_PX = 48
        const val STATUS_PX = 24
        val SHORT_WINDOW_HEIGHT = 480.dp
        val FORM_BAR_HEIGHT = 64.dp
        val STRIP_HEIGHT = 40.dp
        val TOLERANCE = 1.dp
    }
}
