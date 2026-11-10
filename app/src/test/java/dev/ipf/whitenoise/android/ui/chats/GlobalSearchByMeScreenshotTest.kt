package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "By me" quick filter (#3349) at the head of the search mode chips, inactive and active, across themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class GlobalSearchByMeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Inactive, light. */
    @Test fun inactiveLight() = capture("inactive_light", active = false)

    /** Active, light. */
    @Test fun activeLight() = capture("active_light", active = true)

    /** Active, dark. */
    @Test fun activeDark() = capture("active_dark", active = true, dark = true)

    /** Active, AMOLED. */
    @Test fun activeAmoled() = capture("active_amoled", active = true, dark = true, amoled = true)

    /** Active, right-to-left at double font scale on a narrow screen. */
    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h780dp-mdpi")
    fun activeLargeRtl() = capture("active_large_rtl", active = true, largeRtl = true)

    /** Tapping toggles the stable self sender filter; TalkBack reads the visible "By me" with its selected state. */
    @Test
    fun tapTogglesTheSelfSenderFilterWithAnAccessibleSelectedState() {
        var state by mutableStateOf(GlobalSearchState(isOpen = true))
        composeRule.setContent {
            WhiteNoiseTheme {
                GlobalSearchAttachmentModes(
                    state = state,
                    onSelectionChange = {},
                    byMe = GlobalSearchByMe(SELF, "You") { state = toggleGlobalSearchByMe(state, SELF, "You") },
                )
            }
        }
        val chip = composeRule.onNodeWithTag(GLOBAL_SEARCH_BY_ME_TAG)
        chip.assertHeightIsAtLeast(32.dp)
        assertEquals(false, chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(null, chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithText("By me", useUnmergedTree = true).assertExists()

        chip.performClick()
        composeRule.runOnIdle { assertEquals(true, state.byMeActive(SELF)) }
        assertEquals(true, chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    /** Renders the mode chips with By me and records the golden. */
    private fun capture(
        name: String,
        active: Boolean,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val base = GlobalSearchState(isOpen = true)
        val state = if (active) toggleGlobalSearchByMe(base, SELF, "You") else base
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.width(if (largeRtl) 320.dp else 360.dp).testTag("modes")) {
                        GlobalSearchAttachmentModes(
                            state = state,
                            onSelectionChange = {},
                            byMe = GlobalSearchByMe(SELF, "You") {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("modes").captureRoboImage("src/test/snapshots/global_search_by_me_$name.png")
    }

    private companion object {
        val SELF = "ab".repeat(32)
    }
}
