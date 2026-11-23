package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Window shape classification and how the activity's shape reaches composables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Each band starts exactly at its Material breakpoint. */
    @Test
    fun widthAndHeightBandsStartAtMaterialBreakpoints() {
        fun width(dp: Int) = WindowLayout.fromSize(DpSize(dp.dp, 700.dp)).widthClass

        fun height(dp: Int) = WindowLayout.fromSize(DpSize(400.dp, dp.dp)).heightClass

        assertEquals(WindowWidthClass.Compact, width(599))
        assertEquals(WindowWidthClass.Medium, width(600))
        assertEquals(WindowWidthClass.Medium, width(839))
        assertEquals(WindowWidthClass.Expanded, width(840))
        assertEquals(WindowHeightClass.Compact, height(479))
        assertEquals(WindowHeightClass.Medium, height(480))
        assertEquals(WindowHeightClass.Medium, height(899))
        assertEquals(WindowHeightClass.Expanded, height(900))
    }

    /** A square window is not landscape; only a wider-than-tall one is. */
    @Test
    fun landscapeMeansWiderThanTall() {
        assertFalse(WindowLayout.fromSize(DpSize(700.dp, 700.dp)).isLandscape)
        assertFalse(WindowLayout.fromSize(DpSize(360.dp, 780.dp)).isLandscape)
        assertTrue(WindowLayout.fromSize(DpSize(780.dp, 360.dp)).isLandscape)
    }

    /** Rotating a phone still changes the layout, so rotation keeps resetting the composer's keyed state. */
    @Test
    fun rotatingAPhoneChangesTheLayout() {
        val portrait = WindowLayout.fromSize(DpSize(360.dp, 780.dp))
        val landscape = WindowLayout.fromSize(DpSize(780.dp, 360.dp))

        assertNotEquals(portrait, landscape)
        assertEquals(
            WindowLayout(WindowWidthClass.Medium, WindowHeightClass.Compact, isLandscape = true, fitsTwoPanes = true),
            landscape,
        )
    }

    /** Two panes fit from a 300dp list, a 1dp divider and a 360dp conversation; phones in portrait never do. */
    @Test
    fun twoPanesFitFromBothMinimumWidths() {
        fun fits(dp: Int) = WindowLayout.fromSize(DpSize(dp.dp, 700.dp)).fitsTwoPanes

        assertEquals(661.dp, TwoPaneMinWindowWidth)
        assertFalse(fits(412))
        assertFalse(fits(660))
        assertTrue(fits(661))
        assertTrue(fits(1280))
    }

    /** The value used outside a provider is what a portrait phone classifies as. */
    @Test
    fun defaultMatchesAPortraitPhone() {
        assertEquals(WindowLayout.CompactPortrait, WindowLayout.fromSize(DpSize(360.dp, 780.dp)))
    }

    /**
     * Unfolding keeps the orientation but changes the shape, and a dialog sees
     * the activity window's shape rather than its own.
     */
    @Test
    fun providerFollowsWindowResizeAndReachesDialogs() {
        val size = mutableStateOf(DpSize(360.dp, 780.dp))
        val windowInfo =
            object : WindowInfo {
                override val isWindowFocused: Boolean get() = true
                override val containerDpSize: DpSize get() = size.value
            }
        var rootLayout: WindowLayout? = null
        var dialogLayout: WindowLayout? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
                ProvideWindowLayout {
                    rootLayout = LocalWindowLayout.current
                    Dialog(onDismissRequest = {}) { dialogLayout = LocalWindowLayout.current }
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(WindowLayout.CompactPortrait, rootLayout)
            assertEquals(rootLayout, dialogLayout)
        }

        composeRule.runOnIdle { size.value = DpSize(673.dp, 841.dp) }
        val unfolded =
            WindowLayout(WindowWidthClass.Medium, WindowHeightClass.Medium, isLandscape = false, fitsTwoPanes = true)
        composeRule.runOnIdle {
            assertEquals(unfolded, rootLayout)
            assertEquals(unfolded, dialogLayout)
        }
    }
}
