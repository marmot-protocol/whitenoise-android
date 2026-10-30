package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiGlyph
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.conversation.reactions.localReactionOverlay
import dev.ipf.whitenoise.android.ui.conversation.reactions.reactionSourceBounds
import dev.ipf.whitenoise.android.ui.conversation.reactions.rememberLocalReactionMotion
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native draw-layer proposal; the fixture simulates local intent acceptance, not network success. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class LocalReactionMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** An accepted add flies from the selected control to its chip on the AMOLED dark theme. */
    @Test fun acceptedAddAmoled() = record("amoled", false, true)

    /** An accepted add flies from the selected control to its chip under RTL on the light theme. */
    @Test fun acceptedAddRtlLight() = record("rtl_light", true, true)

    /** An accepted fifth reaction type, added after four existing chips, lands in the row's overflow. */
    @Test fun acceptedFifthTypeLandsInOverflow() = record("overflow", false, true)

    /** With motion disabled, the accepted chip appears at once and no flight pixels are drawn. */
    @Test fun disabledMotionIsImmediate() = record("disabled", false, false)

    /** Turning motion off mid-flight drops the decorative flight pixels and settles on the chip. */
    @Test fun disablingMidFlightDropsDecorativePixels() = record("interrupted", false, true)

    /**
     * Renders the reaction fixture, accepts a thumbs-up, and captures its first, mid-flight and settled frames,
     * optionally dumping every frame when `ANIMATION_CAPTURE_FRAMES` is set.
     */
    @Suppress("LongMethod") // One native fixture setup with fixed first/mid/settled capture points.
    private fun record(
        name: String,
        rtl: Boolean,
        motionEnabled: Boolean,
    ) {
        var accepted by mutableStateOf(false)
        var animations by mutableStateOf(motionEnabled)
        var source = Rect.Zero
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = !rtl, amoled = !rtl) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) {
                    val motion = rememberLocalReactionMotion(name, enabled = animations)
                    Box(Modifier.testTag("reaction-motion-root")) {
                        Box(
                            Modifier
                                .width(360.dp)
                                .height(640.dp)
                                .background(MaterialTheme.colorScheme.background)
                                .localReactionOverlay(motion),
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "Reaction · selected control to chip",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Native Compose fixture · simulated local intent",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(90.dp))
                                if (!accepted) {
                                    Row {
                                        TextButton(onClick = {
                                            motion.choose("👍", source)
                                            accepted = true
                                            motion.accept("👍", true, motion.takeOrigin("👍"))
                                        }, modifier = Modifier.testTag("choose-reaction")) {
                                            EmojiGlyph(
                                                "👍",
                                                size = 28.dp,
                                                modifier = Modifier.reactionSourceBounds { source = it },
                                            )
                                        }
                                        TextButton(onClick = {}) { Text("❤") }
                                    }
                                } else {
                                    Spacer(Modifier.height(48.dp))
                                }
                                Spacer(Modifier.height(180.dp))
                                Text(
                                    "See you at the park!",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val existingTallies =
                                    if (name == "overflow") {
                                        listOf("❤", "🎉", "🔥", "😂").map { ReactionTally(it, 2, false) }
                                    } else {
                                        emptyList()
                                    }
                                ReactionPillRow(
                                    tallies =
                                        existingTallies +
                                            (if (accepted) listOf(ReactionTally("👍", 1, true)) else emptyList()),
                                    enabled = true,
                                    onOpenDetails = {},
                                    localMotion = motion,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val folder = File("build/local-reaction-motion/$name").apply { mkdirs() }
        if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
            captureReactionFrame(File(folder, "frame-000.png").path)
        }
        captureReactionFrame("src/test/snapshots/local_reaction_motion_${name}_first.png")
        rule.onNodeWithTag("choose-reaction").performClick()
        repeat(24) { index ->
            if (name == "interrupted" && index == 6) rule.runOnIdle { animations = false }
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
                captureReactionFrame(File(folder, "frame-%03d.png".format(index + 1)).path)
            }
            if (index == 6) captureReactionFrame("src/test/snapshots/local_reaction_motion_${name}_mid.png")
        }
        captureReactionFrame("src/test/snapshots/local_reaction_motion_${name}_settled.png")
    }

    /** Captures the fixture root to the Roborazzi PNG at [path]. */
    private fun captureReactionFrame(path: String) {
        rule.onNodeWithTag("reaction-motion-root").captureRoboImage(path)
    }
}
