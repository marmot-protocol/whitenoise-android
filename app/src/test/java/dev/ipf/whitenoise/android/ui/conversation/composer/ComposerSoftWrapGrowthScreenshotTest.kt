package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A soft wrap grows the automatic composer by one line. While the row catches up, the first line
 * stays in place and the new line is revealed below it, instead of the caret line scrolling into a
 * one-line viewport and the overflow thumb flashing. Clearing the draft fades the hint back in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSoftWrapGrowthScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private var value by mutableStateOf(TextFieldValue(""))

    @Test
    fun softWrapRevealsTheNewLineBelowAFixedFirstLineDark() = checkSoftWrap("dark", rtl = false)

    @Test
    fun softWrapRevealsTheNewLineBelowAFixedFirstLineLargeRtl() = checkSoftWrap("light_large_rtl", rtl = true)

    @Test
    fun clearingTheDraftFadesTheHintBackIn() {
        render("light", rtl = false)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Short note")
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasSetTextAction()).performTextReplacement("")
            composeRule.mainClock.advanceTimeBy(FRAME_MILLIS * 4)
            composeRule
                .onNodeWithTag(TAG)
                .captureRoboImage("src/test/snapshots/composer_soft_wrap_hint_fade_mid_light.png")
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun checkSoftWrap(
        name: String,
        rtl: Boolean,
    ) {
        render(name, rtl)
        val words = SENTENCE.split(" ")
        var text = ""
        var captured = false
        composeRule.mainClock.autoAdvance = false
        try {
            for (word in words) {
                val restingHeight = viewport().boundsInRoot.height
                text = if (text.isEmpty()) word else "$text $word"
                composeRule.onNode(hasSetTextAction()).performTextReplacement(text)
                composeRule.mainClock.advanceTimeBy(FRAME_MILLIS * CAPTURED_FRAME)
                if (viewport().boundsInRoot.height > restingHeight) {
                    // Mid-growth: the first line must still sit at the top of the viewport.
                    assertEquals(0f, editorScroll())
                    composeRule
                        .onNodeWithTag(TAG)
                        .captureRoboImage("src/test/snapshots/composer_soft_wrap_growth_mid_$name.png")
                    repeat(GROWTH_FRAMES) {
                        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
                        assertEquals(0f, editorScroll())
                    }
                    captured = true
                    break
                }
                composeRule.mainClock.advanceTimeBy(FRAME_MILLIS * SETTLE_FRAMES)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertTrue("the sentence never soft-wrapped", captured)
    }

    private fun viewport() = composeRule.onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG).fetchSemanticsNode()

    private fun editorScroll(): Float =
        viewport()
            .config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?.value
            ?.invoke() ?: 0f

    private fun render(
        theme: String,
        rtl: Boolean,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = theme == "dark", amoled = theme == "dark") {
                    Surface(Modifier.width(360.dp).height(260.dp).testTag(TAG)) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onPickFromGallery = {},
                                onPickDocument = {},
                                dictationController = null,
                                dictationAccountRef = null,
                                dictationGroupIdHex = null,
                                initialDraft = value,
                                onDraftChange = { next, _ -> value = next },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAG = "composer_soft_wrap_growth"
        const val FRAME_MILLIS = 16L
        const val GROWTH_FRAMES = 12
        const val CAPTURED_FRAME = 5
        const val SETTLE_FRAMES = 20
        const val SENTENCE = "Hey, are we still meeting tomorrow at the cafe near the station after lunch"
    }
}
