package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Chats and Settings switch through a bubble on the end edge: opening grows Settings out of it,
 * closing shrinks a painted bubble back into it over the live Chats route. Deeper routes keep the
 * slide, and reduced motion settles both directions at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBubbleMotionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f

    /** Pins the animator scale because earlier suites in the same process can leave another value behind. */
    @Before
    fun enableTestMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    @After
    fun restoreMotionPolicy() {
        setAnimatorScale(originalAnimatorScale)
    }

    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }

    private val depth = mutableStateOf(0)
    private val animations = mutableStateOf(true)

    @Test fun onlyTheTopLevelPairUsesTheBubble() {
        assertTrue(usesSettingsBubble(0, 1))
        assertTrue(usesSettingsBubble(1, 0))
        assertFalse(usesSettingsBubble(1, 2))
        assertFalse(usesSettingsBubble(2, 1))
        assertFalse(usesSettingsBubble(0, 2))
    }

    @Test fun openGrowsSettingsFromTheEndEdgeLight() = open("light", dark = false, largeRtl = false)

    @Test fun openGrowsSettingsFromTheEndEdgeLargeRtlDark() = open("large_rtl_dark", dark = true, largeRtl = true)

    @Test fun closeShrinksTheBubbleBackIntoTheEndEdgeDark() {
        render(dark = true, largeRtl = false, start = 1)
        // The paused test looper only draws on capture; draw settled Settings once like a real frame.
        composeRule.onRoot().captureToImage()
        update { depth.value = 0 }
        composeRule.mainClock.advanceTimeByFrame()
        advanceFrames(SETTINGS_BUBBLE_CLOSE_MILLIS / 2L)
        capture("settings_bubble_close_mid_dark")
        advanceFrames(SETTINGS_BUBBLE_CLOSE_MILLIS.toLong())
        capture("settings_bubble_close_settled_dark")
    }

    @Test fun reducedMotionSwitchesAtOnce() {
        render(dark = false, largeRtl = false, start = 0)
        update { animations.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        update { depth.value = 1 }
        composeRule.mainClock.advanceTimeByFrame()
        capture("settings_bubble_reduced_motion_light")
    }

    private fun open(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean,
    ) {
        render(dark, largeRtl, start = 0)
        update { depth.value = 1 }
        composeRule.mainClock.advanceTimeByFrame()
        advanceFrames(SETTINGS_BUBBLE_OPEN_MILLIS / 3L)
        capture("settings_bubble_open_early_$variant")
        advanceFrames(SETTINGS_BUBBLE_OPEN_MILLIS / 3L)
        capture("settings_bubble_open_late_$variant")
        advanceFrames(SETTINGS_BUBBLE_OPEN_MILLIS.toLong())
        capture("settings_bubble_open_settled_$variant")
    }

    private fun render(
        dark: Boolean,
        largeRtl: Boolean,
        start: Int,
    ) {
        depth.value = start
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (largeRtl) 1.6f else 1f),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        SettingsRouteMotion(
                            target = depth.value,
                            depth = depth.value,
                            ownerKey = "account",
                            animationsEnabled = animations.value,
                            topLevelBubble = true,
                        ) { route -> Route(route) }
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
    }

    /** Steps whole frames so each animation tick is composed and drawn before the capture. */
    private fun advanceFrames(millis: Long) {
        repeat((millis / FRAME_MILLIS).toInt().coerceAtLeast(1)) {
            composeRule.mainClock.advanceTimeByFrame()
            // The paused Robolectric looper also needs its frame so the view draws each animation tick.
            ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Writes test state on the UI thread and publishes it before the next manually advanced frame.
     */
    private fun update(block: () -> Unit) {
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}

@androidx.compose.runtime.Composable
private fun Route(route: Int) {
    val rows = if (route == 0) CHAT_ROWS else SETTINGS_ROWS
    Surface(Modifier.fillMaxSize().testTag("route.$route")) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (route == 0) "Chats" else "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            rows.forEach { row ->
                Text(row, Modifier.fillMaxWidth().padding(vertical = 14.dp))
                HorizontalDivider()
            }
        }
    }
}

private const val SETTLE_MILLIS = 1_000L
private const val FRAME_MILLIS = 16L
private val CHAT_ROWS = listOf("Alice", "Book club", "Bob", "Family", "Weekend plans", "Carol")
private val SETTINGS_ROWS = listOf("Profile", "Appearance", "Notifications", "Privacy", "Dictation", "About")
