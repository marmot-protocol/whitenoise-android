package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
                val sentTime = messageBubbleTimestampColor(invalidated = false, mine = true, deleted = false)
                val receivedTime = messageBubbleTimestampColor(invalidated = false, mine = false, deleted = false)
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
    fun amoledInvalidatedBubbleSuppressesDirectionalAccent() {
        var sentBorder: BorderStroke? = BorderStroke(2.dp, Color.Red)
        var receivedBorder: BorderStroke? = BorderStroke(2.dp, Color.Red)
        var timestamp = Color.Unspecified
        var expectedTimestamp = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val invalidatedSentBorder =
                    messageBubbleBorder(
                        highlighted = false,
                        mine = true,
                        invalidated = true,
                    )
                val invalidatedReceivedBorder =
                    messageBubbleBorder(
                        highlighted = false,
                        mine = false,
                        invalidated = true,
                    )
                val invalidatedTimestamp =
                    messageBubbleTimestampColor(
                        invalidated = true,
                        mine = true,
                        deleted = false,
                    )
                val expected = MaterialTheme.colorScheme.onErrorContainer

                SideEffect {
                    sentBorder = invalidatedSentBorder
                    receivedBorder = invalidatedReceivedBorder
                    timestamp = invalidatedTimestamp
                    expectedTimestamp = expected
                }
            }
        }

        composeRule.runOnIdle {
            assertNull(sentBorder)
            assertNull(receivedBorder)
            assertEquals(expectedTimestamp, timestamp)
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
                val sentTime = messageBubbleTimestampColor(invalidated = false, mine = true, deleted = false)
                val receivedTime = messageBubbleTimestampColor(invalidated = false, mine = false, deleted = false)
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
        var amoledInvalidated = Color.Unspecified
        var amoledInvalidatedExpected = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                val actual = messageBubbleFillColor(invalidated = false, deleted = false, mine = true)
                val expected = MaterialTheme.colorScheme.primaryContainer
                SideEffect {
                    lightMine = actual
                    lightMineExpected = expected
                }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val actual = messageBubbleFillColor(invalidated = false, deleted = false, mine = false)
                val expected = MaterialTheme.colorScheme.surfaceVariant
                val custom =
                    messageBubblePresentation(
                        invalidated = false,
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
                val mine = messageBubbleFillColor(invalidated = false, deleted = false, mine = true)
                val deleted = messageBubbleFillColor(invalidated = false, deleted = true, mine = false)
                val invalidated = messageBubbleFillColor(invalidated = true, deleted = false, mine = true)
                val invalidatedExpected = MaterialTheme.colorScheme.errorContainer
                SideEffect {
                    amoledMine = mine
                    amoledDeleted = deleted
                    amoledInvalidated = invalidated
                    amoledInvalidatedExpected = invalidatedExpected
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
            assertEquals(amoledInvalidatedExpected, amoledInvalidated)
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
