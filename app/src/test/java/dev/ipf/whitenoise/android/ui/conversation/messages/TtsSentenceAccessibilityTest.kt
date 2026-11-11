package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.TtsSentenceActions
import dev.ipf.whitenoise.android.ui.TtsSentenceChoice
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.ttsSentenceAccessibilityActions
import dev.ipf.whitenoise.android.ui.ttsSentenceLeafAndRowActions
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

    /**
     * A merged message row keeps one action per label, so its single chooser must reach the second
     * paragraph's sentences too, not only those of the paragraph that happened to win the label (#3761).
     */
    @Test
    fun mergedRowChooserOffersEveryParagraphsSentences() {
        val selected = mutableListOf<TtsSentenceChoice>()
        val paragraphs =
            mapOf(
                "first" to listOf(TtsSentenceChoice("r", "s0", 0, "One."), TtsSentenceChoice("r", "s1", 1, "Two.")),
                "second" to listOf(TtsSentenceChoice("r", "s2", 2, "Three."), TtsSentenceChoice("r", "s3", 3, "Four.")),
            )
        val owner =
            TtsSentenceActions({ leafId, _ -> paragraphs.getValue(leafId) }, {
                selected += it
                true
            })
        composeRule.setContent {
            WhiteNoiseTheme {
                // The second paragraph composes first, so the row's host follows reading order, not composition.
                listOf("second", "first").forEach { leafId ->
                    val actions = ttsSentenceLeafAndRowActions(leafId, leafId, owner).row
                    Text(leafId, Modifier.semantics { customActions = actions })
                }
            }
        }
        composeRule.waitForIdle()
        val first = composeRule.onNodeWithText("first").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val second =
            composeRule
                .onNodeWithText("second")
                .fetchSemanticsNode()
                .config
                .getOrElseNullable(SemanticsActions.CustomActions) { null }
        assertEquals(listOf("Choose sentence to read"), first.map { it.label })
        assertEquals(emptyList<Any>(), second.orEmpty())
        composeRule.runOnIdle { first.single().action() }
        composeRule.onNodeWithText("4. Four.").performClick()
        assertEquals(listOf(paragraphs.getValue("second")[1]), selected)
    }

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

    /** A chooser over more than one page of sentences pages on instead of dropping the later ones. */
    @Test
    fun chooserPagesOnToSentencesBeyondTheFirstPage() {
        val selected = mutableListOf<TtsSentenceChoice>()
        val choices = (0 until 250).map { TtsSentenceChoice("revision", "sentence-$it", it, "Line $it.") }
        val owner =
            TtsSentenceActions({ _, _ -> choices }, {
                selected += it
                true
            })
        composeRule.setContent {
            WhiteNoiseTheme {
                val actions = ttsSentenceAccessibilityActions("leaf", "long", owner)
                Text("source", Modifier.semantics { customActions = actions })
            }
        }
        val actions = composeRule.onNodeWithText("source").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { actions.single().action() }
        composeRule.onNodeWithText("250. Line 249.").assertDoesNotExist()
        composeRule
            .onNodeWithTag("tts_sentence_chooser")
            .captureRoboImage("src/test/snapshots/tts_sentence_chooser_first_of_two_pages.png")
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("1. Line 0.").assertDoesNotExist()
        composeRule.onNodeWithText("Next").assertDoesNotExist()
        // Back returns to the first page, and each page opens at its top.
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("1. Line 0.").assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("201. Line 200.").assertIsDisplayed()
        composeRule.onNodeWithText("250. Line 249.").performScrollTo().performClick()
        assertEquals(listOf(choices.last()), selected)
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
