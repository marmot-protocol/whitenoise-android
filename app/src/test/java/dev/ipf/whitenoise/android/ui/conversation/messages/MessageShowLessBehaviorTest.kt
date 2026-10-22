package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual bubble → menu → reader → compact bubble sequence with automatic collapse disabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w400dp-h2400dp-mdpi")
class MessageShowLessBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun explicitCollapseReopensAndReturnsWithoutChangingDraft() {
        val fixture =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = false, media = false)
        val longBody = (1..90).joinToString("\n") { "Message line $it" }
        val item = fixture.item.copy(record = fixture.item.record.copy(plaintext = longBody), projected = null)
        var surface by mutableStateOf(fixture.copy(item = item))
        val draft = ComposerTextState(TextFieldValue("Unsent reply"))
        var menuOpen by mutableStateOf(true)
        composeRule.setContent {
            SwipeTestBubbleHost(
                surface = surface,
                collapseLongMessages = false,
                isActionMenuOpen = menuOpen,
                onActionMenuOpenChange = { menuOpen = it },
                composerTextState = draft,
            )
        }
        composeRule.onNodeWithText("Show less").performClick()
        composeRule.onNodeWithText("Read more", ignoreCase = true).assertExists().performClick()
        composeRule.onNodeWithContentDescription("Message actions").performClick()
        composeRule.onNodeWithText("Show less").performClick()
        composeRule.onNodeWithText("Read more", ignoreCase = true).assertExists()
        composeRule.runOnIdle { assertEquals("Unsent reply", draft.valueState.value.text) }
        composeRule.onNodeWithText("Read more", ignoreCase = true).performClick()
        composeRule.runOnIdle {
            surface = surface.copy(item = item.copy(record = item.record.copy(plaintext = "Short edit")))
        }
        composeRule.onNodeWithContentDescription("Message actions").performClick()
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }

    @Test
    fun shortExpandedBodyNeverOffersRedundantCollapse() {
        val surface =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        composeRule.setContent {
            SwipeTestBubbleHost(surface = surface, collapseLongMessages = false, isActionMenuOpen = true)
        }
        composeRule.onNodeWithText("Show less").assertDoesNotExist()
    }
}
