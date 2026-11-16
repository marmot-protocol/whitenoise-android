package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A peer's custom-emoji reaction draws the artwork its own reaction carries, the same shortcode on another reaction
 * draws that reaction's artwork, and a reaction with no artwork stays literal text, across themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class ReactionArtworkScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Two messages' `:party:` reactions keep their own artwork, and `:none:` stays text, light theme. */
    @Test
    fun eachReactionDrawsItsOwnArtworkLight() = capture(Theme.LIGHT)

    /** The same pills in the dark theme. */
    @Test
    fun eachReactionDrawsItsOwnArtworkDark() = capture(Theme.DARK)

    /** The same pills in the AMOLED theme. */
    @Test
    fun eachReactionDrawsItsOwnArtworkAmoled() = capture(Theme.AMOLED)

    /** At 200% RTL text the artwork stays inside its pill. */
    @Test
    fun eachReactionDrawsItsOwnArtworkLargeRtl() = capture(Theme.LARGE_RTL)

    /** Only shortcode pills ask for artwork, each from its own reaction, and a Unicode emoji never asks. */
    @Test
    fun onlyShortcodeReactionsAskForArtwork() {
        val asked = mutableListOf<String>()
        render(Theme.LIGHT) { asked += it }
        composeRule.waitForIdle()
        assertEquals(listOf(RED_REACTION, BLUE_REACTION, PLAIN_REACTION), asked.distinct())
        composeRule.onAllNodesWithText(":none:", substring = true).fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
    }

    /** Renders two rows of pills in [theme] with a source that paints each known reaction its own colour. */
    private fun render(
        theme: Theme,
        onLoad: (String) -> Unit = {},
    ) {
        val largeRtl = theme == Theme.LARGE_RTL
        val source =
            object : ReactionArtworkSource {
                override fun allowNetwork() = true

                override suspend fun load(
                    reactionMessageIdHex: String,
                    allowNetwork: Boolean,
                ): EmojiArt? {
                    onLoad(reactionMessageIdHex)
                    return when (reactionMessageIdHex) {
                        RED_REACTION -> square(android.graphics.Color.RED)
                        BLUE_REACTION -> square(android.graphics.Color.BLUE)
                        else -> null
                    }
                }
            }
        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = theme != Theme.LIGHT,
                amoled = theme == Theme.AMOLED,
                fontScale = if (largeRtl) 2f else 1f,
            ) {
                CompositionLocalProvider(
                    LocalReactionArtworkSource provides source,
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.testTag(ROW_TAG)) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(8.dp)) {
                            ReactionPillRow(
                                listOf(
                                    ReactionTally(":party:", 2, false, RED_REACTION),
                                    ReactionTally("👍", 1, true, "thumbs"),
                                ),
                                enabled = true,
                                onOpenDetails = {},
                            )
                            ReactionPillRow(
                                listOf(
                                    ReactionTally(":party:", 1, false, BLUE_REACTION),
                                    ReactionTally(":none:", 1, false, PLAIN_REACTION),
                                ),
                                enabled = true,
                                onOpenDetails = {},
                            )
                        }
                    }
                }
            }
        }
    }

    /** Records the pills in [theme] as `reaction_artwork_<theme>.png`. */
    private fun capture(theme: Theme) {
        render(theme)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ROW_TAG).captureRoboImage("src/test/snapshots/reaction_artwork_${theme.slug}.png")
    }

    /** A solid [color] square standing in for decoded emoji artwork. */
    private fun square(color: Int): EmojiArt {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 0f, 32f, 32f, Paint().apply { this.color = color })
        return EmojiArt.Decoded(bitmap.asImageBitmap())
    }

    private enum class Theme(
        val slug: String,
    ) {
        LIGHT("light"),
        DARK("dark"),
        AMOLED("amoled"),
        LARGE_RTL("large_rtl"),
    }

    private companion object {
        const val ROW_TAG = "reaction-artwork-rows"
        const val RED_REACTION = "red-reaction"
        const val BLUE_REACTION = "blue-reaction"
        const val PLAIN_REACTION = "plain-reaction"
    }
}
