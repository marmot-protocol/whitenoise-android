package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Same-named custom reactions keep source-specific images; unavailable artwork stays literal. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class CustomReactionArtworkScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Native artwork in the standard light palette. */
    @Test
    fun light() = capture("light", dark = false)

    /** Native artwork and missing-image fallback in the dark palette. */
    @Test
    fun dark() = capture("dark", dark = true)

    /** AMOLED retains the native image and literal missing-artwork fallback. */
    @Test
    fun amoled() = capture("amoled", dark = true, amoled = true)

    /** Large RTL labels preserve the source-specific chip and accessible fallback. */
    @Test
    fun largeRtl() = capture("large_rtl", dark = true, rtl = true)

    /** Mounts production pills with two independent definitions and one unavailable native source. */
    private fun capture(
        name: String,
        dark: Boolean,
        rtl: Boolean = false,
        amoled: Boolean = false,
    ) {
        val images =
            listOf(Color.RED, Color.BLUE).map { color ->
                EmojiArt.Decoded(
                    Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap(),
                )
            }
        val localImage =
            Bitmap
                .createBitmap(
                    12,
                    12,
                    Bitmap.Config.ARGB_8888,
                ).apply { eraseColor(Color.GREEN) }
                .asImageBitmap()
        val local = CustomEmojiSet(listOf(CustomEmoji(":same:", File("same.png"), localImage)))
        composeRule.setContent {
            val density = LocalDensity.current
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                CompositionLocalProvider(
                    LocalCustomEmoji provides local,
                    LocalDensity provides Density(density.density, if (rtl) 1.6f else 1f),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(
                            Modifier.width(320.dp).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            listOf(
                                "First definition",
                                "Different image, same code",
                                "Artwork unavailable",
                            ).forEachIndexed {
                                index,
                                label,
                                ->
                                Text(label)
                                ReactionPillRow(
                                    tallies =
                                        listOf(
                                            ReactionTally(
                                                if (index ==
                                                    2
                                                ) {
                                                    ":marmot:"
                                                } else {
                                                    ":same:"
                                                },
                                                2,
                                                false,
                                                "reaction-$index",
                                            ),
                                        ),
                                    enabled = true,
                                    onOpenDetails = {},
                                    artworkByReaction = mapOf("reaction-0" to images[0], "reaction-1" to images[1]),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/custom_reaction_artwork_$name.png")
    }
}
