package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.TtsSentenceActions
import dev.ipf.whitenoise.android.ui.TtsSentenceChoice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.ttsSentenceAccessibilityActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsSentenceAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun duplicateOriginalTextChoosesTheSecondRevisionScopedSentence() {
        val selected = mutableListOf<TtsSentenceChoice>()
        val choices = (0..1).map { TtsSentenceChoice("revision", "sentence-$it", it, "Pay $12.50.") }
        val owner =
            TtsSentenceActions({ _, _ -> choices }, {
                selected += it
                true
            })
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                WhiteNoiseTheme(darkTheme = true) {
                    val actions = ttsSentenceAccessibilityActions("leaf", "Pay $12.50. Pay $12.50.", owner)
                    Text("source", Modifier.semantics { customActions = actions })
                }
            }
        }
        val actions = composeRule.onNodeWithText("source").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle {
            assertEquals("Choose sentence to read", actions.single().label)
            actions.single().action()
        }
        composeRule
            .onNodeWithTag("tts_sentence_chooser")
            .captureRoboImage("src/test/snapshots/tts_sentence_chooser_dark_large_font.png")
        composeRule.onNodeWithText("2. Pay $12.50.").performClick()
        assertEquals(listOf(choices[1]), selected)
    }

    @Test
    fun oneSentenceHasADirectAction() {
        val choice = TtsSentenceChoice("revision", "sentence", 0, "Yes.")
        val selected = mutableListOf<TtsSentenceChoice>()
        val owner =
            TtsSentenceActions({ _, _ -> listOf(choice) }, {
                selected += it
                true
            })
        composeRule.setContent {
            WhiteNoiseTheme {
                val actions = ttsSentenceAccessibilityActions("leaf", "Yes.", owner)
                Text("source", Modifier.semantics { customActions = actions })
            }
        }
        val actions = composeRule.onNodeWithText("source").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle {
            assertEquals("Read from this sentence", actions.single().label)
            actions.single().action()
        }
        assertEquals(listOf(choice), selected)
    }
}
