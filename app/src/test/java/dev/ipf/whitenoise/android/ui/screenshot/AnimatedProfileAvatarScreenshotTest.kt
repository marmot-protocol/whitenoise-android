package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.ui.common.ANIMATED_PROFILE_AVATAR_TAG
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A GIF profile picture draws its first frame with no layout change next to a static picture and an
 * undecodable one, whether the animation is attached or reduced motion keeps it still.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AnimatedProfileAvatarScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Seeds one static and one animated avatar in the shared loader. */
    @Before
    fun seedAvatars() {
        val gif = twoFrameGif()
        val firstFrame = BitmapFactory.decodeByteArray(gif, 0, gif.size).asImageBitmap()
        AvatarImageLoader.putCachedAnimated(GIF_URL, firstFrame, gif)
        val still = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(STATIC_BLUE) }
        AvatarImageLoader.putCached(STATIC_URL, still.asImageBitmap())
    }

    /** Restores the animator scale and empties the loader for other fixtures. */
    @After
    fun tearDown() {
        setAnimatorScale(1f)
        AvatarImageLoader.clear()
    }

    /** Light theme with the animation attached: the GIF shows its first frame in place. */
    @Test
    fun animatedAvatarLight() = captureAnimating(darkTheme = false, name = "animated_profile_avatar_light")

    /** Dark theme with the animation attached. */
    @Test
    fun animatedAvatarDark() = captureAnimating(darkTheme = true, name = "animated_profile_avatar_dark")

    /** Reduced motion never attaches the animation, and the still frame is pixel-identical in place. */
    @Test
    fun reducedMotionAvatarLight() {
        setAnimatorScale(0f)
        composeRule.setContent { Avatars(darkTheme = false) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()
        composeRule
            .onNodeWithTag(ROW_TAG)
            .captureRoboImage("src/test/snapshots/animated_profile_avatar_reduced_motion_light.png")
    }

    /** Captures the row once the off-main decode has attached the animated layer. */
    private fun captureAnimating(
        darkTheme: Boolean,
        name: String,
    ) {
        composeRule.setContent { Avatars(darkTheme) }
        composeRule.waitUntil(DECODE_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(ANIMATED_PROFILE_AVATAR_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ROW_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Static, animated and undecodable profile pictures side by side. */
    @Composable
    private fun Avatars(darkTheme: Boolean) {
        WhiteNoiseTheme(darkTheme = darkTheme) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier.padding(16.dp).testTag(ROW_TAG),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Avatar(title = "Static", seed = "static", size = 56.dp, pictureUrl = STATIC_URL)
                    Avatar(title = "Animated", seed = "animated", size = 56.dp, pictureUrl = GIF_URL)
                    Avatar(title = "Broken", seed = "broken", size = 56.dp, pictureUrl = BROKEN_URL)
                }
            }
        }
    }

    /** Writes the global animator scale Robolectric exposes to the app. */
    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private companion object {
        const val ROW_TAG = "animated-avatar-row"
        const val GIF_URL = "https://profiles.example/animated.png"
        const val STATIC_URL = "https://profiles.example/static.gif"
        const val BROKEN_URL = "https://profiles.example/broken.gif"
        const val STATIC_BLUE = 0xFF1E88E5.toInt()
        const val DECODE_TIMEOUT_MS = 5_000L
    }
}
