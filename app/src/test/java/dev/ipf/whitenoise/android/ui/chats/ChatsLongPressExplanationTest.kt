package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.group.QuickInfoAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding a chats-area control explains it without running it; a quick tap still runs it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsLongPressExplanationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var calls = 0

    @Test
    fun holdingQuickInfoActionExplainsItWithoutRunningIt() {
        renderQuickInfo()
        composeRule.onNodeWithContentDescription(context.getString(R.string.mute)).performTouchInput { longClick() }
        assertExplanation(R.string.mute, R.string.explanation_chat_info_mute)
        assertEquals(0, calls)
    }

    @Test
    fun tappingQuickInfoActionStillRunsIt() {
        renderQuickInfo()
        composeRule.onNodeWithContentDescription(context.getString(R.string.mute)).performClick()
        assertEquals(1, calls)
        composeRule.onNodeWithTag("explanation.sheet").assertDoesNotExist()
    }

    @Test
    fun quickInfoActionOffersTalkBackExplain() {
        renderQuickInfo()
        explainViaTalkBack(composeRule.onNodeWithContentDescription(context.getString(R.string.mute)))
        assertExplanation(R.string.mute, R.string.explanation_chat_info_mute)
        assertEquals(0, calls)
    }

    @Test
    fun holdingTheRelayWarningFabExplainsItWithoutOpeningRelays() {
        renderFab(missingRelays = true)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.chats_check_relays))
            .performTouchInput { longClick() }
        assertExplanation(R.string.chats_check_relays, R.string.explanation_chats_fab_check_relays)
        assertEquals(0, calls)
    }

    @Test
    fun tappingTheRelayWarningFabStillRunsIt() {
        renderFab(missingRelays = true)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chats_check_relays)).performClick()
        assertEquals(1, calls)
    }

    @Test
    fun holdingTheNormalNewMessageFabDoesNotExplain() {
        renderFab(missingRelays = false)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.new_message))
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag("explanation.sheet").assertDoesNotExist()
    }

    private fun assertExplanation(
        title: Int,
        body: Int,
    ) {
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.body").assertTextEquals(context.getString(body))
        composeRule
            .onNode(hasText(context.getString(title)) and hasAnyAncestor(hasTestTag("explanation.sheet")))
            .assertExists()
    }

    private fun explainViaTalkBack(node: SemanticsNodeInteraction) {
        val explain =
            node
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.explanation_action_explain) }
        composeRule.runOnIdle { assertTrue(explain.action()) }
    }

    private fun renderQuickInfo() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface {
                        QuickInfoAction(
                            label = context.getString(R.string.mute),
                            icon = R.drawable.ic_notifications_off,
                            onClick = { calls++ },
                            explanation = chatsExplanation(R.string.mute, R.string.explanation_chat_info_mute),
                        )
                    }
                }
            }
        }
    }

    private fun renderFab(missingRelays: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface { ChatsNewMessageFabContent(missingRelays = missingRelays, onClick = { calls++ }) }
                }
            }
        }
    }
}
