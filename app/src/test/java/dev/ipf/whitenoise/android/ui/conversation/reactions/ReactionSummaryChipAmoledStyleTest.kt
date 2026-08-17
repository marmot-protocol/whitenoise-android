package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactionSummaryChipAmoledStyleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun amoledReactionChipSeparatesMessageDirectionFromSelection() {
        var outgoingSelectedBorder: BorderStroke? = null
        var outgoingUnselectedBorder: BorderStroke? = null
        var incomingSelectedBorder: BorderStroke? = null
        var incomingUnselectedBorder: BorderStroke? = null
        var selectedContainer = Color.Unspecified
        var unselectedContainer = Color.Unspecified
        var selectedContent = Color.Unspecified
        var unselectedContent = Color.Unspecified
        var expectedSentAccent = Color.Unspecified
        var expectedReceivedAccent = Color.Unspecified
        var expectedContent = Color.Unspecified
        var expectedSelectedContainer = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val sentAccent = MaterialTheme.colorScheme.inversePrimary
                val receivedAccent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                val onSurface = MaterialTheme.colorScheme.onSurface
                val selectedContainerValue = reactionSummaryChipContainerColor(selected = true)
                val unselectedContainerValue = reactionSummaryChipContainerColor(selected = false)
                val selectedContentValue = reactionSummaryChipContentColor(selected = true)
                val unselectedContentValue = reactionSummaryChipContentColor(selected = false)
                val outgoingSelectedBorderValue = reactionSummaryChipBorder(outgoing = true, selected = true)
                val outgoingUnselectedBorderValue = reactionSummaryChipBorder(outgoing = true, selected = false)
                val incomingSelectedBorderValue = reactionSummaryChipBorder(outgoing = false, selected = true)
                val incomingUnselectedBorderValue = reactionSummaryChipBorder(outgoing = false, selected = false)

                SideEffect {
                    outgoingSelectedBorder = outgoingSelectedBorderValue
                    outgoingUnselectedBorder = outgoingUnselectedBorderValue
                    incomingSelectedBorder = incomingSelectedBorderValue
                    incomingUnselectedBorder = incomingUnselectedBorderValue
                    selectedContainer = selectedContainerValue
                    unselectedContainer = unselectedContainerValue
                    selectedContent = selectedContentValue
                    unselectedContent = unselectedContentValue
                    expectedSentAccent = sentAccent
                    expectedReceivedAccent = receivedAccent
                    expectedContent = onSurface
                    expectedSelectedContainer = onSurface.copy(alpha = 0.1f).compositeOver(Color.Black)
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(expectedSelectedContainer, selectedContainer)
            assertEquals(Color.Black, unselectedContainer)
            assertEquals(expectedContent, selectedContent)
            assertEquals(expectedContent, unselectedContent)
            assertEquals(2.dp, requireNotNull(outgoingSelectedBorder).width)
            assertEquals(1.dp, requireNotNull(outgoingUnselectedBorder).width)
            assertEquals(2.dp, requireNotNull(incomingSelectedBorder).width)
            assertEquals(1.dp, requireNotNull(incomingUnselectedBorder).width)
            assertEquals(expectedSentAccent, borderColor(outgoingSelectedBorder))
            assertEquals(expectedSentAccent, borderColor(outgoingUnselectedBorder))
            assertEquals(expectedReceivedAccent, borderColor(incomingSelectedBorder))
            assertEquals(expectedReceivedAccent, borderColor(incomingUnselectedBorder))
            assertNotEquals(borderColor(outgoingSelectedBorder), borderColor(incomingSelectedBorder))
        }
    }

    @Test
    fun standardDarkReactionChipDistinguishesSelectionWithoutChangingPalette() {
        var mineContainer = Color.Unspecified
        var notMineContainer = Color.Unspecified
        var mineContent = Color.Unspecified
        var notMineContent = Color.Unspecified
        var mineBorder: BorderStroke? = null
        var notMineBorder: BorderStroke? = null
        var expectedMineContainer = Color.Unspecified
        var expectedNotMineContainer = Color.Unspecified
        var expectedMineContent = Color.Unspecified
        var expectedNotMineContent = Color.Unspecified
        var expectedBorderColor = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val colorScheme = MaterialTheme.colorScheme
                val mineContainerValue = reactionSummaryChipContainerColor(selected = true)
                val notMineContainerValue = reactionSummaryChipContainerColor(selected = false)
                val mineContentValue = reactionSummaryChipContentColor(selected = true)
                val notMineContentValue = reactionSummaryChipContentColor(selected = false)
                val mineBorderValue = reactionSummaryChipBorder(outgoing = true, selected = true)
                val notMineBorderValue = reactionSummaryChipBorder(outgoing = true, selected = false)

                SideEffect {
                    mineContainer = mineContainerValue
                    notMineContainer = notMineContainerValue
                    mineContent = mineContentValue
                    notMineContent = notMineContentValue
                    mineBorder = mineBorderValue
                    notMineBorder = notMineBorderValue
                    expectedMineContainer = colorScheme.secondaryContainer
                    expectedNotMineContainer = colorScheme.surfaceContainerHigh
                    expectedMineContent = colorScheme.onSecondaryContainer
                    expectedNotMineContent = colorScheme.onSurface
                    expectedBorderColor = colorScheme.surface
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(expectedMineContainer, mineContainer)
            assertEquals(expectedNotMineContainer, notMineContainer)
            assertEquals(expectedMineContent, mineContent)
            assertEquals(expectedNotMineContent, notMineContent)
            assertEquals(2.dp, requireNotNull(mineBorder).width)
            assertEquals(1.dp, requireNotNull(notMineBorder).width)
            assertEquals(expectedBorderColor, borderColor(mineBorder))
            assertEquals(expectedBorderColor, borderColor(notMineBorder))
        }
    }

    @Test
    fun currentUserReactionIsExposedAsSelectedSemantics() {
        var clickCount = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Column {
                    ReactionSummaryChip(
                        tallies = listOf(ReactionTally(emoji = "👍", count = 1, mine = true)),
                        outgoing = false,
                        onClick = { clickCount += 1 },
                    )
                    ReactionSummaryChip(
                        tallies = listOf(ReactionTally(emoji = "❤️", count = 1, mine = false)),
                        outgoing = true,
                        onClick = {},
                    )
                }
            }
        }

        val chips = composeRule.onAllNodes(hasClickAction())
        chips[0].assertIsSelected()
        chips[1].assertIsNotSelected()
        chips[0].performClick()
        composeRule.runOnIdle { assertEquals(1, clickCount) }
    }

    @Test
    fun darkAccountAccentUsesContrastSafeAmoledOutline() {
        var outgoingBorder: BorderStroke? = null
        var configuredAccent = Color.Unspecified
        var expectedSafeAccent = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = true,
                amoled = true,
                accentColorArgb = 0xFF000000,
            ) {
                val border = reactionSummaryChipBorder(outgoing = true, selected = false)
                val primary = MaterialTheme.colorScheme.primary
                val inversePrimary = MaterialTheme.colorScheme.inversePrimary
                SideEffect {
                    outgoingBorder = border
                    configuredAccent = primary
                    expectedSafeAccent = inversePrimary
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(expectedSafeAccent, borderColor(outgoingBorder))
            assertNotEquals(configuredAccent, borderColor(outgoingBorder))
            assertNotEquals(Color.Black, borderColor(outgoingBorder))
        }
    }

    @Test
    fun totalDoesNotOverflowAndOnlyFourEmojiRemainVisible() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ReactionSummaryChip(
                    tallies =
                        listOf(
                            ReactionTally("👍", Int.MAX_VALUE, mine = false),
                            ReactionTally("❤️", Int.MAX_VALUE, mine = false),
                            ReactionTally("😂", 1, mine = false),
                            ReactionTally("🎉", 1, mine = false),
                            ReactionTally("😮", 1, mine = false),
                        ),
                    outgoing = false,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("👍❤️😂🎉", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("4294967297", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("😮", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun borderColor(border: BorderStroke?): Color = (requireNotNull(border).brush as SolidColor).value
}
