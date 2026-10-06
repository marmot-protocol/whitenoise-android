package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.state.SwipePreferenceState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real settings controls and deterministic visuals across themes and large RTL text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GestureSettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = screen("light")

    @Test fun dark() = screen("dark", dark = true)

    @Test fun amoled() = screen("amoled", dark = true, amoled = true)

    @Test fun largeRtl() = screen("large_rtl", scale = 2f, rtl = true)

    @Test fun configured() =
        screen(
            "configured",
            state =
                SwipePreferenceState(
                    SwipeAction.Reply,
                    SwipeAction.Forward,
                    SwipeAction.ReadUnread,
                    SwipeAction.MuteUnmute,
                ),
        )

    @Test fun allFourChoicesAreReachableAndResetIsExplicit() {
        val changes = mutableListOf<Pair<SwipeBinding, SwipeAction>>()
        var resets = 0
        rule.setContent {
            WhiteNoiseTheme {
                GestureSettingsContent(SwipePreferenceState(), { b, a -> changes.add(b to a) }, { resets++ }, {})
            }
        }
        val picks = listOf("Reply", "Forward", "Read / unread", "Pin / unpin")
        SwipeBinding.entries.forEachIndexed { index, binding ->
            rule.onNodeWithTag("gestures." + binding.name).performScrollTo().performClick()
            rule.onNodeWithText(picks[index], useUnmergedTree = true).performClick()
        }
        rule.onNodeWithTag("gestures.reset").performScrollTo().performClick()
        assertEquals(SwipeBinding.entries, changes.map { it.first })
        assertEquals(1, resets)
    }

    private fun screen(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
        state: SwipePreferenceState = SwipePreferenceState(),
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    GestureSettingsContent(state, { _, _ -> }, {}, {})
                }
            }
        }
        rule.onRoot().captureRoboImage("src/test/snapshots/gesture_settings_$name.png")
        if (scale > 1f) {
            SwipeBinding.entries.forEach { rule.onNodeWithTag("gestures." + it.name).performScrollTo() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("gestures.reset"))
        }
    }
}
