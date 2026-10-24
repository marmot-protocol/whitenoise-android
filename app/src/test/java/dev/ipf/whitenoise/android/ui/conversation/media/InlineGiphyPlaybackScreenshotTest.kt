package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real GIPHY card renders changing copied pixels and leaves a capacity-waiting card's still visible. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class InlineGiphyPlaybackScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val transport = InlineAnimationTestTransport()

    /** Removes the explicit JVM IPC fixture after rendering. */
    @After
    fun cleanUp() {
        composeRule.runOnIdle { transport.animations.forEach { it.close() } }
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
    }

    /** Light cards show a blue delivered frame beside the queued red first frame. */
    @Test fun inlineGiphyLight() = capture(false, "light")

    /** Dark cards retain the same metadata and stable fallback while animation advances. */
    @Test fun inlineGiphyDark() = capture(true, "dark")

    /** Uses admitted GIF bytes and the production animation/presentation path with only Binder replaced. */
    private fun capture(
        dark: Boolean,
        suffix: String,
    ) {
        IsolatedMediaDecoder.installTestTransport(transport)
        val media = requireNotNull(RemoteGiphyMedia.parse("https://media3.giphy.com/media/fixture/giphy.gif"))
        val footer = GiphyMessageFooter("12:34", false, MessageStatus.Sent, null, null)
        val first = DecodedAttachmentPresentation.Static(transport.pixels(Color.RED), true)
        val second = DecodedAttachmentPresentation.Static(transport.pixels(Color.RED), true)
        val focused =
            object : WindowInfo {
                override val isWindowFocused = true
            }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalWindowInfo provides focused) {
                    Column {
                        RemoteGiphyMediaCard(
                            media,
                            footer,
                            first,
                            false,
                            false,
                            {},
                            animationSource = InlineAnimationSource("first", true) { twoFrameGif() },
                        )
                        RemoteGiphyMediaCard(
                            media,
                            footer,
                            second,
                            false,
                            false,
                            {},
                            animationSource = InlineAnimationSource("second", true) { twoFrameGif() },
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) { transport.animations.size == 1 }
        composeRule.runOnIdle {
            val animation = transport.animations.single()
            assertTrue(animation.isRunning)
            animation.accept(transport.pixels(Color.BLUE))
            assertEquals(2L, animation.presentedFrame)
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/inline_giphy_playback_$suffix.png")
    }
}
