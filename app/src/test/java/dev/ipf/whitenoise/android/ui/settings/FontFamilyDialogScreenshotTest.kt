package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.fontFamilyOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The production picker retains a preview while its choices scroll in a short, large-type window. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h480dp-mdpi")
class FontFamilyDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lowerFontKeepsPreviewVisibleShortLargeLight() = checkLowerFont(rtl = false)

    @Test
    fun lowerFontKeepsPreviewVisibleShortLargeAmoledRtl() = checkLowerFont(rtl = true)

    private fun checkLowerFont(rtl: Boolean) {
        val selected = mutableStateOf(AppFont.System)
        val open = mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = rtl, amoled = rtl, fontScale = 2f, appFont = selected.value) {
                    if (open.value) {
                        FontFamilyDialog(
                            selected = selected.value,
                            label = { it.displayName },
                            onSelect = { selected.value = it },
                            onDismiss = { open.value = false },
                            theme = if (rtl) BubbleTheme.Amoled else BubbleTheme.Light,
                            mineArgb = null,
                            otherArgb = null,
                        )
                    }
                }
            }
        }
        val preview = composeRule.onNodeWithTag("font_family.preview.viewport")
        preview.assertIsDisplayed()
        val initialTop = preview.fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithTag("font_family.Figtree").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(AppFont.Figtree, selected.value) }
        preview.assertIsDisplayed()
        assertEquals(initialTop, preview.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertTrue(preview.fetchSemanticsNode().boundsInRoot.height >= 48f)
        composeRule.onNodeWithTag("font_family.Figtree").assertIsSelected()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(context.getString(R.string.font_size_sample_received), useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            AppFont.Figtree.fontFamilyOrNull(),
            layouts
                .single()
                .layoutInput.style.fontFamily,
        )
        composeRule.onNodeWithTag("font_family.done").assertIsDisplayed()
        composeRule.onNodeWithTag("font_family.system").assertIsDisplayed()
        composeRule.onNodeWithTag("font_family.dialog").captureRoboImage(
            "src/test/snapshots/font_family_short_large_${if (rtl) "amoled_rtl" else "light"}.png",
        )
        composeRule.onNodeWithTag("font_family.system").performClick()
        composeRule.runOnIdle { assertEquals(AppFont.System, selected.value) }
        preview.assertIsDisplayed()
        composeRule.onNodeWithTag("font_family.done").performClick()
        composeRule.onNodeWithTag("font_family.dialog").assertDoesNotExist()
    }
}
