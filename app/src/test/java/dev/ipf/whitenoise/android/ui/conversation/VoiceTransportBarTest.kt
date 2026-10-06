package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Persistent voice controls own layout space, retain separate actions and remain legible at large RTL text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class VoiceTransportBarTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var playing: Boolean? = null
    private var stops = 0
    private var opens = 0
    private var rowOpens = 0

    @Test fun light() = renderAndCapture("light")

    @Test fun dark() = renderAndCapture("dark", dark = true)

    @Test fun amoled() = renderAndCapture("amoled", dark = true, amoled = true)

    @Test fun largeRtl() = renderAndCapture("large_rtl", rtl = true)

    @Test fun paused() = renderAndCapture("paused", paused = true)

    /** Source and transport buttons never trigger each other or overlap destination content. */
    @Test fun shellControlsAndFirstDestinationRowRemainIndependent() {
        render()
        val strip = rule.onNodeWithTag("voice-transport").getUnclippedBoundsInRoot()
        val row = rule.onNodeWithTag("first-row")
        assertTrue(strip.bottom <= row.getUnclippedBoundsInRoot().top)
        row.assertIsDisplayed().performClick()
        rule.onNodeWithText("Maya and the planning group").performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.voice_message_pause)).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.tts_playback_action_stop)).performClick()
        assertEquals(1, rowOpens)
        assertEquals(1, opens)
        assertEquals(false, playing)
        assertEquals(1, stops)
    }

    private fun renderAndCapture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        paused: Boolean = false,
    ) {
        render(dark, amoled, rtl, paused)
        rule.onNodeWithTag("voice-transport").captureRoboImage("src/test/snapshots/shell_voice_$name.png")
    }

    private fun render(
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        paused: Boolean = false,
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    Column(Modifier.width(360.dp).testTag("voice-host")) {
                        ShellTransientNoticeLayout(
                            notice = null,
                            persistentTopContent = {
                                VoiceTransportBarContent(
                                    state =
                                        VoicePlaybackController.PlaybackState(
                                            key = "voice",
                                            isPlaying = !paused,
                                            positionMs = 12_000,
                                            durationMs = 45_000,
                                            ready = true,
                                            sessionId = 1,
                                            source =
                                                VoicePlaybackSource(
                                                    "personal",
                                                    "chat",
                                                    "message",
                                                    "Maya and the planning group",
                                                ),
                                        ),
                                    onPlayingChange = { playing = it },
                                    onStop = { stops++ },
                                    onBodyClick = { opens++ },
                                )
                            },
                        ) {
                            Text("First destination row", Modifier.testTag("first-row").clickable { rowOpens++ })
                        }
                    }
                }
            }
        }
    }
}
