package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.ui.common.SheetTerminalMotion
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ImageSearchSuccessMotionCaptureTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun acceptedImageSuccessKeepsPresentationWhileBusyClears() {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val originalUrl = "https://example.com/original.jpg"
        val parentUrl = mutableStateOf(originalUrl)
        val busy = mutableStateOf(true)
        val shown = mutableStateOf(true)
        var completions = 0
        lateinit var terminal: SheetTerminalMotion
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (shown.value) {
                        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        terminal = rememberSheetTerminalMotion(sheet)
                        ImageSearchSheet(
                            initialUrl = parentUrl.value,
                            hasCurrentImage = parentUrl.value.isNotBlank(),
                            header = "Edit image",
                            title = "Test image",
                            seed = "test",
                            urlLabel = "Image URL",
                            removeImageLabel = "Remove test image",
                            applyInFlight = busy.value,
                            onDismiss = {},
                            onApply = {},
                            suppliedSheetState = sheet,
                            suppliedTerminal = terminal,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val folder = File("build/animation-frames/image-search-busy-success/exit").apply { mkdirs() }
        captureScreenRoboImage(File(folder, "source.png").path)
        rule.runOnIdle {
            terminal.closeWhenReady({ !busy.value }) {
                completions++
                shown.value = false
            }
            parentUrl.value = ""
        }
        rule.onNodeWithTag("image_search.url").assertTextContains(originalUrl)
        rule.runOnIdle {
            assertEquals(0, completions)
            busy.value = false
            Snapshot.sendApplyNotifications()
        }
        repeat(64) { frame ->
            rule.mainClock.advanceTimeBy(16L)
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
        rule.runOnIdle {
            assertEquals(1, completions)
            assertEquals(false, shown.value)
        }
    }
}
