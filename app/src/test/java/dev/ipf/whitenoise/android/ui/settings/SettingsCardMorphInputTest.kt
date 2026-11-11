package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cards folding back into their anchor cover the live route, so a tap must not reach the controls under them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h800dp-mdpi")
class SettingsCardMorphInputTest {
    @get:Rule val rule = createComposeRule()

    private object Anchor : SettingsMorphAnchor

    @Test fun foldingCardsHoldBackTapsUntilTheyAreGone() {
        val morph = SettingsCardMorph().apply { anchor = Anchor }
        var routeOpen by mutableStateOf(true)
        var taps = 0
        rule.setContent {
            CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                val colors = SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified)
                Column(Modifier.fillMaxSize().settingsCardMorphHost(morph, colors)) {
                    if (routeOpen) {
                        repeat(2) { Box(Modifier.settingsCardMorph().size(120.dp)) }
                    } else {
                        Box(
                            Modifier
                                .settingsCardMorph(Anchor)
                                .size(120.dp)
                                .testTag("send")
                                .clickable { taps++ },
                        )
                    }
                }
            }
        }
        rule.runOnIdle {
            morph.time = { 0.5f }
            morph.begin(animate = true)
            routeOpen = false
        }
        rule.onNodeWithTag("send").performClick()
        rule.runOnIdle { assertEquals(0, taps) }

        rule.runOnIdle { morph.time = { 1f } }
        rule.onNodeWithTag("send").performClick()
        rule.runOnIdle { assertEquals(1, taps) }
    }
}
