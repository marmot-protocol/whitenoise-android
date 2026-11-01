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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
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
    private var allowContentDraw by mutableStateOf(true)
    private var rejectViewport by mutableStateOf(false)
    private var acceptedSends = 0
    private val actualDraws = mutableListOf<dev.ipf.whitenoise.android.ui.conversation.ComposerSendFlightGeometry>()

    /** An accepted text copy shrinks and lifts into its measured bubble, with first, middle and settled baselines. */
    @Test
    fun acceptedTextCopyShrinksIntoMeasuredBubble() = record(suffix = "", rtl = false, text = TEXT)

    /** An accepted right-to-left copy anchors to the right edge in the light theme, with its own baselines. */
    @Test
    fun acceptedArabicTextUsesRightAnchoredLightMotion() = record(suffix = "_light_rtl", rtl = true, text = ARABIC_TEXT)

    /** A flight that reaches its final geometry numerically stays alive until the canvas paints the handoff. */
    @Test
    fun numericalFinalFrameCannotRetireBeforeTheActualCanvasPaints() {
        record("_paint_gate", false, TEXT, lateAdmission = false) { owner ->
            rule.runOnIdle { allowContentDraw = false }
            repeat(12) {
                if (owner.presentation("accepted-1").geometry?.settled != true) {
                    rule.mainClock.advanceTimeByFrame()
                    rule.waitForIdle()
                }
            }
            rule.runOnIdle {
                org.junit.Assert.assertNotNull("Numeric completion alone retains the actual owner", owner.flight)
                org.junit.Assert.assertTrue(checkNotNull(owner.presentation("accepted-1").geometry).settled)
                org.junit.Assert.assertTrue(checkNotNull(owner.lastRenderedGeometry).progress < 1f)
                allowContentDraw = true
            }
            rule.onNodeWithTag("motion-root").captureToImage()
            org.junit.Assert.assertTrue(checkNotNull(owner.lastRenderedGeometry).settled)
            rule.onNodeWithTag("motion-root").captureRoboImage("src/test/snapshots/send_actual_final_paint.png")
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            org.junit.Assert.assertNull("Only the actual painted handoff retires", owner.flight)
        }
    }

    /**
     * A rejected viewport falls back to the native bubble, and restoring it cannot revive the same flight
     * without another clock frame.
     */
    @Test
    fun actualNativeFallbackCannotResurrectWithoutAnotherClockFrame() {
        record("_native_fallback", false, TEXT) { owner ->
            val time = rule.mainClock.currentTime
            rule.runOnIdle { rejectViewport = true }
            assertNativeDestinationInk(TEXT, "invalid")
            org.junit.Assert.assertNull(owner.lastRenderedGeometry)
            org.junit.Assert.assertFalse(owner.presentation("accepted-1").coversNative)
            rule.runOnIdle { rejectViewport = false }
            assertNativeDestinationInk(TEXT, "restored")
            org.junit.Assert.assertNull(owner.lastRenderedGeometry)
            org.junit.Assert.assertFalse("The same flight cannot resume", owner.presentation("accepted-1").coversNative)
            org.junit.Assert.assertEquals("Neither actual draw advances the clock", time, rule.mainClock.currentTime)
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            org.junit.Assert.assertNull("Timing retires the Canvas fallback decision", owner.flight)
            assertNativeDestinationInk(TEXT, "retired")
        }
    }

    /** Typing a new draft mid-flight retires the moving copy, keeping both the sent bubble text and the new draft. */
    @Test
    fun nextDraftDuringActualMovingCopyRetiresWithoutLosingEitherText() {
        val nextDraft = "A distinct next draft stays editable."
        record("_next_draft", false, TEXT) { owner ->
            org.junit.Assert.assertTrue(checkNotNull(owner.lastRenderedGeometry).progress in 0.01f..0.99f)
            rule.onNode(hasSetTextAction()).performTextReplacement(nextDraft)
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            org.junit.Assert.assertNull("New input retires the old moving copy", owner.flight)
            assertNativeDestinationInk(TEXT, "next_draft")
            rule.onNodeWithText(nextDraft).assertIsDisplayed()
            org.junit.Assert.assertEquals("Editing must not publish again", 1, acceptedSends)
        }
        rule.onNodeWithText(nextDraft).assertIsDisplayed()
    }

    /**
     * Mounts a composer and destination over one native frame clock, sends [text], and records the first,
     * middle and settled frames. [lateAdmission] adds a frame before the destination mounts, and [onMiddle]
     * runs at the midpoint.
     */
    @Suppress("LongMethod") // One native fixture binds setup and first/middle/settled capture points.
    private fun record(
        suffix: String,
        rtl: Boolean,
        text: String,
        lateAdmission: Boolean = true,
        onMiddle: (ComposerSendMotion) -> Unit = {},
    ) {
        rule.mainClock.autoAdvance = false
        lateinit var owner: ComposerSendMotion
        var mountDestination by mutableStateOf(false)
        var unrelatedRowSize by mutableStateOf(1)
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                dev.ipf.whitenoise.android.ui.conversation.LocalComposerSendGeometryObserver provides { geometry ->
                    actualDraws += geometry
                },
            ) {
                WhiteNoiseTheme(darkTheme = !rtl, amoled = !rtl) {
                    val motion = remember { ComposerSendMotion().apply { enabled = true } }
                    // This fixture's weighted/static column and landing are measured in one native layout pass.
                    motion.readViewport = {
                        motion.composerCoordinates?.takeIf { it.isAttached && !rejectViewport }?.let { coordinates ->
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
                            .testTag("motion-root")
                            .drawWithContent { if (allowContentDraw) drawContent() },
                    ) {
                        ComposerSendMotionHost(motion) {
                            MaterialTheme(shapes = MaterialTheme.shapes.copy(large = CutCornerShape(4.dp))) {
                                Box(
                                    Modifier
                                        .size(unrelatedRowSize.dp)
                                        .composerSendDestination(
                                            "unrelated-row",
                                            motion,
                                            messageBubblePresentation(deleted = false, mine = true),
                                            true,
                                        ),
                                )
                            }
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
                                        acceptedSends += 1
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
        rule.runOnIdle { unrelatedRowSize = 2 }
        // A non-rounded unrelated row is remeasured while the accepted owner is pending.
        rule.onNodeWithTag("motion-root").captureToImage()
        rule.runOnIdle {
            org.junit.Assert.assertEquals(
                "Unrelated shape cannot clear the active copy",
                "accepted-1",
                owner.activePresentationId,
            )
        }
        // Publication remains inside the existing 80 ms admission allowance, without advancing its tween.
        if (lateAdmission) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertPendingSourcePainted(owner, text)
        }
        rule.runOnIdle { mountDestination = true }
        recordFrames(owner, suffix, frames, onMiddle, source)
        rule.onNodeWithTag("motion-root").captureRoboImage("src/test/snapshots/composer_send_motion_settled$suffix.png")
        org.junit.Assert.assertEquals("One actual send acceptance", 1, acceptedSends)
        rule.runOnIdle {
            org.junit.Assert.assertNull(owner.activePresentationId)
            owner.dispose()
            owner.enabled = true
            owner.accept(source, "late-pre-lock-acceptance", 1)
            org.junit.Assert.assertNull("Retired capture must not animate after resume", owner.activePresentationId)
            owner.accept(null, "no-endpoint", 1)
            org.junit.Assert.assertFalse(owner.presentation("no-endpoint").coversNative)
        }
    }

    /** Advances 36 frames capturing each, checks the midpoint, then asserts the first drawn frame is on the source. */
    private fun recordFrames(
        owner: ComposerSendMotion,
        suffix: String,
        frames: File?,
        onMiddle: (ComposerSendMotion) -> Unit,
        source: dev.ipf.whitenoise.android.ui.conversation.ComposerSendSource,
    ) {
        repeat(36) { index ->
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            rule.onNodeWithTag("motion-root").captureToImage()
            frames?.let {
                rule.onNodeWithTag("motion-root").captureRoboImage(File(it, "frame-%03d.png".format(index + 1)).path)
            }
            if (index == 8) {
                assertMovingCopyPainted(owner)
                rule
                    .onNodeWithTag("motion-root")
                    .captureRoboImage("src/test/snapshots/composer_send_motion_mid$suffix.png")
                onMiddle(owner)
            }
        }
        val initial = checkNotNull(actualDraws.firstOrNull())
        org.junit.Assert.assertEquals(
            "The first actual Canvas geometry retains the source patch",
            0f,
            initial.progress,
            0f,
        )
        org.junit.Assert.assertEquals(source.editor, initial.bounds)
    }

    /** Asserts the canonical bubble paragraph has visible ink in the native frame, and snapshots it as [phase]. */
    private fun assertNativeDestinationInk(
        text: String,
        phase: String,
    ) {
        val image = rule.onNodeWithTag("motion-root").captureToImage().asAndroidBitmap()
        rule.onNodeWithTag("motion-root").captureRoboImage("src/test/snapshots/send_native_fallback_$phase.png")
        val origin =
            rule
                .onNodeWithTag("motion-root")
                .fetchSemanticsNode()
                .boundsInWindow.topLeft
        val bubble =
            rule
                .onNodeWithTag("composer.send.destination.accepted-1", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInWindow
        val ink = rule.onNodeWithText(text).fetchSemanticsNode().boundsInWindow
        val fill = image.getPixel((bubble.left - origin.x + 4f).toInt(), (bubble.center.y - origin.y).toInt())
        val visibleInk =
            ((ink.top - origin.y).toInt() until (ink.bottom - origin.y).toInt()).sumOf { y ->
                ((ink.left - origin.x).toInt() until (ink.right - origin.x).toInt()).count { x ->
                    val color = image.getPixel(x, y)
                    listOf(0, 8, 16).any { shift ->
                        abs(((color ushr shift) and 255) - ((fill ushr shift) and 255)) > 20
                    }
                }
            }
        org.junit.Assert.assertTrue("Canonical paragraph ink is actually visible without an overlay", visibleInk > 20)
    }

    /** Asserts the accepted source, still withheld from its destination, paints its ink in the native frame. */
    private fun assertPendingSourcePainted(
        owner: ComposerSendMotion,
        expectedText: String,
    ) {
        val image = rule.onNodeWithTag("motion-root").captureToImage().asAndroidBitmap()
        val source = checkNotNull(owner.lastRenderedPendingSource)
        org.junit.Assert.assertNull(owner.lastRenderedGeometry)
        org.junit.Assert.assertTrue(owner.presentation("accepted-1").coversNative)
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

    /** Asserts the midpoint frame paints the moving copy's measured fill at an in-flight progress. */
    private fun assertMovingCopyPainted(owner: ComposerSendMotion) {
        val image = rule.onNodeWithTag("motion-root").captureToImage().asAndroidBitmap()
        val geometry = checkNotNull(owner.lastRenderedGeometry)
        org.junit.Assert.assertTrue(
            "Midpoint must contain an actual progressing overlay",
            geometry.progress in 0.01f..0.99f,
        )
        org.junit.Assert.assertTrue(owner.presentation("accepted-1").coversNative)
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
