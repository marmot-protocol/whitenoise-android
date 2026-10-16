package dev.ipf.whitenoise.android.ui.common

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
 * Banner regions sit outside padded content, in bar slots, the shell, the root and modal columns, so in landscape
 * a side cutout or a side navigation bar would otherwise cover the start of the banner text (#3312). The region
 * pads for those insets itself, and honours what an ancestor already consumed so padded content is not padded twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w780dp-h360dp-mdpi")
class TopNoticeRegionInsetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View

    /** Under a side cutout and a side navigation bar, the banner starts after the cutout and ends before the bar. */
    @Test
    fun theRegionKeepsClearOfSideInsets() {
        render { TopNoticeSlot() }
        dispatchSideInsets()

        val region = regionBounds()
        assertEquals(CUTOUT_PX.dp.value, region.left.value, TOLERANCE)
        assertEquals((rootBounds().right - NAVIGATION_PX.dp).value, region.right.value, TOLERANCE)
    }

    /** When an ancestor has already consumed the side insets, the region adds no padding of its own. */
    @Test
    fun theRegionDoesNotPadAgainUnderAConsumingAncestor() {
        render {
            Box(Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                TopNoticeSlot()
            }
        }
        dispatchSideInsets()

        val region = regionBounds()
        assertEquals(0f, region.left.value, TOLERANCE)
        assertEquals(rootBounds().right.value, region.right.value, TOLERANCE)
    }

    /** Bounds of the presented banner region. */
    private fun regionBounds(): DpRect =
        composeRule
            .onNodeWithTag(TOP_NOTICE_REGION_TAG)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

    /** Bounds of the whole window under test. */
    private fun rootBounds(): DpRect = composeRule.onRoot().getUnclippedBoundsInRoot()

    /** Applies a left display cutout and a right navigation bar, the landscape side insets. */
    private fun dispatchSideInsets() {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, NAVIGATION_PX, 0))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** Renders a header and the given slot under a host that already has a global notice to present. */
    private fun render(slot: @Composable () -> Unit) {
        val notice = mutableStateOf<TransientNotice?>(TransientNotice(id = 1L, title = AppText.Plain(NOTICE_TITLE)))
        val host = TopNoticeHost(SnackbarHostState(), notice = { notice.value })
        composeRule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalTopNoticeHost provides host) {
                WhiteNoiseTheme {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(HEADER_HEIGHT))
                        slot()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val NOTICE_TITLE = "Invitation accepted"
        const val CUTOUT_PX = 40
        const val NAVIGATION_PX = 48
        const val TOLERANCE = 1f
        val HEADER_HEIGHT = 56.dp
    }
}
