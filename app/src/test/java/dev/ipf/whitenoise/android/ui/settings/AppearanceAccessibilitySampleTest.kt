package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real preview text inherits effective density; simulation and owner replacement remain sample-local. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AppearanceAccessibilitySampleTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The optional scale multiplies inherited OS × app density once, and Reset restores the inherited values. */
    @Test
    fun simulationChangesRealTextLayoutAndResetRestoresInheritedConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, 1.5f),
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                ) {
                    val state = remember { AppearancePreviewState() }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AppearanceAccessibilitySample(state, BubbleTheme.Light, null, null)
                    }
                }
            }
        }
        val rtl = context.getString(R.string.appearance_preview_rtl)
        val large = context.getString(R.string.appearance_preview_large_text)
        assertEquals(1.5f, sampleLayout().layoutInput.density.fontScale)
        assertEquals(LayoutDirection.Ltr, sampleLayout().layoutInput.layoutDirection)
        composeRule.onNodeWithText(rtl).performScrollTo().performClick()
        composeRule.onNodeWithText(large).performScrollTo().performClick()
        assertEquals(3f, sampleLayout().layoutInput.density.fontScale)
        assertEquals(LayoutDirection.Rtl, sampleLayout().layoutInput.layoutDirection)
        composeRule
            .onNodeWithText(context.getString(R.string.appearance_preview_reset))
            .performScrollTo()
            .performClick()
        assertEquals(1.5f, sampleLayout().layoutInput.density.fontScale)
        assertEquals(LayoutDirection.Ltr, sampleLayout().layoutInput.layoutDirection)
    }

    /** A new account/runtime owner cannot inherit a prior owner's temporary sample switches. */
    @Test
    fun ownerReplacementClearsBothSimulationSwitches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = mutableStateOf("first-runtime")
        composeRule.setContent {
            WhiteNoiseTheme {
                val state = remember(owner.value) { AppearancePreviewState() }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppearanceAccessibilitySample(state, BubbleTheme.Light, null, null)
                }
            }
        }
        val rtl = context.getString(R.string.appearance_preview_rtl)
        val large = context.getString(R.string.appearance_preview_large_text)
        composeRule
            .onNodeWithText(rtl)
            .performScrollTo()
            .performClick()
            .assertIsOn()
        composeRule
            .onNodeWithText(large)
            .performScrollTo()
            .performClick()
            .assertIsOn()
        composeRule.runOnIdle { owner.value = "second-runtime" }
        composeRule.onNodeWithText(rtl).performScrollTo().assertIsOff()
        composeRule.onNodeWithText(large).performScrollTo().assertIsOff()
    }

    /** Reads the reused production bubble text rather than a duplicate test-only theme renderer. */
    private fun sampleLayout(): TextLayoutResult {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNode(
                hasText(context.getString(R.string.font_size_sample_received)) and
                    hasAnyAncestor(hasTestTag("appearance.accessibility.sample")),
                useUnmergedTree = true,
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
