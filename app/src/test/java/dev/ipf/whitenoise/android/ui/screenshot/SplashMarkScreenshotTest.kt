package dev.ipf.whitenoise.android.ui.screenshot

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/** Renders the platform-compatible splash mask and guards the mark's circular safe zone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h360dp-mdpi")
class SplashMarkScreenshotTest {
    /** Records the complete black mark against the production light splash surface. */
    @Test
    fun systemSplashShowsCompleteMarkInLightTheme() {
        captureSplash("system_splash_mark_light")
    }

    /** Records the complete white mark against the production dark splash surface. */
    @Test
    @Config(qualifiers = "w360dp-h360dp-night-mdpi")
    fun systemSplashShowsCompleteMarkInDarkTheme() {
        captureSplash("system_splash_mark_dark")
    }

    /** Prevents later artwork changes from crossing the platform's backgroundless-icon safe circle. */
    @Test
    fun splashMarkFitsInsideBackgroundlessPlatformSafeCircle() {
        val context = splashContext(nightMode = false)
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val mark = checkNotNull(context.getDrawable(R.drawable.ic_splash_mark))
        mark.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        mark.draw(Canvas(bitmap))

        val center = (ICON_SIZE_PX - 1) / 2f
        var farthestOpaquePixel = 0f
        for (y in 0 until ICON_SIZE_PX) {
            for (x in 0 until ICON_SIZE_PX) {
                if (bitmap.getPixel(x, y).ushr(24) != 0) {
                    farthestOpaquePixel = maxOf(farthestOpaquePixel, hypot(x - center, y - center))
                }
            }
        }

        assertTrue(
            "Splash mark reaches $farthestOpaquePixel px; the platform exposes a $SAFE_RADIUS_PX px radius",
            farthestOpaquePixel <= SAFE_RADIUS_PX,
        )
    }

    /** Captures the real pre-Android 12 compatibility drawable, which applies the Android 12 icon mask. */
    private fun captureSplash(name: String) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val context = ContextThemeWrapper(activity, R.style.Theme_WhiteNoise_Starting)
            val windowBackground = TypedValue()
            check(context.theme.resolveAttribute(android.R.attr.windowBackground, windowBackground, true))
            val root =
                FrameLayout(context).apply {
                    background = checkNotNull(context.getDrawable(windowBackground.resourceId))
                }
            activity.setContentView(
                root,
                ViewGroup.LayoutParams(SCREEN_SIZE_PX, SCREEN_SIZE_PX),
            )
            root.measure(
                View.MeasureSpec.makeMeasureSpec(SCREEN_SIZE_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SCREEN_SIZE_PX, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            root.captureRoboImage("src/test/snapshots/$name.png")
        }
    }

    /** Resolves the same DayNight resources used by the launch activity's starting theme. */
    private fun splashContext(nightMode: Boolean): Context {
        val base = RuntimeEnvironment.getApplication().withNightMode(nightMode)
        return ContextThemeWrapper(base, R.style.Theme_WhiteNoise_Starting)
    }

    /** Returns a configuration context with only the requested night-mode flag changed. */
    private fun Context.withNightMode(nightMode: Boolean): Context {
        val nightFlag = if (nightMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightFlag
        return createConfigurationContext(config)
    }

    private companion object {
        const val SCREEN_SIZE_PX = 360
        const val ICON_SIZE_PX = 288
        const val SAFE_RADIUS_PX = 96f
    }
}
