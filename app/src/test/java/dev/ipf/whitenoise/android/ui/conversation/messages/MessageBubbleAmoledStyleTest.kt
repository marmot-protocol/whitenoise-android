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
import dev.ipf.whitenoise.android.state.resolveBubbleColorArgb
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.whiteNoiseBaseColorScheme
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

    /** Amoled custom color keeps monochrome directional bubble border. */
    @Test
    fun amoledCustomColorKeepsMonochromeDirectionalBubbleBorder() {
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
                    )
                val received =
                    messageBubbleBorder(
                        highlighted = false,
                        mine = false,
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
            assertEquals(Color.White, borderColor(sentBorder))
            assertEquals(Color.White.copy(alpha = 0.7f), borderColor(receivedBorder))
        }
    }

    /** Highlight uses the semantic theme role, regardless of stored custom colours. */
    @Test
    fun highlightedAmoledBubbleUsesTheThemeHighlight() {
        var highlightedBorder: BorderStroke? = null
        var expectedHighlight = Color.Unspecified
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val border = messageBubbleBorder(highlighted = true, mine = false)
                val highlight = MaterialTheme.colorScheme.tertiary
                SideEffect {
                    highlightedBorder = border
                    expectedHighlight = highlight
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(2.dp, requireNotNull(highlightedBorder).width)
            assertEquals(expectedHighlight, borderColor(highlightedBorder))
        }
    }

    /** Standard dark bubble chrome uses paired prototype foregrounds. */
    @Test
    fun standardDarkBubbleChromeUsesPairedPrototypeForegrounds() {
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
                val sentExpected = MaterialTheme.colorScheme.onPrimary
                val receivedExpected = MaterialTheme.colorScheme.onSurface

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

    /** Message bubble fill color preserves theme and semantic precedence. */
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
                val expected = MaterialTheme.colorScheme.primary
                SideEffect {
                    lightMine = actual
                    lightMineExpected = expected
                }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val actual = messageBubbleFillColor(deleted = false, mine = false)
                val expected = MaterialTheme.colorScheme.surfaceContainerHigh
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

    /** Standard themes use readable selection tint behind content. */
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

    /** Action accent does not recolor default bubbles or override saved bubble colors. */
    @Test
    fun actionAccentDoesNotRecolorDefaultBubblesOrOverrideSavedBubbleColors() {
        val actionArgb = 0xFF217A44L
        val globalArgb = 0xFF445566L
        val chatArgb = 0xFF994433L
        composeRule.setContent {
            listOf(false, true).forEach { dark ->
                WhiteNoiseTheme(darkTheme = dark, accentColorArgb = actionArgb) {
                    val base = whiteNoiseBaseColorScheme(dark)
                    val mine = messageBubblePresentation(deleted = false, mine = true)
                    val other = messageBubblePresentation(deleted = false, mine = false)
                    val global =
                        messageBubblePresentation(
                            deleted = false,
                            mine = true,
                            customArgb = resolveBubbleColorArgb(null, globalArgb, 0L),
                        )
                    val chat =
                        messageBubblePresentation(
                            deleted = false,
                            mine = false,
                            customArgb = resolveBubbleColorArgb(chatArgb, globalArgb, 0L),
                        )
                    val action = MaterialTheme.colorScheme.primary
                    SideEffect {
                        assertEquals(Color(actionArgb), action)
                        assertEquals(base.primary, colorFromArgb(mine.backgroundArgb))
                        assertEquals(base.onPrimary, colorFromArgb(mine.contentArgb))
                        assertEquals(base.surfaceContainerHigh, colorFromArgb(other.backgroundArgb))
                        assertEquals(base.onSurface, colorFromArgb(other.contentArgb))
                        assertEquals(globalArgb, global.backgroundArgb)
                        assertEquals(readableTextArgb(globalArgb), global.contentArgb)
                        assertEquals(chatArgb, chat.backgroundArgb)
                        assertEquals(readableTextArgb(chatArgb), chat.contentArgb)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun borderColor(border: BorderStroke?): Color = (requireNotNull(border).brush as SolidColor).value
}
