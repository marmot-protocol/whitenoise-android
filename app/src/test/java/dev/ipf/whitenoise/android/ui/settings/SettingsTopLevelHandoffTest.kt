package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live top-level controls hide while a leg draws them, even when nothing else redraws them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsTopLevelHandoffTest {
    @get:Rule val rule = createComposeRule()

    @Test fun controlsDrawnBeforeTheirLegBeganHideWhenItStarts() {
        val morph = SettingsCardMorph(fullScreen = true)
        rule.setContent {
            CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                Row(Modifier.background(Color.Black)) {
                    Box(Modifier.size(40.dp).settingsAvatarFlightSource(0.dp).background(Color.Green))
                    Box(Modifier.size(40.dp).settingsPenSource(true, 16.dp, Color.Red, Color.Red).background(Color.Red))
                }
            }
        }
        rule.waitForIdle()
        assertEquals("both controls draw at rest", listOf(Color.Green, Color.Red), controls(frame()))
        // A leg starting later must hide the already-drawn controls without another redraw.
        rule.runOnIdle {
            morph.topLevel.hideAvatar = { true }
            morph.topLevel.hidePen = { true }
        }
        assertEquals("the leg draws them instead", listOf(Color.Black, Color.Black), controls(frame()))
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun controls(frame: Bitmap) = listOf(20, 60).map { Color(frame.getPixel(it, 20)) }
}
