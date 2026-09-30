package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
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

    /** AMOLED still has one readable attribution and timestamp row per state. */
    @Test
    fun giphyCardsAmoled() {
        render(darkTheme = true, amoled = true)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_cards_amoled.png")
    }

    /** Long creator text yields to outgoing metadata at large font scale in RTL. */
    @Test
    @Config(qualifiers = "en-w300dp-h950dp-mdpi")
    fun giphyCardsLargeRtl() {
        render(darkTheme = false, rtl = true, scale = 1.7f)
        composeRule
            .onAllNodesWithContentDescription("via GIPHY · Marmot Studio with a very long creator attribution")
            .assertCountEquals(2)
        composeRule.onAllNodesWithTag("giphy.message-footer", useUnmergedTree = true).assertCountEquals(3)
        val attributions =
            composeRule.onAllNodesWithTag("giphy.attribution", useUnmergedTree = true).fetchSemanticsNodes()
        val metadata =
            composeRule.onAllNodesWithTag("giphy.message-footer", useUnmergedTree = true).fetchSemanticsNodes()
        attributions.zip(metadata).forEach { (credit, details) ->
            assertTrue(credit.boundsInRoot.width > 40f)
            assertTrue(credit.boundsInRoot.left >= details.boundsInRoot.right)
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_cards_large_rtl.png")
    }

    /** Exercises the same footer component used by real incoming and outgoing GIPHY bubbles. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
    ) {
        val media =
            RemoteGiphyMedia(
                url = "https://media.giphy.com/media/abc/giphy.gif",
                attribution = "Marmot Studio with a very long creator attribution",
            )
        val presentation = DecodedAttachmentPresentation.Static(sampleBitmap())
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = scale) {
                    Surface {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(16.dp),
                        ) {
                            RemoteGiphyMediaCard(
                                media = media,
                                footer = GiphyMessageFooter("10:41 AM", false, MessageStatus.Sent, null, null),
                                presentation = presentation,
                                loading = false,
                                failed = false,
                                onLoad = {},
                            )
                            RemoteGiphyMediaCard(
                                media = media.copy(attribution = null),
                                footer = GiphyMessageFooter("10:42 AM", true, MessageStatus.Pending, null, null),
                                presentation = null,
                                loading = true,
                                failed = false,
                                onLoad = {},
                            )
                            RemoteGiphyMediaCard(
                                media = media,
                                footer =
                                    GiphyMessageFooter(
                                        "10:43 AM",
                                        true,
                                        MessageStatus.Failed,
                                        "Edited a very long time ago",
                                        {},
                                    ),
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
