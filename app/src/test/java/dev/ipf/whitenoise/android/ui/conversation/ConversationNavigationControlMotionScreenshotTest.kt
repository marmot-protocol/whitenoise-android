package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The mention chip and Jump to newest grow out of the stack's bottom-end corner and shrink back into
 * it, so they no longer pop in and out over the transcript. Reduced motion keeps the instant swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w200dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationNavigationControlMotionScreenshotTest {
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

    private val visible = mutableStateOf(false)
    private var jumpClicks = 0

    @Test fun controlsGrowFromTheBottomEndCornerLight() {
        render(dark = false, rtl = false, motion = true)
        show(true)
        advanceFrames(CONVERSATION_NAVIGATION_CONTROL_MOTION_MILLIS / 2L)
        capture("conversation_navigation_controls_enter_mid_light")
        advanceFrames(SETTLE_MILLIS)
        capture("conversation_navigation_controls_shown_light")
    }

    @Test fun controlsShrinkIntoTheBottomEndCornerRtlDark() {
        visible.value = true
        render(dark = true, rtl = true, motion = true)
        show(false)
        advanceFrames(CONVERSATION_NAVIGATION_CONTROL_MOTION_MILLIS / 2L)
        capture("conversation_navigation_controls_exit_mid_rtl_dark")
        advanceFrames(SETTLE_MILLIS)
        capture("conversation_navigation_controls_hidden_rtl_dark")
    }

    @Test fun aLeavingControlIgnoresTapsAndAccessibility() {
        visible.value = true
        render(dark = false, rtl = false, motion = true)
        tapJumpToNewest()
        assertEquals(1, jumpClicks)
        show(false)
        advanceFrames(CONVERSATION_NAVIGATION_CONTROL_MOTION_MILLIS / 2L)
        tapJumpToNewest()
        assertEquals(1, jumpClicks)
        composeRule.onAllNodesWithText("@ 3").assertCountEquals(0)
    }

    /** Taps just inside the stack's bottom-end corner, where Jump to newest sits and shrinks into. */
    private fun tapJumpToNewest() {
        composeRule.onRoot().performTouchInput { click(Offset(width - 24.dp.toPx(), height - 24.dp.toPx())) }
        composeRule.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
    }

    @Test fun reducedMotionShowsTheControlsAtOnce() {
        render(dark = true, rtl = false, motion = false)
        show(true)
        // AnimatedVisibility settles a None transition on the next frame, not after the full duration.
        advanceFrames(FRAME_MILLIS)
        capture("conversation_navigation_controls_reduced_motion_dark")
    }

    private fun render(
        dark: Boolean,
        rtl: Boolean,
        motion: Boolean,
    ) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize()) {
                            ConversationNavigationStack(composerOverlap = 0.dp) {
                                ConversationNavigationControl(visible = visible.value, motionEnabled = motion) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        modifier = Modifier.size(width = 52.dp, height = 34.dp),
                                    ) {
                                        Box { Text("@ 3") }
                                    }
                                }
                                ConversationNavigationControl(visible = visible.value, motionEnabled = motion) {
                                    ConversationJumpToNewestButton(unreadIncomingCount = 4, onClick = { jumpClicks++ })
                                }
                            }
                        }
                    }
                }
            }
        }
        advanceFrames(SETTLE_MILLIS)
    }

    private fun show(value: Boolean) {
        update { visible.value = value }
        composeRule.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper(FRAME_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** Steps whole frames so each animation tick is composed and drawn before the capture. */
    private fun advanceFrames(millis: Long) {
        repeat((millis / FRAME_MILLIS).toInt().coerceAtLeast(1)) {
            composeRule.mainClock.advanceTimeByFrame()
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

private const val SETTLE_MILLIS = 600L
private const val FRAME_MILLIS = 16L
