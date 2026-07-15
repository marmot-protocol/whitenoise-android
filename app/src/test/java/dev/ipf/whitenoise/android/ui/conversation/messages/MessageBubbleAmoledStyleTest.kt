package dev.ipf.whitenoise.android.ui.conversation.messages

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

    private fun borderColor(border: BorderStroke?): Color = (requireNotNull(border).brush as SolidColor).value
}
