package dev.ipf.whitenoise.android.ui.common

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scaffold's top-bar slot hosts the screen's banner slot below its bar (#3312). A material scaffold hands its
 * content the top window inset only when the bar slot measures to nothing, so a scaffold with no top bar must keep
 * that inset itself, for its content and for a banner, rather than lose it to an always-present slot column.
 *
 * Robolectric screenshots run with zero insets, which is why these cases dispatch a real status-bar inset through
 * [ViewCompat.dispatchApplyWindowInsets], the source `WindowInsets` reads from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class WhiteNoiseScaffoldInsetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View
    private val notice = mutableStateOf<TransientNotice?>(null)

    /** Without a top bar, the content starts under the status bar exactly as it does in a plain material scaffold. */
    @Test
    fun aBarLessScaffoldKeepsTheTopInsetForItsContent() {
        render(topBar = false)
        dispatchStatusBar()

        assertEquals(STATUS_PX.dp.value, contentBounds().top.value, TOLERANCE)
    }

    /** A startup-style scaffold that opts out of the slot still keeps the inset for its content. */
    @Test
    fun anOptedOutBarLessScaffoldKeepsTheTopInsetForItsContent() {
        render(topBar = false, slot = false)
        dispatchStatusBar()

        assertEquals(STATUS_PX.dp.value, contentBounds().top.value, TOLERANCE)
    }

    /** Without a top bar, a banner sits directly under the status bar and the content starts under the banner. */
    @Test
    fun aBarLessScaffoldShowsItsBannerBelowTheStatusBar() {
        render(topBar = false)
        dispatchStatusBar()
        showNotice()

        val banner = bannerBounds()
        assertEquals(STATUS_PX.dp.value, banner.top.value, TOLERANCE)
        assertEquals(banner.bottom.value, contentBounds().top.value, TOLERANCE)
    }

    /** With a top bar, the bar owns the inset: the content starts under the bar with no second inset added. */
    @Test
    fun aScaffoldWithATopBarAddsNoSecondInset() {
        render(topBar = true)
        dispatchStatusBar()

        val bar = composeRule.onNodeWithTag(BAR_TAG).getUnclippedBoundsInRoot()
        assertEquals(0f, bar.top.value, TOLERANCE)
        assertEquals(bar.bottom.value, contentBounds().top.value, TOLERANCE)
    }

    /** With a top bar, a banner follows the bar and the content follows the banner. */
    @Test
    fun aScaffoldWithATopBarShowsItsBannerBelowTheBar() {
        render(topBar = true)
        dispatchStatusBar()
        showNotice()

        val bar = composeRule.onNodeWithTag(BAR_TAG).getUnclippedBoundsInRoot()
        val banner = bannerBounds()
        assertEquals(bar.bottom.value, banner.top.value, TOLERANCE)
        assertEquals(banner.bottom.value, contentBounds().top.value, TOLERANCE)
    }

    /** Publishes an app-global confirmation through a host, as the app root does. */
    private fun showNotice() {
        composeRule.runOnUiThread { notice.value = TransientNotice(id = 1L, title = AppText.Plain(NOTICE_TITLE)) }
        composeRule.waitForIdle()
    }

    /** Bounds of the presented app-global banner. */
    private fun bannerBounds(): DpRect =
        composeRule
            .onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

    /** Bounds of the scaffold content after its padding. */
    private fun contentBounds(): DpRect = composeRule.onNodeWithTag(CONTENT_TAG).getUnclippedBoundsInRoot()

    /** Applies a status-bar inset the way the platform would. */
    private fun dispatchStatusBar() {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_PX, 0, 0))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** Renders the scaffold under a host, with a plain fixed-height bar that pads for nothing itself. */
    private fun render(
        topBar: Boolean,
        slot: Boolean = true,
    ) {
        val host = TopNoticeHost(SnackbarHostState(), notice = { notice.value })
        composeRule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                WhiteNoiseTheme {
                    WhiteNoiseScaffold(
                        topBar = { if (topBar) Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).testTag(BAR_TAG)) },
                        topNoticeSlot = slot,
                    ) { padding ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .consumeWindowInsets(padding)
                                .testTag(CONTENT_TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val BAR_TAG = "scaffold-bar"
        const val CONTENT_TAG = "scaffold-content"
        const val NOTICE_TITLE = "Profile saved"
        const val STATUS_PX = 24
        const val TOLERANCE = 1f
        val BAR_HEIGHT = 56.dp
    }
}
