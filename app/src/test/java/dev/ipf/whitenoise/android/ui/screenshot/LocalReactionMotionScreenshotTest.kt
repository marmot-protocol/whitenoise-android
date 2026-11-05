package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.conversation.messages.FOCUSED_OVERLAY_FRAME_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageAction
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedMessageActions
import dev.ipf.whitenoise.android.ui.conversation.messages.FocusedTextMessagePreview
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_REACTION_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReactionSummary
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.reactions.LOCAL_REACTION_MOTION_MILLIS
import dev.ipf.whitenoise.android.ui.conversation.reactions.LocalReactionMotion
import dev.ipf.whitenoise.android.ui.conversation.reactions.REACTION_PILL_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.conversation.reactions.localReactionOverlay
import dev.ipf.whitenoise.android.ui.conversation.reactions.rememberLocalReactionMotion
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

/** Production message preview, action rail and badge; acceptance is a synthetic optimistic local intent. */
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

    /** Losing a target restores the canonical badge immediately, including its existing pulse. */
    @Test
    fun missingTargetRestoresCanonicalBadge() {
        lateinit var motion: LocalReactionMotion
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    motion = rememberLocalReactionMotion("clipped-glyph", enabled = true)
                    Box(Modifier.testTag("clipped-reaction-root")) {
                        Column(
                            Modifier
                                .width(360.dp)
                                .height(640.dp)
                                .background(MaterialTheme.colorScheme.background)
                                .localReactionOverlay(motion)
                                .padding(16.dp),
                        ) {
                            Spacer(Modifier.height(244.dp))
                            // The production row is 48dp; this real ancestor clip cuts its
                            // glyph's bottom while leaving most of the canonical badge visible.
                            Box(Modifier.height(26.dp).clipToBounds()) {
                                ReactionPillRow(
                                    tallies = listOf(ReactionTally("👍", 1, true)),
                                    enabled = false,
                                    onOpenDetails = {},
                                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true),
                                    localMotion = motion,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        flushQueuedComposition(64)
        rule.runOnUiThread {
            motion.accept("👍", true, Rect(40f, 120f, 68f, 148f))
            Snapshot.sendApplyNotifications()
        }
        flushQueuedComposition()
        val missing = captureReactionBitmap("clipped-reaction-root")
        rule.runOnIdle {
            assertTrue("the composed selected badge must still have an active bounded effect", motion.effect != null)
            assertNull("the real ancestor clip must withhold fully visible glyph geometry", motion.targets["👍"])
            motion.viewport = Rect.Zero
        }
        val canonical = captureReactionBitmap("clipped-reaction-root")
        assertArrayEquals(
            "missing geometry must retain the same-phase canonical clipped badge",
            cropPixels(canonical, IntRect(0, 0, 360, 640)),
            cropPixels(missing, IntRect(0, 0, 360, 640)),
        )
        assertTrue(
            "the fallback must retain visible native emoji pixels",
            yellowStrength(
                cropPixels(
                    missing,
                    IntRect(
                        0,
                        0,
                        360,
                        640,
                    ),
                ),
            ) > 0,
        )
        rule.onNodeWithTag("conversation.reaction.motion.active").assertDoesNotExist()
        captureReactionFrame("src/test/snapshots/local_reaction_motion_missing_target.png", "clipped-reaction-root")
        flushQueuedComposition(160)
        rule.runOnIdle { assertNull("missing geometry must settle after the bounded wait", motion.effect) }
    }

    /** A clipped outer frame restores canonical chrome while the glyph remains eligible. */
    @Test fun partialChromeViewportRestoresCanonicalBadge() = record("partial_chrome", false, true)

    /** A counted badge lands on its emoji, rather than the center between emoji and count. */
    @Test fun existingCountedBadgeTargetsItsGlyph() = record("counted", false, true)

    /** Outgoing messages keep the badge on their opposite bubble edge. */
    @Test fun outgoingBadgeTargetsItsGlyph() = record("outgoing", false, true)

    /** Larger app text and RTL retain the measured source and destination. */
    @Test fun largeFontRtlTargetsItsGlyph() = record("large_rtl", true, true)

    /** Bundled artwork uses the same decoded crop at both ends of the flight. */
    @Test fun bundledArtworkLandsInBadge() = record("bundled", false, true, ":marmot:")

    /** Rectangular custom artwork retains its aspect ratio while traveling to the badge. */
    @Test fun rectangularArtworkLandsInBadge() = record("rectangular", false, true, ":flag:")

    /** A shortcode with no decoded artwork keeps existing bounded chip feedback. */
    @Test fun missingArtworkDoesNotFlyLiteralText() = record("missing_art", false, true, ":missing:")

    /** Geometry arriving after the bounded wait must not revive a flight partway along its path. */
    @Test
    @Suppress("LongMethod") // One real native bubble with a deliberately delayed badge composition.
    fun lateGeometryKeepsTheCanonicalBadgeWithoutStartingAFlight() {
        lateinit var motion: LocalReactionMotion
        var showBadge by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    motion = rememberLocalReactionMotion("late-geometry", enabled = true)
                    Box(Modifier.testTag("late-reaction-root")) {
                        Column(
                            Modifier
                                .width(360.dp)
                                .height(640.dp)
                                .background(MaterialTheme.colorScheme.background)
                                .localReactionOverlay(motion)
                                .padding(16.dp),
                        ) {
                            Spacer(Modifier.height(244.dp))
                            Column(Modifier.widthIn(max = 284.dp)) {
                                FocusedTextMessagePreview(
                                    presentation = messageBubblePresentation(deleted = false, mine = false),
                                    mine = false,
                                    text = "See you at the park!",
                                    document = null,
                                    time = "12:34",
                                    status = MessageStatus.Received,
                                    showStatus = false,
                                )
                                if (showBadge) {
                                    MessageReactionSummary(
                                        tallies = listOf(ReactionTally("👍", 1, true)),
                                        mine = false,
                                        onClick = {},
                                        localMotion = motion,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            motion.accept("👍", true, Rect(40f, 120f, 68f, 148f))
            Snapshot.sendApplyNotifications()
        }
        flushQueuedComposition(160)
        rule.runOnIdle {
            assertNull("an unmeasured destination must settle after the bounded wait", motion.effect)
            showBadge = true
        }
        flushQueuedComposition(320)
        rule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").assertExists()
        rule.onNodeWithTag("conversation.reaction.motion.active").assertDoesNotExist()
        rule.runOnIdle {
            assertNull("late geometry must not restart the settled effect", motion.effect)
            assertTrue(motion.targets.isEmpty())
        }
        captureReactionFrame("src/test/snapshots/local_reaction_motion_late_geometry_settled.png", "late-reaction-root")
    }

    /** With motion disabled, the accepted chip appears at once and no flight pixels are drawn. */
    @Test fun disabledMotionIsImmediate() = record("disabled", false, false)

    /** Turning motion off mid-flight drops the decorative flight pixels and settles on the chip. */
    @Test fun disablingMidFlightDropsDecorativePixels() = record("interrupted", false, true)

    /**
     * Renders the reaction fixture, accepts a thumbs-up, and captures its first, mid-flight and settled frames,
     * optionally dumping every frame when `ANIMATION_CAPTURE_FRAMES` is set.
     * One production-component capture matrix shares these acceptance checks.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun record(
        name: String,
        rtl: Boolean,
        motionEnabled: Boolean,
        emoji: String = "👍",
    ) {
        val mine = name == "outgoing"
        lateinit var motion: LocalReactionMotion
        var accepted by mutableStateOf(false)
        var animations by mutableStateOf(motionEnabled)
        var bubbleBounds by mutableStateOf(IntRect(16, 260, 300, 330))
        val customBitmap =
            Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.rgb(255, 160, 0))
                Canvas(this).drawRect(
                    0f,
                    0f,
                    24f,
                    24f,
                    android.graphics.Paint().apply {
                        color =
                            AndroidColor.rgb(
                                35,
                                160,
                                255,
                            )
                    },
                )
            }
        val custom = CustomEmojiSet(listOf(CustomEmoji(":flag:", File("flag.png"), customBitmap.asImageBitmap())))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = !rtl, amoled = !rtl, fontScale = if (name == "large_rtl") 1.6f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalCustomEmoji provides custom,
                ) {
                    motion = rememberLocalReactionMotion(name, enabled = animations)
                    val preview: @androidx.compose.runtime.Composable () -> Unit = {
                        FocusedTextMessagePreview(
                            presentation = messageBubblePresentation(deleted = false, mine = mine),
                            mine = mine,
                            text = "See you at the park!",
                            document = null,
                            time = "12:34",
                            status = MessageStatus.Received,
                            showStatus = false,
                        )
                    }
                    Box(Modifier.testTag("reaction-motion-root")) {
                        Box(
                            Modifier
                                .width(360.dp)
                                .height(640.dp)
                                .background(MaterialTheme.colorScheme.background)
                                .localReactionOverlay(motion),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                            ) {
                                Spacer(Modifier.height(244.dp))
                                Column(Modifier.widthIn(max = 284.dp)) {
                                    Box(
                                        Modifier.widthIn(max = 284.dp).onGloballyPositioned {
                                            val bounds = it.boundsInWindow()
                                            bubbleBounds =
                                                IntRect(
                                                    bounds.left.toInt(),
                                                    bounds.top.toInt(),
                                                    bounds.right.toInt(),
                                                    bounds.bottom.toInt(),
                                                )
                                        },
                                    ) { preview() }
                                    val existing =
                                        if (name == "counted") {
                                            listOf(ReactionTally(emoji, 3, false))
                                        } else if (name == "overflow") {
                                            listOf("❤", "🎉", "🔥", "😂").map { ReactionTally(it, 2, false) }
                                        } else {
                                            emptyList()
                                        }
                                    MessageReactionSummary(
                                        tallies =
                                            if (name == "counted") {
                                                listOf(ReactionTally(emoji, if (accepted) 4 else 3, accepted))
                                            } else {
                                                existing +
                                                    if (accepted) {
                                                        listOf(
                                                            ReactionTally(
                                                                emoji,
                                                                1,
                                                                true,
                                                            ),
                                                        )
                                                    } else {
                                                        emptyList()
                                                    }
                                            },
                                        mine = mine,
                                        onClick = {},
                                        localMotion = motion,
                                    )
                                }
                            }
                            if (!accepted) {
                                FocusedMessageActions(
                                    sourceBounds = bubbleBounds,
                                    touchY = bubbleBounds.center.y.toFloat(),
                                    mine = mine,
                                    actions =
                                        listOf(
                                            FocusedMessageAction("Reply", null, true, false, {
                                                Icon(painterResource(R.drawable.ic_reply), contentDescription = null)
                                            }, {}),
                                            FocusedMessageAction("Info", null, true, false, {
                                                Icon(painterResource(R.drawable.ic_info), contentDescription = null)
                                            }, {}),
                                        ),
                                    quickReactions = listOf(emoji, "❤️", "😂", "😮", "😢", "👏"),
                                    canReact = true,
                                    selectedReactions = emptySet(),
                                    previewDescription = "See you at the park!",
                                    previewReady = true,
                                    preview = preview,
                                    onReactionOrigin = motion::choose,
                                    onReact = { selected ->
                                        accepted = true
                                        motion.accept(selected, true, motion.takeOrigin(selected))
                                    },
                                    onMoreReactions = {},
                                    onDismiss = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        // Popup placement and the lifted-stack snap complete before measuring/clicking the real rail.
        flushQueuedComposition(64)
        rule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).captureToImage()
        flushQueuedComposition()
        val folder = File("build/local-reaction-motion/$name").apply { mkdirs() }
        if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
            captureReactionFrame(File(folder, "frame-000.png").path, FOCUSED_OVERLAY_FRAME_TEST_TAG)
        }
        val destinationBefore = captureReactionBitmap()
        val sourceBitmap =
            captureReactionFrame(
                "src/test/snapshots/local_reaction_motion_${name}_first.png",
                FOCUSED_OVERLAY_FRAME_TEST_TAG,
            )
        rule
            .onNodeWithTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        rule.runOnIdle { assertTrue("the production quick-reaction action must accept the local intent", accepted) }
        val capturedHandoffs = mutableSetOf<Int>()
        val phases = mutableListOf<String>()
        val landingSamples = mutableMapOf<Int, Pair<IntRect, IntArray>>()
        val overflowSamples = mutableMapOf<Int, Long>()
        var nativeChromeBounds: androidx.compose.ui.geometry.Rect? = null
        var checkedPendingChrome = false
        val checksNativeLanding = motionEnabled && name !in setOf("overflow", "interrupted", "missing_art")
        repeat(24) { index ->
            if (name == "interrupted" && index == 6) rule.runOnIdle { animations = false }
            flushQueuedComposition()
            rule.onNodeWithTag("reaction-motion-root").assertExists()
            // Native recordings publish during draw, independently of optional frame export.
            captureReactionBitmap()
            motion.targets[emoji]
                ?.chrome
                ?.bounds
                ?.let { nativeChromeBounds = it }
            if (name == "partial_chrome" && index == 6) {
                val glyph = motion.targets.getValue(emoji).bounds
                val chrome = requireNotNull(nativeChromeBounds)
                assertTrue("the frame must extend beyond its glyph", chrome.right > glyph.right + 1f)
                rule.runOnIdle {
                    motion.viewport =
                        androidx.compose.ui.geometry
                            .Rect(0f, 0f, glyph.right + 1f, 640f)
                }
                flushQueuedComposition()
                captureReactionBitmap()
                rule.runOnIdle {
                    assertNull(
                        "partially visible chrome must use its canonical native surface",
                        motion.targets[emoji]?.chrome,
                    )
                }
            }
            val earlyFlight = name == "amoled" && motion.effect != null && motion.progress.value in 0.05f..0.2f
            if (earlyFlight && nativeChromeBounds != null) {
                val bounds = requireNotNull(nativeChromeBounds).translate(-motion.origin)
                val crop =
                    IntRect(
                        floor(bounds.left).toInt(),
                        floor(bounds.top).toInt(),
                        ceil(bounds.right).toInt(),
                        ceil(bounds.bottom).toInt(),
                    )
                assertArrayEquals(
                    "the new outline must not park at the destination before following the emoji",
                    cropPixels(destinationBefore, crop),
                    cropPixels(captureReactionBitmap(), crop),
                )
                checkedPendingChrome = true
            }
            if (index == 0 && name in setOf("amoled", "large_rtl", "bundled", "rectangular")) {
                val source = requireNotNull(motion.effect?.origin).translate(-motion.origin)
                val crop =
                    IntRect(
                        floor(source.left - 3).toInt(),
                        floor(source.top - 3).toInt(),
                        ceil(source.right + 3).toInt(),
                        ceil(source.bottom + 3).toInt(),
                    )
                val firstFlight = captureReactionBitmap()
                if (name == "bundled" || name == "rectangular") {
                    assertArrayEquals(
                        "already-decoded artwork must depart from its exact rendered pixels",
                        cropPixels(sourceBitmap, crop),
                        cropPixels(firstFlight, crop),
                    )
                } else {
                    val before = requireNotNull(yellowBounds(sourceBitmap, crop))
                    val departing = requireNotNull(yellowBounds(firstFlight, crop))
                    assertTrue(
                        "the emoji must not jump horizontally when leaving the rail",
                        kotlin.math.abs(before.center.x - departing.center.x) <= 1,
                    )
                    assertTrue(
                        "the emoji must not jump vertically when leaving the rail",
                        kotlin.math.abs(before.center.y - departing.center.y) <= 1,
                    )
                }
            }
            if (checksNativeLanding && motion.effect != null) {
                val elapsed = (motion.progress.value * LOCAL_REACTION_MOTION_MILLIS).roundToInt()
                listOf(176, 192, 208).forEach { millis ->
                    if (elapsed >= millis && !landingSamples.containsKey(millis)) {
                        val target = motion.targets.getValue(emoji)
                        val bounds = (nativeChromeBounds ?: target.bounds).translate(-motion.origin)
                        val crop =
                            IntRect(
                                floor(bounds.left).toInt(),
                                floor(bounds.top).toInt(),
                                ceil(bounds.right).toInt(),
                                ceil(bounds.bottom).toInt(),
                            )
                        val bitmap = captureReactionBitmap()
                        if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
                            bitmap.captureRoboImage(File(folder, "native-endpoint-$millis.png").path)
                        }
                        landingSamples[millis] = crop to cropPixels(bitmap, crop)
                    }
                }
            }
            val completedLanding = checksNativeLanding && motion.effect == null
            val needsCompletionSample = landingSamples.containsKey(208) && !landingSamples.containsKey(224)
            if (completedLanding && needsCompletionSample) {
                val crop = landingSamples.getValue(208).first
                landingSamples[224] = crop to cropPixels(captureReactionBitmap(), crop)
            }
            if (name == "overflow" && motion.effect != null) {
                val elapsed = (motion.progress.value * LOCAL_REACTION_MOTION_MILLIS).roundToInt()
                listOf(176, 192, 208).forEach { millis ->
                    if (elapsed >= millis && !overflowSamples.containsKey(millis)) {
                        val bounds =
                            motion.targets
                                .getValue(emoji)
                                .bounds
                                .translate(-motion.origin)
                        val crop =
                            IntRect(
                                floor(bounds.left).toInt(),
                                floor(bounds.top).toInt(),
                                ceil(bounds.right).toInt(),
                                ceil(bounds.bottom).toInt(),
                            )
                        overflowSamples[millis] = yellowStrength(cropPixels(captureReactionBitmap(), crop))
                    }
                }
            }
            if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
                captureReactionFrame(File(folder, "frame-%03d.png".format(index + 1)).path)
                val elapsed = (motion.progress.value * LOCAL_REACTION_MOTION_MILLIS).roundToInt()
                phases += "frame=${index + 1} phase=${motion.progress.value} active=${motion.effect != null}"
                if (motion.effect != null) {
                    listOf(176, 192, 208).forEach { handoff ->
                        if (elapsed >= handoff && capturedHandoffs.add(handoff)) {
                            assertTrue("handoff capture must be within one animation frame", elapsed - handoff <= 16)
                            captureReactionFrame(File(folder, "handoff-$handoff.png").path)
                        }
                    }
                }
            }
            if (index == 6) {
                rule.onNodeWithTag(FOCUSED_OVERLAY_FRAME_TEST_TAG).assertDoesNotExist()
                rule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").assertExists()
                if (motionEnabled && name !in setOf("interrupted", "missing_art")) {
                    rule.runOnIdle {
                        assertTrue(
                            "a measured destination must exist before the flight capture",
                            motion.targets[emoji] != null,
                        )
                        assertTrue(
                            "the production quick rail must report a measured source",
                            motion.effect?.origin != null,
                        )
                    }
                    rule.onNodeWithTag("conversation.reaction.motion.active").assertExists()
                    rule.onNodeWithTag("reaction-motion-root").assertExists()
                }
                if (name == "missing_art") {
                    rule.runOnIdle {
                        assertTrue(
                            "missing artwork must not fly its literal shortcode",
                            motion.effect?.origin == null,
                        )
                    }
                    rule.onNodeWithTag("conversation.reaction.motion.active").assertDoesNotExist()
                }
                if (name == "counted") {
                    val glyph = rule.onNodeWithText(emoji, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val pill = rule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").fetchSemanticsNode().boundsInRoot
                    rule.runOnIdle {
                        assertEquals(
                            glyph.width,
                            motion.targets
                                .getValue(emoji)
                                .bounds.width,
                            0.1f,
                        )
                        assertTrue(
                            "count must not shift the endpoint to the whole-pill center",
                            kotlin.math.abs(glyph.center.x - pill.center.x) > 1f,
                        )
                    }
                }
                captureReactionFrame("src/test/snapshots/local_reaction_motion_${name}_mid.png")
            }
        }
        if (name == "amoled") {
            assertTrue(
                "an early native frame must verify the absent destination outline",
                checkedPendingChrome,
            )
        }
        val settled = captureReactionFrame("src/test/snapshots/local_reaction_motion_${name}_settled.png")
        if (checksNativeLanding) {
            assertEquals(
                "all late opaque-overlay frames must be checked",
                setOf(
                    176,
                    192,
                    208,
                    224,
                ),
                landingSamples.keys,
            )
            landingSamples.forEach { (millis, sample) ->
                assertArrayEquals(
                    "$name native destination pixels must not shift or dim at $millis ms",
                    sample.second,
                    cropPixels(settled, sample.first),
                )
            }
        }
        if (name == "overflow") {
            assertEquals(setOf(176, 192, 208), overflowSamples.keys)
            assertTrue(
                "overflow emoji must fade while the numeric chip remains",
                overflowSamples.getValue(176) > overflowSamples.getValue(192),
            )
            assertTrue(
                "overflow emoji must keep fading through handoff",
                overflowSamples.getValue(192) > overflowSamples.getValue(208),
            )
        }
        if (System.getenv("ANIMATION_CAPTURE_FRAMES") == "1") {
            File(
                folder,
                "phases.txt",
            ).writeText(
                phases.joinToString(
                    "\n",
                    postfix = "\n",
                ),
            )
        }
    }

    /** A paused test clock must publish queued state before delivering the recomposition frame. */
    private fun flushQueuedComposition(millis: Long = 16) {
        rule.runOnUiThread { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    /** Capture exactly this native node, avoiding Roborazzi's multiple-popup-window fallback. */
    private fun captureReactionFrame(
        path: String,
        tag: String = "reaction-motion-root",
    ): Bitmap {
        val bitmap = captureReactionBitmap(tag)
        bitmap.captureRoboImage(path)
        return bitmap
    }

    /** Native pixel assertions are independent of Roborazzi's baseline recording/verification mode. */
    private fun captureReactionBitmap(
        tag: String =
            "reaction-motion-root",
    ): Bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    /** Font hinting may change edge alpha, but the visible glyph center must stay on the rail's source. */
    private fun yellowBounds(
        bitmap: Bitmap,
        crop: IntRect,
    ): IntRect? {
        var left = crop.right
        var top = crop.bottom
        var right = crop.left
        var bottom = crop.top
        cropPixels(bitmap, crop).forEachIndexed { index, pixel ->
            val red = pixel ushr 16 and 255
            val green = pixel ushr 8 and 255
            val blue = pixel and 255
            val warm = red > 90 && green > 55
            if (warm && blue < green * 0.65f && red > green * 0.8f) {
                val x = index % crop.width + crop.left
                val y = index / crop.width + crop.top
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        return if (left >= right || top >= bottom) null else IntRect(left, top, right, bottom)
    }

    /** White/gray chip text contributes zero; the thumbs-up's yellow content tracks its visible opacity. */
    private fun yellowStrength(pixels: IntArray): Long =
        pixels.sumOf { pixel ->
            val red = pixel ushr 16 and 255
            val green = pixel ushr 8 and 255
            val blue = pixel and 255
            (minOf(red, green) - blue).coerceAtLeast(0).toLong()
        }

    /** Compare the native glyph's actual pixels, including baseline and inline artwork placement. */
    private fun cropPixels(
        bitmap: Bitmap,
        bounds: IntRect,
    ): IntArray =
        IntArray(bounds.width * bounds.height).also { pixels ->
            bitmap.getPixels(pixels, 0, bounds.width, bounds.left, bounds.top, bounds.width, bounds.height)
        }
}
