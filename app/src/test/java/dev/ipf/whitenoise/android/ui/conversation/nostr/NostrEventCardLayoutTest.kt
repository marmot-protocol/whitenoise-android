package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h1000dp-mdpi")
class NostrEventCardLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun replyBubbleIntrinsicWidthCanMeasureTheExpandableCard() {
        var expansions = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(310.dp)) {
                    MessageBubbleFrame(
                        presentation = messageBubblePresentation(deleted = false, mine = true),
                        highlighted = false,
                        mine = true,
                        mentionedSelf = false,
                        mentionedYouLabel = "",
                        contentModifier = Modifier.width(IntrinsicSize.Max),
                    ) {
                        NostrEventCardLayout(140.dp, onExpand = { expansions++ }) { Box(Modifier.height(220.dp)) }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).assertIsDisplayed().performClick()
        assertEquals(1, expansions)
    }

    @Test
    fun clippedBodyActionCannotReceiveFooterOrOutsideTouches() {
        var expanded = 0
        var played = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(310.dp).height(300.dp).testTag("host")) {
                    NostrEventCardLayout(140.dp, onExpand = { expanded++ }) {
                        Column {
                            Spacer(Modifier.height(110.dp))
                            TextButton(
                                onClick = { played++ },
                                modifier = Modifier.height(48.dp).testTag("hidden-action"),
                            ) {
                                Text("Hidden play")
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("hidden-action").assertIsNotDisplayed()
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).performTouchInput { click(center) }
        composeRule.onNodeWithTag("host").performTouchInput { click(Offset(30f, 150f)) }
        composeRule.runOnIdle {
            assertEquals(1, expanded)
            assertEquals(0, played)
        }
    }

    @Test
    fun shortCardsHaveNoReservedBlankSpaceOrExpansionRow() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.testTag("card")) {
                    NostrEventCardLayout(400.dp, onExpand = {}) { Box(Modifier.height(40.dp)) }
                }
            }
        }
        composeRule.onNodeWithTag("card").assertHeightIsEqualTo(40.dp)
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).assertIsNotDisplayed()
    }

    @Test
    fun overflowKeepsExpansionVisibleAndRemovesItWhenWindowGrows() {
        val ceiling = mutableStateOf(150.dp)
        var expanded = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.testTag("card")) {
                    NostrEventCardLayout(ceiling.value, onExpand = { expanded++ }) { Box(Modifier.height(200.dp)) }
                }
            }
        }
        composeRule.onNodeWithTag("card").assertHeightIsEqualTo(150.dp)
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).assertIsDisplayed().performClick()
        assertEquals(1, expanded)
        composeRule.runOnIdle { ceiling.value = 400.dp }
        composeRule.onNodeWithTag("card").assertHeightIsEqualTo(200.dp)
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).assertIsNotDisplayed()
    }

    @Test
    fun overflowChangesEvenWhenTheClippedBodyKeepsTheSameHeight() {
        val bodyHeight = mutableStateOf(220.dp)
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    Box(Modifier.testTag("card")) {
                        NostrEventCardLayout(150.dp, onExpand = {}) { Box(Modifier.height(bodyHeight.value)) }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("card").assertHeightIsEqualTo(150.dp)
        val footer =
            composeRule
                .onNodeWithTag(NOSTR_CARD_EXPAND_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        val sameVisibleHeight = (150f - footer).dp
        composeRule.runOnIdle { bodyHeight.value = sameVisibleHeight }
        composeRule.onNodeWithTag("card").assertHeightIsEqualTo(sameVisibleHeight)
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).assertIsNotDisplayed()
        composeRule.runOnIdle { bodyHeight.value = 220.dp }
        composeRule.onNodeWithTag("card").assertHeightIsEqualTo(150.dp)
        composeRule.onNodeWithTag(NOSTR_CARD_EXPAND_TAG).assertIsDisplayed()
    }
}
