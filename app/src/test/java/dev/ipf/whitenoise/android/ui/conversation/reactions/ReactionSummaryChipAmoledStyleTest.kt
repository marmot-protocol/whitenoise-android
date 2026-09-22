package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The prototype's reaction pills: one per emoji, primary container/outline when the reaction is mine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactionSummaryChipAmoledStyleTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Selection is the only colour cue, on AMOLED as elsewhere. */
    @Test
    fun amoledPillsUseThePrimaryRolesForSelectionOnly() {
        assertPillRoles(amoled = true)
    }

    /** The standard dark palette keeps the same roles. */
    @Test
    fun standardDarkPillsUseThePrimaryRolesForSelectionOnly() {
        assertPillRoles(amoled = false)
    }

    /** Asserts pill roles. */
    private fun assertPillRoles(amoled: Boolean) {
        var selectedContainer = Color.Unspecified
        var unselectedContainer = Color.Unspecified
        var selectedBorder = Color.Unspecified
        var unselectedBorder = Color.Unspecified
        var expectedSelectedContainer = Color.Unspecified
        var expectedUnselectedContainer = Color.Unspecified
        var expectedSelectedBorder = Color.Unspecified
        var expectedUnselectedBorder = Color.Unspecified
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = amoled) {
                val scheme = MaterialTheme.colorScheme
                val selectedContainerValue = reactionPillContainerColor(selected = true)
                val unselectedContainerValue = reactionPillContainerColor(selected = false)
                val selectedBorderValue = reactionPillBorderColor(selected = true)
                val unselectedBorderValue = reactionPillBorderColor(selected = false)
                SideEffect {
                    selectedContainer = selectedContainerValue
                    unselectedContainer = unselectedContainerValue
                    selectedBorder = selectedBorderValue
                    unselectedBorder = unselectedBorderValue
                    expectedSelectedContainer = scheme.primaryContainer
                    expectedUnselectedContainer = scheme.surfaceContainerHigh
                    expectedSelectedBorder = scheme.primary
                    expectedUnselectedBorder = scheme.outlineVariant
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(expectedSelectedContainer, selectedContainer)
            assertEquals(expectedUnselectedContainer, unselectedContainer)
            assertEquals(expectedSelectedBorder, selectedBorder)
            assertEquals(expectedUnselectedBorder, unselectedBorder)
        }
    }

    /** My reaction reads as selected and a tap opens the unfiltered reactor list without toggling it. */
    @Test
    fun currentUserReactionIsSelectedAndTapOpensItsDetails() {
        val opened = mutableListOf<String?>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Column {
                    ReactionPillRow(
                        tallies =
                            listOf(
                                ReactionTally(emoji = "👍", count = 1, mine = true),
                                ReactionTally(emoji = "❤️", count = 1, mine = false),
                            ),
                        enabled = true,
                        onOpenDetails = { opened += it },
                    )
                }
            }
        }
        val pills = composeRule.onAllNodes(hasClickAction())
        pills[0].assertIsSelected()
        pills[1].assertIsNotSelected()
        pills[0].performClick()
        composeRule.runOnIdle { assertEquals(listOf<String?>(null), opened) }
    }

    /** A fifth emoji collapses into the "+N" pill, which opens the details instead of toggling. */
    @Test
    fun fifthEmojiCollapsesIntoTheOverflowPill() {
        val opened = mutableListOf<String?>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ReactionPillRow(
                    tallies =
                        listOf(
                            ReactionTally("👍", 2, mine = false),
                            ReactionTally("❤️", 1, mine = false),
                            ReactionTally("😂", 1, mine = false),
                            ReactionTally("🎉", 1, mine = false),
                            ReactionTally("😮", 1, mine = false),
                        ),
                    enabled = true,
                    onOpenDetails = { opened += it },
                )
            }
        }
        listOf("👍", "❤️", "😂", "🎉", "2", "+1").forEach {
            composeRule.onNodeWithText(it, useUnmergedTree = true).assertExists()
        }
        composeRule.onNodeWithText("😮", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onAllNodes(hasClickAction())[4].performClick()
        composeRule.runOnIdle { assertEquals(listOf<String?>(null), opened) }
    }
}
