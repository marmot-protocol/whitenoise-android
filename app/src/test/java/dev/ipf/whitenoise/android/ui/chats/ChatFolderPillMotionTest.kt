package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
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

/** A disappearing fill must not acquire a dark tint from the transparent animation endpoint. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ChatFolderPillMotionTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale = 1f

    /** Enable real Compose interpolation independently of the isolated host's motion preference. */
    @Before
    fun enableMotion() {
        previousScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restore the setting even when a sampled frame fails. */
    @After
    fun restoreMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    /** Removing selection changes semantics immediately without darkening the fading fill. */
    @Test
    fun deselectingPreservesFillHue() = assertMatchingSurfaceStaysUntinted(initiallySelected = true)

    /** Adding selection must not introduce a dark flash from the initially transparent endpoint. */
    @Test
    fun selectingPreservesFillHue() = assertMatchingSurfaceStaysUntinted(initiallySelected = false)

    /** Matching opaque endpoints isolate tint drift: every intermediate alpha must render the same surface. */
    private fun assertMatchingSurfaceStaysUntinted(initiallySelected: Boolean) {
        val selected = mutableStateOf(initiallySelected)
        val fill = Color(0xFFB6D5E5)
        rule.setContent {
            WhiteNoiseTheme {
                Surface(color = fill) {
                    ChatFolderPill("Folder", selected.value, "pill", fill, onClick = {})
                }
            }
        }
        assertFillPixel(fill)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            selected.value = !initiallySelected
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val pill = rule.onNodeWithTag("pill")
        if (initiallySelected) pill.assertIsNotSelected() else pill.assertIsSelected()
        rule.mainClock.advanceTimeByFrame()
        repeat(6) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertFillPixel(fill)
        }
    }

    /** Sample the capsule's full-height left interior, away from text, rounded edges and hit-target padding. */
    private fun assertFillPixel(expected: Color) {
        val pixels = rule.onNodeWithTag("pill").captureToImage().toPixelMap()
        val actual = pixels[12, pixels.height / 2]
        assertEquals("red tint", expected.red, actual.red, 2f / 255f)
        assertEquals("green tint", expected.green, actual.green, 2f / 255f)
        assertEquals("blue tint", expected.blue, actual.blue, 2f / 255f)
    }
}
