package dev.ipf.whitenoise.android.ui.share

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Accepted staging retires its request even when the active account changes around close acceptance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SharePickerMotionCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureExternalSharePicker() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val state = appStateWithDirectChats(GROUP_A to PEER_A, profiles = mutableMapOf(PEER_A to profile("Alice")))
        val shown = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        if (shown.value) {
                            ShareChatPickerFullScreen(
                                appState = state,
                                requestId = "synthetic-share",
                                payload =
                                    SharePayload(
                                        text = "Shared text",
                                        streamUris = emptyList(),
                                        intentMimeType = "text/plain",
                                    ),
                                onDismiss = { shown.value = false },
                                onStage = { _, _ -> true },
                                controllerBinder = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        capture("external-share/forward") { rule.runOnIdle { shown.value = true } }
        capture("external-share/reverse") { rule.onNodeWithContentDescription("Close").performClick() }
    }

    private fun capture(
        name: String,
        action: () -> Unit,
    ) {
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        rule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(32) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
