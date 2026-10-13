package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.media.sandbox.AnimationSessionControl
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
import dev.ipf.whitenoise.android.ui.conversation.media.AnimatedDrawableAttachmentImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.Color as ComposeColor

/** Only raw pixels enter the app-owned drawable; actual worker playback has separate device tests. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w240dp-h240dp-mdpi")
class IsolatedAnimationPixelsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun firstFrameLight() = capture(false, "light")

    @Test fun firstFrameDark() = capture(true, "dark")

    private fun capture(
        dark: Boolean,
        suffix: String,
    ) {
        val pixels = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        pixels.eraseColor(Color.argb(128, 255, 0, 0))
        val session =
            object : AnimationSessionControl {
                override fun play() = Unit

                override fun pause() = Unit

                override fun isOpen() = true

                override fun close() = Unit
            }
        val drawable = IsolatedAnimationDrawable(pixels, session)
        try {
            composeRule.setContent {
                Box(Modifier.fillMaxSize().background(if (dark) ComposeColor.Black else ComposeColor.White)) {
                    AnimatedDrawableAttachmentImage(
                        drawable,
                        "Animated image",
                        ContentScale.Fit,
                        Modifier.size(192.dp),
                        active = false,
                    )
                }
            }
            composeRule.onRoot().captureRoboImage("src/test/snapshots/isolated_animation_pixels_$suffix.png")
        } finally {
            composeRule.runOnIdle { drawable.close() }
        }
    }
}
