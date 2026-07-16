package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
    fun amoledReactionChipUsesBlackFillAndDirectionalAccentBorders() {
        var mineBorder: BorderStroke? = null
        var notMineBorder: BorderStroke? = null
        var mineContainer = Color.Unspecified
        var notMineContainer = Color.Unspecified
        var mineContent = Color.Unspecified
        var notMineContent = Color.Unspecified
        var expectedSentAccent = Color.Unspecified
        var expectedReceivedAccent = Color.Unspecified
        var expectedContent = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val sentAccent = MaterialTheme.colorScheme.primary
                val receivedAccent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                val onSurface = MaterialTheme.colorScheme.onSurface
                val mineBorderValue = reactionSummaryChipBorder(mine = true)
                val notMineBorderValue = reactionSummaryChipBorder(mine = false)
                val mineContainerValue = reactionSummaryChipContainerColor(mine = true)
                val notMineContainerValue = reactionSummaryChipContainerColor(mine = false)
                val mineContentValue = reactionSummaryChipContentColor(mine = true)
                val notMineContentValue = reactionSummaryChipContentColor(mine = false)

                SideEffect {
                    mineBorder = mineBorderValue
                    notMineBorder = notMineBorderValue
                    mineContainer = mineContainerValue
                    notMineContainer = notMineContainerValue
                    mineContent = mineContentValue
                    notMineContent = notMineContentValue
                    expectedSentAccent = sentAccent
                    expectedReceivedAccent = receivedAccent
                    expectedContent = onSurface
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color.Black, mineContainer)
            assertEquals(Color.Black, notMineContainer)
            assertEquals(expectedContent, mineContent)
            assertEquals(expectedContent, notMineContent)
            assertEquals(2.dp, requireNotNull(mineBorder).width)
            assertEquals(2.dp, requireNotNull(notMineBorder).width)
            assertEquals(expectedSentAccent, borderColor(mineBorder))
            assertEquals(expectedReceivedAccent, borderColor(notMineBorder))
            assertNotEquals(borderColor(mineBorder), borderColor(notMineBorder))
        }
    }

    @Test
    fun standardDarkReactionChipKeepsExistingStyling() {
        var mineContainer = Color.Unspecified
        var notMineContainer = Color.Unspecified
        var mineContent = Color.Unspecified
        var notMineContent = Color.Unspecified
        var mineBorder: BorderStroke? = null
        var expectedMineContainer = Color.Unspecified
        var expectedNotMineContainer = Color.Unspecified
        var expectedMineContent = Color.Unspecified
        var expectedNotMineContent = Color.Unspecified
        var expectedBorderColor = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val colorScheme = MaterialTheme.colorScheme
                val mineContainerValue = reactionSummaryChipContainerColor(mine = true)
                val notMineContainerValue = reactionSummaryChipContainerColor(mine = false)
                val mineContentValue = reactionSummaryChipContentColor(mine = true)
                val notMineContentValue = reactionSummaryChipContentColor(mine = false)
                val mineBorderValue = reactionSummaryChipBorder(mine = true)

                SideEffect {
                    mineContainer = mineContainerValue
                    notMineContainer = notMineContainerValue
                    mineContent = mineContentValue
                    notMineContent = notMineContentValue
                    mineBorder = mineBorderValue
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
            assertEquals(1.5.dp, requireNotNull(mineBorder).width)
            assertEquals(expectedBorderColor, borderColor(mineBorder))
        }
    }

    private fun borderColor(border: BorderStroke?): Color = (requireNotNull(border).brush as SolidColor).value
}
