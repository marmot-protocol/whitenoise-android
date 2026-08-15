package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MainShellTtsReturnTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun requestGenerationKeepsOneStaticAccessibleStatus() {
        var requestId by mutableLongStateOf(1L)
        render(requestId = { requestId })

        assertStableStatus()
        composeRule.runOnIdle { requestId = 2L }
        assertStableStatus()
    }

    @Test
    fun transitionLight() {
        render(requestId = { 1L })

        capture("tts_return_transition_light.png")
    }

    @Test
    fun transitionDarkLargeRtl() {
        render(
            requestId = { 1L },
            darkTheme = true,
            fontScale = 1.8f,
            layoutDirection = LayoutDirection.Rtl,
        )

        capture("tts_return_transition_dark_large_rtl.png")
    }

    private fun assertStableStatus() {
        composeRule.onAllNodesWithTag(TTS_RETURN_TRANSITION_TAG).assertCountEquals(1)
        composeRule
            .onNodeWithTag(TTS_RETURN_TRANSITION_TAG)
            .assertTextEquals(label())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    private fun render(
        requestId: () -> Long,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    TtsReturnTransitionScreen(requestId = requestId())
                }
            }
        }
    }

    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(TTS_RETURN_TRANSITION_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    private fun label(): String = ApplicationProvider.getApplicationContext<Context>().getString(R.string.tts_returning_to_passage)
}
