package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.ExplanationHostState
import dev.ipf.whitenoise.android.ui.common.ExplanationSheetHost
import dev.ipf.whitenoise.android.ui.common.LocalExplanationHost
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.design.AboveWindowYPopupPositionProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/** A composer control's explanation opens above the composer without taking focus; others keep the modal sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerExplanationEdgeTest {
    @get:Rule val composeRule = createComposeRule()
    private val host = ExplanationHostState()

    /** Held inside the composer, the explanation's bottom is the composer's top edge, in a non-focusable popup. */
    @Test
    fun composerControlsExplainAboveTheComposersTopEdge() {
        render(aboveComposer = true)
        composeRule.onNodeWithTag(CONTROL).performTouchInput { longClick() }

        val composerTop =
            composeRule
                .onNodeWithTag(COMPOSER)
                .fetchSemanticsNode()
                .positionInWindow.y
        composeRule.runOnIdle { assertEquals(composerTop.roundToInt(), host.bottomWindowY) }
        composeRule.onNode(hasTestTag(SHEET) and hasAnyAncestor(isPopup())).assertIsDisplayed()
    }

    /** Held anywhere else, such as in Settings, the explanation keeps the modal sheet at the window's bottom. */
    @Test
    fun otherControlsKeepTheModalSheet() {
        render(aboveComposer = false)
        composeRule.onNodeWithTag(CONTROL).performTouchInput { longClick() }

        composeRule.runOnIdle { assertNull(host.bottomWindowY) }
        composeRule.onNodeWithTag(SHEET).assertIsDisplayed()
        composeRule.onNode(hasTestTag(SHEET) and hasAnyAncestor(isPopup())).assertDoesNotExist()
    }

    /** The editor's Back closes an explanation open above the composer, and leaves any other explanation alone. */
    @Test
    fun editorBackClosesOnlyTheComposersExplanation() {
        var close: (() -> Unit)? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalExplanationHost provides host) { close = composerExplanationClose() }
        }
        composeRule.runOnIdle { assertNull(close) }

        composeRule.runOnIdle { host.show(EXPLANATION) }
        composeRule.runOnIdle { assertNull(close) }

        composeRule.runOnIdle { host.show(EXPLANATION, bottomWindowY = 600) }
        composeRule.runOnIdle { checkNotNull(close).invoke() }
        composeRule.runOnIdle { assertNull(host.shown) }
    }

    /**
     * The sheet sits centered with its bottom at the edge, or at the visible frame's bottom when the edge is below
     * it.
     */
    @Test
    fun placementKeepsTheSheetBottomAtTheEdge() {
        val window = IntSize(360, 700)
        val sheet = IntSize(300, 200)

        assertEquals(IntOffset(30, 400), place(bottomY = 600, window, sheet, LayoutDirection.Ltr))
        assertEquals(IntOffset(30, 400), place(bottomY = 600, window, sheet, LayoutDirection.Rtl))
        assertEquals(IntOffset(30, 500), place(bottomY = 900, window, sheet, LayoutDirection.Ltr))
    }

    /** Where the composer placement puts a [sheet] whose bottom edge belongs at [bottomY]. */
    private fun place(
        bottomY: Int,
        window: IntSize,
        sheet: IntSize,
        direction: LayoutDirection,
    ): IntOffset = AboveWindowYPopupPositionProvider(bottomY).calculatePosition(IntRect.Zero, window, direction, sheet)

    /** A 120dp composer holding one explained control at the bottom of the window, inside or outside the edge scope. */
    private fun render(aboveComposer: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalExplanationHost provides host) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        val composer: @Composable () -> Unit = {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .testTag(COMPOSER)
                                    .composerExplanationEdge(),
                            ) {
                                Box(Modifier.size(48.dp).testTag(CONTROL).explainOnLongPress(EXPLANATION))
                            }
                        }
                        if (aboveComposer) ExplainAboveComposer(composer) else composer()
                    }
                    ExplanationSheetHost(host)
                }
            }
        }
    }

    private companion object {
        const val COMPOSER = "composer"
        const val CONTROL = "composer.control"
        const val SHEET = "explanation.sheet"
        val EXPLANATION = Explanation("Camera", "Takes a photo to send in this chat.")
    }
}
