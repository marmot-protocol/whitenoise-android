package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel evidence for the loaded, manual-load, and retry GIPHY card states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class RemoteGiphyMediaBubbleScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun giphyCardsLight() {
        render(darkTheme = false)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_cards_light.png")
    }

    @Test
    fun giphyCardsDark() {
        render(darkTheme = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_cards_dark.png")
    }

    private fun render(darkTheme: Boolean) {
        val media =
            RemoteGiphyMedia(
                url = "https://media.giphy.com/media/abc/giphy.gif",
                attribution = "Marmot Studio",
            )
        val presentation = DecodedAttachmentPresentation.Static(sampleBitmap())
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                Surface {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        RemoteGiphyMediaCard(
                            media = media,
                            presentation = presentation,
                            loading = false,
                            failed = false,
                            onLoad = {},
                        )
                        RemoteGiphyMediaCard(
                            media = media.copy(attribution = null),
                            presentation = null,
                            loading = false,
                            failed = false,
                            onLoad = {},
                        )
                        RemoteGiphyMediaCard(
                            media = media,
                            presentation = null,
                            loading = false,
                            failed = true,
                            onLoad = {},
                        )
                    }
                }
            }
        }
    }

    private fun sampleBitmap(): Bitmap =
        Bitmap.createBitmap(256, 188, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35, 18, 70))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 225, 180) }
            canvas.drawCircle(70f, 94f, 48f, paint)
            paint.color = Color.rgb(255, 190, 70)
            canvas.drawCircle(150f, 82f, 58f, paint)
            paint.color = Color.rgb(245, 90, 135)
            canvas.drawCircle(214f, 118f, 44f, paint)
        }
}
