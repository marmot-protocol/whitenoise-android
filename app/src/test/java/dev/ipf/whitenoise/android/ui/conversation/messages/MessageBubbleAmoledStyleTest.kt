package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleAmoledStyleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun invalidationWarningRendersWithoutMessageBodyText() {
        val warning = "May not be visible to everyone"

        composeRule.setContent {
            WhiteNoiseTheme {
                MessageBubbleInvalidationWarning(
                    warning = warning,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        composeRule.onNodeWithText(warning).assertIsDisplayed()
    }

    @Test
    fun captionlessInvalidatedMediaUsesSupplementalBubbleFrame() {
        assertTrue(shouldFrameMessageBubbleSupplement(bodyText = null, invalidationWarning = "warning"))
        assertFalse(shouldFrameMessageBubbleSupplement(bodyText = null, invalidationWarning = null))
    }

    @Test
    fun uncaptionedHeaderOnlyMediaUsesCustomBubbleContentColor() {
        val customBubbleContentColor = Color.White
        val outsideFooterColor = Color.Gray
        val supplementInsideBubble =
            shouldFrameMessageBubbleSupplement(
                bodyText = null,
                invalidationWarning = null,
                showSenderHeader = true,
            )

        assertTrue(supplementInsideBubble)
        assertEquals(
            customBubbleContentColor,
            messageBubbleSupplementContentColor(
                supplementInsideBubble = supplementInsideBubble,
                bubbleContentColor = customBubbleContentColor,
                outsideContentColor = outsideFooterColor,
            ),
        )
    }

    @Test
    fun amoledBubbleChromeColorCodesSentAndReceivedMessages() {
        var sentBorder: BorderStroke? = null
        var receivedBorder: BorderStroke? = null
        var sentTimestamp = Color.Unspecified
        var receivedTimestamp = Color.Unspecified
        var expectedSentAccent = Color.Unspecified
        var expectedReceivedAccent = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val sent = messageBubbleBorder(highlighted = false, mine = true)
                val received = messageBubbleBorder(highlighted = false, mine = false)
                val sentTime = messageBubbleTimestampColor(mine = true, deleted = false)
                val receivedTime = messageBubbleTimestampColor(mine = false, deleted = false)
                val sentAccent = MaterialTheme.colorScheme.primary
                val receivedAccent = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

                SideEffect {
                    sentBorder = sent
                    receivedBorder = received
                    sentTimestamp = sentTime
                    receivedTimestamp = receivedTime
                    expectedSentAccent = sentAccent
                    expectedReceivedAccent = receivedAccent
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(2.dp, requireNotNull(sentBorder).width)
            assertEquals(2.dp, requireNotNull(receivedBorder).width)
            assertEquals(expectedSentAccent, borderColor(sentBorder))
            assertEquals(expectedReceivedAccent, borderColor(receivedBorder))
            assertNotEquals(borderColor(sentBorder), borderColor(receivedBorder))
            assertEquals(expectedSentAccent, sentTimestamp)
            assertEquals(expectedReceivedAccent, receivedTimestamp)
        }
    }

    @Test
    fun amoledCustomColorOverridesDirectionalBubbleBorder() {
        val customArgb = 0xFF336699L
        var backgroundArgb = 0L
        var contentArgb = 0L
        var expectedContentArgb = 0L
        var sentBorder: BorderStroke? = null
        var receivedBorder: BorderStroke? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val presentation =
                    messageBubblePresentation(
                        deleted = false,
                        mine = true,
                        customArgb = customArgb,
                    )
                val expectedContent =
                    MaterialTheme.colorScheme.onSurfaceVariant
                        .toArgb()
                        .toLong() and 0xFFFFFFFFL
                val sent =
                    messageBubbleBorder(
                        highlighted = false,
                        mine = true,
                        customArgb = presentation.borderOverrideArgb,
                    )
                val received =
                    messageBubbleBorder(
                        highlighted = false,
                        mine = false,
                        customArgb = presentation.borderOverrideArgb,
                    )

                SideEffect {
                    backgroundArgb = presentation.backgroundArgb
                    contentArgb = presentation.contentArgb
                    expectedContentArgb = expectedContent
                    sentBorder = sent
                    receivedBorder = received
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(OPAQUE_BLACK_ARGB, backgroundArgb)
            assertEquals(expectedContentArgb, contentArgb)
            assertEquals(2.dp, requireNotNull(sentBorder).width)
            assertEquals(2.dp, requireNotNull(receivedBorder).width)
            assertEquals(colorFromArgb(customArgb), borderColor(sentBorder))
            assertEquals(colorFromArgb(customArgb), borderColor(receivedBorder))
        }
    }

    @Test
    fun highlightedAmoledCustomBubbleRetainsItsCustomBorder() {
        val customArgb = 0xFF336699L
        var highlightedBorder: BorderStroke? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val border =
                    messageBubbleBorder(
                        highlighted = true,
                        mine = false,
                        customArgb = customArgb,
                    )
                SideEffect { highlightedBorder = border }
            }
        }

        composeRule.runOnIdle {
            assertEquals(2.dp, requireNotNull(highlightedBorder).width)
            assertEquals(colorFromArgb(customArgb), borderColor(highlightedBorder))
        }
    }

    @Test
    fun standardDarkBubbleChromeKeepsExistingStyling() {
        var sentBorder: BorderStroke? = BorderStroke(2.dp, Color.Red)
        var receivedBorder: BorderStroke? = BorderStroke(2.dp, Color.Red)
        var sentTimestamp = Color.Unspecified
        var receivedTimestamp = Color.Unspecified
        var expectedSentTimestamp = Color.Unspecified
        var expectedReceivedTimestamp = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val sent = messageBubbleBorder(highlighted = false, mine = true)
                val received = messageBubbleBorder(highlighted = false, mine = false)
                val sentTime = messageBubbleTimestampColor(mine = true, deleted = false)
                val receivedTime = messageBubbleTimestampColor(mine = false, deleted = false)
                val sentExpected = MaterialTheme.colorScheme.onPrimaryContainer
                val receivedExpected = MaterialTheme.colorScheme.onSurfaceVariant

                SideEffect {
                    sentBorder = sent
                    receivedBorder = received
                    sentTimestamp = sentTime
                    receivedTimestamp = receivedTime
                    expectedSentTimestamp = sentExpected
                    expectedReceivedTimestamp = receivedExpected
                }
            }
        }

        composeRule.runOnIdle {
            assertNull(sentBorder)
            assertNull(receivedBorder)
            assertEquals(expectedSentTimestamp, sentTimestamp)
            assertEquals(expectedReceivedTimestamp, receivedTimestamp)
        }
    }

    @Test
    fun messageBubbleFillColorPreservesThemeAndSemanticPrecedence() {
        val customArgb = 0xFF336699L
        var lightMine = Color.Unspecified
        var lightMineExpected = Color.Unspecified
        var darkReceived = Color.Unspecified
        var darkReceivedExpected = Color.Unspecified
        var customBackgroundArgb = 0L
        var customContentArgb = 0L
        var amoledMine = Color.Unspecified
        var amoledDeleted = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                val actual = messageBubbleFillColor(deleted = false, mine = true)
                val expected = MaterialTheme.colorScheme.primaryContainer
                SideEffect {
                    lightMine = actual
                    lightMineExpected = expected
                }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val actual = messageBubbleFillColor(deleted = false, mine = false)
                val expected = MaterialTheme.colorScheme.surfaceVariant
                val custom =
                    messageBubblePresentation(
                        deleted = false,
                        mine = true,
                        customArgb = customArgb,
                    )
                SideEffect {
                    darkReceived = actual
                    darkReceivedExpected = expected
                    customBackgroundArgb = custom.backgroundArgb
                    customContentArgb = custom.contentArgb
                }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val mine = messageBubbleFillColor(deleted = false, mine = true)
                val deleted = messageBubbleFillColor(deleted = true, mine = false)
                SideEffect {
                    amoledMine = mine
                    amoledDeleted = deleted
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(lightMineExpected, lightMine)
            assertEquals(darkReceivedExpected, darkReceived)
            assertEquals(customArgb, customBackgroundArgb)
            assertEquals(readableTextArgb(customArgb), customContentArgb)
            assertEquals(Color.Black, amoledMine)
            assertEquals(Color.Black, amoledDeleted)
        }
    }

    @Test
    fun amoledSelectionTintIsVisiblyDistinctFromSurface() {
        var tint = Color.Unspecified
        var surface = Color.Unspecified
        var primary = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                tint = messageBubbleSelectionRowTint(selected = true)
                surface = MaterialTheme.colorScheme.surface
                primary = MaterialTheme.colorScheme.primary
            }
        }

        composeRule.runOnIdle {
            assertNotEquals(Color.Transparent, tint)
            assertNotEquals(surface, tint)
            assertEquals(primary.copy(alpha = 0.32f), tint)
        }
    }

    @Test
    fun standardThemesUseReadableSelectionTintBehindContent() {
        var lightTint = Color.Unspecified
        var darkTint = Color.Unspecified
        var lightPrimary = Color.Unspecified
        var darkPrimary = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                lightTint = messageBubbleSelectionRowTint(selected = true)
                lightPrimary = MaterialTheme.colorScheme.primary
            }
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                darkTint = messageBubbleSelectionRowTint(selected = true)
                darkPrimary = MaterialTheme.colorScheme.primary
            }
        }

        composeRule.runOnIdle {
            assertEquals(lightPrimary.copy(alpha = 0.24f), lightTint)
            assertEquals(darkPrimary.copy(alpha = 0.24f), darkTint)
            assertNotEquals(Color.Transparent, lightTint)
            assertNotEquals(Color.Transparent, darkTint)
        }
    }

    private fun borderColor(border: BorderStroke?): Color = (requireNotNull(border).brush as SolidColor).value
}
