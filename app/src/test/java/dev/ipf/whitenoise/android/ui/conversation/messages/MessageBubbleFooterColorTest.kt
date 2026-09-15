package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.messageFooterLabelColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleFooterColorTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** An ordinary bubble's footer uses the quiet readable gray, not the full content colour. */
    @Test
    fun ordinaryBubbleFooterIsQuietGrayThatStaysReadable() {
        var footer = Color.Unspecified
        var container = Color.Unspecified
        var content = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                val primary = MaterialTheme.colorScheme.primary
                val onPrimary = MaterialTheme.colorScheme.onPrimary
                val color =
                    messageBubbleFooterColor(
                        mine = true,
                        persistedFailure = false,
                        bubbleBackgroundColor = primary,
                        bubbleContentColor = onPrimary,
                    )
                SideEffect {
                    footer = color
                    container = primary
                    content = onPrimary
                }
            }
        }

        composeRule.runOnIdle {
            assertNotEquals(content, footer)
            assertEquals(messageFooterLabelColor(container, content), footer)
            assertEquals(footer.red, footer.green, 0.001f)
            assertEquals(footer.green, footer.blue, 0.001f)
            assertTrue(contrastRatio(container, footer) >= 4.5f)
        }
    }

    /** A persisted failure keeps the error pairing so the footer still reads as an error. */
    @Test
    fun persistedFailureKeepsTheErrorColour() {
        var footer = Color.Unspecified
        var expected = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                val color =
                    messageBubbleFooterColor(
                        mine = true,
                        persistedFailure = true,
                        bubbleBackgroundColor = MaterialTheme.colorScheme.errorContainer,
                        bubbleContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                val onErrorContainer = MaterialTheme.colorScheme.onErrorContainer
                SideEffect {
                    footer = color
                    expected = onErrorContainer
                }
            }
        }

        composeRule.runOnIdle { assertEquals(expected, footer) }
    }

    /** The AMOLED theme keeps its directional accent for sent bubbles. */
    @Test
    fun amoledFooterKeepsTheDirectionalAccent() {
        var footer = Color.Unspecified
        var expected = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val color =
                    messageBubbleFooterColor(
                        mine = true,
                        persistedFailure = false,
                        bubbleBackgroundColor = Color.Black,
                        bubbleContentColor = Color.White,
                    )
                val primary = MaterialTheme.colorScheme.primary
                SideEffect {
                    footer = color
                    expected = primary
                }
            }
        }

        composeRule.runOnIdle { assertEquals(expected, footer) }
    }

    /** WCAG relative contrast between two opaque colours. */
    private fun contrastRatio(
        a: Color,
        b: Color,
    ): Float {
        val lighter = maxOf(a.luminance(), b.luminance()) + 0.05f
        val darker = minOf(a.luminance(), b.luminance()) + 0.05f
        return lighter / darker
    }
}
