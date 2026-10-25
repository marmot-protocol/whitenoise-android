package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotion
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotionHost
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.composerSendDestination
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ComposerSendMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun acceptedTextCopyShrinksIntoMeasuredBubble() = record(suffix = "", rtl = false, text = TEXT)

    @Test
    fun acceptedArabicTextUsesRightAnchoredLightMotion() = record(suffix = "_light_rtl", rtl = true, text = ARABIC_TEXT)

    @Suppress("LongMethod") // One native fixture binds setup and first/middle/settled capture points.
    private fun record(suffix: String, rtl: Boolean, text: String) {
        rule.mainClock.autoAdvance = false
        lateinit var owner: ComposerSendMotion
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = !rtl, amoled = !rtl) {
                    val motion = remember { ComposerSendMotion().apply { enabled = true } }
                    owner = motion
                    var accepted by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .size(360.dp, 640.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("motion-root"),
                    ) {
                        ComposerSendMotionHost(motion) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Copy, shrink and lift", color = MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Native Compose · simulated acceptance",
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (accepted) {
                                MessageBubbleFrame(
                                    presentation = messageBubblePresentation(deleted = false, mine = true),
                                    highlighted = false,
                                    mine = true,
                                    mentionedSelf = false,
                                    mentionedYouLabel = "Mentioned you",
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(end = 16.dp, bottom = 200.dp)
                                            .composerSendDestination(
                                                "accepted-1",
                                                motion,
                                                messageBubblePresentation(deleted = false, mine = true),
                                                true,
                                            ),
                                ) {
                                    Text(
                                        text,
                                        modifier =
                                            Modifier.onGloballyPositioned {
                                                motion.destinationText("accepted-1", it)
                                            },
                                        onTextLayout = { motion.destinationLayout("accepted-1", it) },
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text("12:34", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                initialDraft = TextFieldValue(text),
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onSend = { _, _ -> error("Typed acceptance callback expected") },
                                onSendWithPresentation = { _, onAccepted ->
                                    accepted = true
                                    onAccepted("accepted-1")
                                },
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val frames =
            if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
                File("build/composer-send-production-frames$suffix").apply { mkdirs() }
            } else {
                null
            }
        rule.onNodeWithTag("motion-root").captureRoboImage("src/test/snapshots/composer_send_motion_first$suffix.png")
        frames?.let { rule.onNodeWithTag("motion-root").captureRoboImage(File(it, "frame-000.png").path) }
        val source = rule.runOnIdle { checkNotNull(owner.capture(text)) }
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals("accepted-1", owner.activePresentationId) }
        repeat(36) { index ->
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            frames?.let {
                rule.onNodeWithTag("motion-root").captureRoboImage(File(it, "frame-%03d.png".format(index + 1)).path)
            }
            if (index == 8) {
                rule
                    .onNodeWithTag("motion-root")
                    .captureRoboImage("src/test/snapshots/composer_send_motion_mid$suffix.png")
            }
        }
        rule.onNodeWithTag("motion-root").captureRoboImage("src/test/snapshots/composer_send_motion_settled$suffix.png")
        rule.runOnIdle {
            org.junit.Assert.assertNull(owner.activePresentationId)
            owner.dispose()
            owner.enabled = true
            owner.accept(source, "late-pre-lock-acceptance", 1)
            org.junit.Assert.assertNull("Retired capture must not animate after resume", owner.activePresentationId)
            owner.accept(null, "no-endpoint", 1)
            org.junit.Assert.assertFalse(owner.hides("no-endpoint"))
        }
    }

    private companion object {
        const val ARABIC_TEXT = "رسالة جاهزة للإرسال.\nزوايا ناعمة ونص واضح."
        const val TEXT = "A message ready to fly.\nSmooth corners, steady text."
    }
}
