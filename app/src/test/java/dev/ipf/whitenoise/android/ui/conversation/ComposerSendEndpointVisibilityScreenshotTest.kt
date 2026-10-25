package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ComposerSendEndpointVisibilityScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** A large outer pill cannot make clipped editor ink eligible for a flight. */
    @Test
    @Suppress("LongMethod") // One native fixture covers the clipped and visible states of the same endpoint.
    fun clippedEditorFallsBackAndAStaleNullCapturePreservesTheNewerFlight() {
        var clipped by mutableStateOf(true)
        lateinit var motion: ComposerSendMotion
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val owner = remember { ComposerSendMotion().apply { enabled = true } }
                motion = owner
                Surface(
                    color = Color.Black,
                    modifier =
                        Modifier
                            .size(300.dp, 200.dp)
                            .testTag("clipped-composer")
                            .onGloballyPositioned { owner.sourceSurface = it },
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Clipped editor", color = Color.White)
                        Box(Modifier.height(if (clipped) 40.dp else 160.dp).clipToBounds()) {
                            BasicTextField(
                                value = SIX_LINES,
                                onValueChange = {},
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                                modifier =
                                    Modifier
                                        .height(160.dp)
                                        .onGloballyPositioned { owner.sourceEditor = it },
                                onTextLayout = { owner.sourceLayout = it },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val layout = checkNotNull(motion.sourceLayout)
            val surface = checkNotNull(motion.sourceSurface).boundsInWindow()
            val editor = checkNotNull(motion.sourceEditor).boundsInWindow()
            assertTrue("The six-line layout fits the outer surface", layout.size.height <= surface.height)
            assertTrue("The actual editor viewport clips several lines", layout.size.height > editor.height)
            assertNull("Hidden composer lines must never become flying text", motion.capture(SIX_LINES))
        }
        rule.onNodeWithTag("clipped-composer").captureRoboImage(
            "src/test/snapshots/composer_send_clipped_editor_fallback.png",
        )
        rule.runOnIdle { clipped = false }
        rule.waitForIdle()
        rule.runOnIdle {
            val visibleSource = motion.capture(SIX_LINES)
            assertNotNull("The same layout is eligible once all its ink is visible", visibleSource)
            motion.accept(visibleSource, "newer-accepted-row", 1)
            motion.accept(null, "older-null-capture", 0)
            assertEquals(
                "An older absent capture cannot cancel a newer flight",
                "newer-accepted-row",
                motion.activePresentationId,
            )
            motion.dispose()
        }
    }

    private companion object {
        const val SIX_LINES =
            "First visible line\nSecond visible line\nThird hidden line\n" +
                "Fourth hidden line\nFifth hidden line\nSixth hidden line"
    }
}
