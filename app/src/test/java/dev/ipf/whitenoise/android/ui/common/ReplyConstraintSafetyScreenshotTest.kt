package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Oversized reply content remains a readable bounded bubble across supported themes and accessibility. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class ReplyConstraintSafetyScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Light surfaces retain normal wrap-content geometry alongside oversized content. */
    @Test
    fun light() = capture("reply_constraint_safety_light")

    /** Dark surfaces retain readable quote insets and bounded long bodies. */
    @Test
    fun dark() = capture("reply_constraint_safety_dark", dark = true)

    /** AMOLED borders remain correctly inset around the same bounded content. */
    @Test
    fun amoled() = capture("reply_constraint_safety_amoled", dark = true, amoled = true)

    /** Narrow RTL bubbles at 200 percent text size remain visible and aligned. */
    @Test
    fun largeRtl() = capture("reply_constraint_safety_large_rtl", largeRtl = true)

    /** Renders real frame/quote components with synthetic content and no account data. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val oversized = "Wide text ".repeat(7_000)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale = if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface {
                        Column(
                            Modifier.padding(12.dp).testTag("gallery"),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BoundedReplyFixture(oversized, "A short quote", largeRtl, dark, amoled)
                            BoundedReplyFixture("A short reply", oversized, largeRtl, dark, amoled)
                            BoundedReplyFixture("OK", "Hi", largeRtl, dark, amoled)
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("gallery").captureRoboImage("src/test/snapshots/$name.png")
    }
}
