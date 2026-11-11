package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A route holding the secure flag, such as Account keys with the key revealed, must leave no card picture behind. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class SettingsCardMorphSecureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun leavingASecureRouteKeepsBareShapes() {
        assertEquals(0, keptPictures(secure = true))
    }

    @Test fun leavingAnOpenRouteKeepsItsPictures() {
        assertEquals(2, keptPictures(secure = false))
    }

    private fun keptPictures(secure: Boolean): Int {
        val morph = SettingsCardMorph()
        rule.setContent {
            CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                if (secure) WindowSecureFlag()
                val colors = SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified)
                Column(Modifier.fillMaxSize().settingsCardMorphHost(morph, colors)) {
                    repeat(2) { Box(Modifier.settingsCardMorph().size(120.dp).background(Color.Red)) }
                }
            }
        }
        // Drawing a frame is what records card pictures.
        rule.onRoot().captureToImage()
        // The next route begins while this one, and its flag, are still composed.
        rule.runOnIdle { morph.begin(animate = true) }
        return morph.keptPictures
    }
}
