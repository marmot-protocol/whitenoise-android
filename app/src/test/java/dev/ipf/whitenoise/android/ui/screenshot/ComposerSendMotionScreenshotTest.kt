package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotion
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotionHost
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendViewportProjection
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
import kotlin.math.abs

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
        var mountDestination by mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = !rtl, amoled = !rtl) {
                    val motion = remember { ComposerSendMotion().apply { enabled = true } }
                    // This fixture's weighted/static column and landing are measured in one native layout pass.
                    motion.readViewport = {
                        motion.composerCoordinates?.takeIf { it.isAttached }?.let { coordinates ->
                            ComposerSendViewportProjection(
                                coordinates.findRootCoordinates().boundsInWindow(clipBounds = false),
                                coordinates.size.height.toFloat(),
                            )
                        }
                    }
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
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    if (accepted && mountDestination) {
                                        MessageBubbleFrame(
                                            presentation = messageBubblePresentation(deleted = false, mine = true),
                                            highlighted = false,
                                            mine = true,
                                            mentionedSelf = false,
                                            mentionedYouLabel = "Mentioned you",
                                            modifier =
                                                Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(end = 16.dp, bottom = 24.dp)
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
                                }
                                ComposerBar(
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    initialDraft = TextFieldValue(text),
                                    modifier = Modifier,
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
        val source =
            rule.runOnIdle {
                val beforeDisable = checkNotNull(owner.capture(text))
                val layout = owner.sourceLayout
                val surface = owner.sourceSurface
                val editor = owner.sourceEditor
                val viewport = owner.readViewport
                owner.enabled = false
                org.junit.Assert.assertNull("Ineligible mounted composer cannot capture", owner.capture(text))
                owner.enabled = true
                owner.accept(beforeDisable, "late-pre-disable-acceptance", 1)
                org.junit.Assert.assertNull(
                    "An old acceptance cannot resume after eligibility changes",
                    owner.activePresentationId,
                )
                // No text or layout callback occurs between these calls: the same mounted draft must recover.
                org.junit.Assert.assertSame(layout, owner.sourceLayout)
                org.junit.Assert.assertSame(surface, owner.sourceSurface)
                org.junit.Assert.assertSame(editor, owner.sourceEditor)
                org.junit.Assert.assertSame(viewport, owner.readViewport)
                checkNotNull(owner.capture(text))
            }
        rule.onNodeWithContentDescription("Send").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { org.junit.Assert.assertEquals("accepted-1", owner.activePresentationId) }
        // Deliberately withhold the destination for one actual native frame: the captured source must remain painted.
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertPendingSourcePainted(owner, text)
        rule.runOnIdle { mountDestination = true }
        repeat(36) { index ->
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            frames?.let {
                rule.onNodeWithTag("motion-root").captureRoboImage(File(it, "frame-%03d.png".format(index + 1)).path)
            }
            if (index == 8) {
                assertMovingCopyPainted(owner)
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

    private fun assertPendingSourcePainted(
        owner: ComposerSendMotion,
        expectedText: String,
    ) {
        val image = rule.onNodeWithTag("motion-root").captureToImage().asAndroidBitmap()
        val source = checkNotNull(owner.lastRenderedPendingSource)
        org.junit.Assert.assertNull(owner.lastRenderedGeometry)
        org.junit.Assert.assertTrue(owner.hides("accepted-1"))
        org.junit.Assert.assertEquals(expectedText, source.text)
        val background = source.fill.toArgb()
        val origin =
            rule
                .onNodeWithTag("motion-root")
                .fetchSemanticsNode()
                .boundsInWindow.topLeft
        val inkPixels =
            ((source.editor.top - origin.y).toInt() until (source.editor.bottom - origin.y).toInt()).sumOf { y ->
                ((source.editor.left - origin.x).toInt() until (source.editor.right - origin.x).toInt()).count { x ->
                    val actual = image.getPixel(x, y)
                    listOf(0, 8, 16).any { shift ->
                        abs(((actual ushr shift) and 255) - ((background ushr shift) and 255)) > 20
                    }
                }
            }
        org.junit.Assert.assertTrue(
            "Pending accepted source ink must remain in the actual native frame",
            inkPixels > 20,
        )
    }

    private fun assertMovingCopyPainted(owner: ComposerSendMotion) {
        val image = rule.onNodeWithTag("motion-root").captureToImage().asAndroidBitmap()
        val geometry = checkNotNull(owner.lastRenderedGeometry)
        org.junit.Assert.assertTrue(
            "Midpoint must contain an actual progressing overlay",
            geometry.progress in 0.01f..0.99f,
        )
        org.junit.Assert.assertTrue(owner.hides("accepted-1"))
        val origin =
            rule
                .onNodeWithTag("motion-root")
                .fetchSemanticsNode()
                .boundsInWindow.topLeft
        val actual =
            image.getPixel(
                (geometry.bounds.left - origin.x + 4f).toInt(),
                (geometry.bounds.center.y - origin.y).toInt(),
            )
        val expected = geometry.fill.toArgb()
        for (shift in listOf(0, 8, 16)) {
            org.junit.Assert.assertTrue(
                "The native midpoint must paint the measured moving copy",
                abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 2,
            )
        }
    }

    private companion object {
        const val ARABIC_TEXT = "رسالة جاهزة للإرسال.\nزوايا ناعمة ونص واضح."
        const val TEXT = "A message ready to fly.\nSmooth corners, steady text."
    }
}
