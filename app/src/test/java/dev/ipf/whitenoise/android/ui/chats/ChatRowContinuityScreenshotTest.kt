package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins the actual unread row at distinct selection frames, including narrow double-size RTL metadata. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ChatRowContinuityScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale = 1f

    /** Keep the clock-controlled captures independent of the host's reduced-motion preference. */
    @Before
    fun enableMotion() {
        previousScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restore the isolated Android setting after each image. */
    @After
    fun restoreMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    /** The first selected frame keeps the existing metadata while the new leading strip starts moving. */
    @Test
    fun firstSelectedFrame() = capture("first", elapsed = 0)

    /** The middle frame pins actual layout movement instead of only its final checked state. */
    @Test
    fun middleSelectedFrame() = capture("middle", elapsed = 80)

    /** The settled row retains unread count and timestamp beside its selected leading target. */
    @Test
    fun settledSelectedFrame() = capture("settled", elapsed = 240)

    /** Dark-mode selected metadata keeps its normal trailing palette and hierarchy. */
    @Test
    fun darkSelectedFrame() = capture("dark", elapsed = 240, dark = true)

    /** AMOLED and double-size RTL text keep the selected unread row legible in a narrow viewport. */
    @Test
    fun amoledLargeRtlSelectedFrame() = capture("amoled_large_rtl", elapsed = 240, dark = true, largeRtl = true)

    /** Changes only selection state; the same production row and unread projection survive every captured phase. */
    private fun capture(variant: String, elapsed: Long, dark: Boolean = false, largeRtl: Boolean = false) {
        val selected = mutableStateOf(false)
        val appState = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item(unread = true)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = largeRtl) {
                    Surface(Modifier.width(if (largeRtl) 280.dp else 320.dp).testTag("continuity.row")) {
                        ChatRow(
                            item,
                            appState,
                            onClick = {},
                            onOpenProfile = {},
                            selectionMode = selected.value,
                            selected = selected.value,
                        )
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selected.value = true }
        rule.mainClock.advanceTimeByFrame()
        if (elapsed > 0) rule.mainClock.advanceTimeBy(elapsed)
        rule.onNodeWithTag("continuity.row").captureRoboImage("src/test/snapshots/chat_row_continuity_$variant.png")
    }
}
