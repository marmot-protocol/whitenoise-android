package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.state.ComposerVoicePlacement
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A locked voice take at 240, 280, 360 and 412 dp with 200% text, in both directions, keeps its Lock indicator and
 * its Pause or Resume control whole, inside the composer, clear of Cancel and Stop, and with a 48 dp target.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w412dp-h780dp-mdpi")
class VoiceRecordingStripFitScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var voice: VoiceRecordingController

    private var replyCancels = 0

    /** The narrowest supported width keeps Lock and Pause beside the timer in LTR. */
    @Test fun locked240Ltr() = verifyFit(240, rtl = false, capturePaused = true)

    /** The narrowest supported width keeps Lock and Pause beside the timer in RTL. */
    @Test fun locked240Rtl() = verifyFit(240, rtl = true, capturePaused = true)

    /** A 280 dp composer keeps Lock and Pause in LTR. */
    @Test fun locked280Ltr() = verifyFit(280, rtl = false)

    /** A 280 dp composer keeps Lock and Pause in RTL. */
    @Test fun locked280Rtl() = verifyFit(280, rtl = true)

    /** A 360 dp composer keeps Lock and Pause in LTR. */
    @Test fun locked360Ltr() = verifyFit(360, rtl = false)

    /** A 360 dp composer keeps Lock and Pause in RTL. */
    @Test fun locked360Rtl() = verifyFit(360, rtl = true)

    /** A 412 dp composer keeps Lock and Pause in LTR. */
    @Test fun locked412Ltr() = verifyFit(412, rtl = false)

    /** A 412 dp composer keeps Lock and Pause in RTL. */
    @Test fun locked412Rtl() = verifyFit(412, rtl = true)

    /** A locked reply at default text keeps the reply preview and its Cancel reply control clear of the strip. */
    @Test fun lockedReply360Ltr() = verifyFit(360, rtl = false, fontScale = 1f, reply = true)

    /** A locked reply at 240 dp with 200% text keeps the reply preview clear while Lock and Pause fit. */
    @Test fun lockedReply240Rtl() = verifyFit(240, rtl = true, reply = true)

    /** A locked reply with the microphone on the start side keeps Cancel reply pressable above the strip. */
    @Test fun lockedReplyStartMic240Ltr() = verifyFit(240, rtl = false, reply = true, micAtStart = true)

    /**
     * Locks a take at [width] dp, optionally while replying or with the microphone on the start side, checks and
     * captures the strip, then pauses and resumes it by touch.
     */
    private fun verifyFit(
        width: Int,
        rtl: Boolean,
        capturePaused: Boolean = false,
        fontScale: Float = 2f,
        reply: Boolean = false,
        micAtStart: Boolean = false,
    ) {
        val direction = if (rtl) "rtl" else "ltr"
        val text = if (fontScale > 1f) "large" else "default"
        val variant = (if (reply) "reply_" else "") + (if (micAtStart) "start_mic_" else "")
        val name = "voice_strip_fit_$variant${width}_${direction}_$text"
        val toolbar = ComposerToolbarSettings().withVoicePlacement(ComposerVoicePlacement.Left)
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply { if (micAtStart) putString("composer_toolbar_v1", toolbar.encode()) }
            .commit()
        composeRule.setContent { stripHost(width, rtl, fontScale, reply) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOST_TAG).captureRoboImage("src/test/snapshots/${name}_locked.png")
        val lock = glyph("Locked")
        assertTrue("Lock keeps its 16 dp glyph: $lock", lock.width >= 15.5f && lock.height >= 15.5f)
        assertControlFits("composer.voice.pause", "Pause recording", lock)
        if (reply) assertReplyClear(lock, "composer.voice.pause")
        press("composer.voice.pause")
        composeRule.runOnIdle { assertTrue("a press on Pause pauses the take", voice.isPaused) }
        if (capturePaused) {
            composeRule.onNodeWithTag(HOST_TAG).captureRoboImage("src/test/snapshots/${name}_paused.png")
        }
        assertControlFits("composer.voice.resume", "Resume", glyph("Locked"))
        press("composer.voice.resume")
        composeRule.runOnIdle { assertTrue("a press on Resume resumes the take", !voice.isPaused) }
    }

    /**
     * The control's glyph is whole and its 48 dp target lies inside the composer, clear of [lock], Cancel and Stop.
     */
    private fun assertControlFits(
        tag: String,
        description: String,
        lock: Rect,
    ) {
        val host = composeRule.onNodeWithTag(HOST_TAG).fetchSemanticsNode().boundsInRoot
        val target = composeRule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
        val icon = glyph(description)
        assertTrue("$tag keeps its 20 dp glyph: $icon", icon.width >= 19.5f && icon.height >= 19.5f)
        assertTrue("$tag has a 48 dp target: $target", target.width >= 47.5f && target.height >= 47.5f)
        val actions = listOf(bounds("Cancel voice message"), bounds("Stop recording"))
        listOf(lock, target).forEach { item ->
            assertTrue("$item stays inside the composer $host", host.contains(item))
            actions.forEach { action -> assertTrue("$item clears $action", !item.overlapsStrictly(action)) }
        }
        assertTrue("$tag clears the Lock glyph $lock", !target.overlapsStrictly(lock))
    }

    /** The reply preview's Cancel reply stays clear of the Lock glyph and the [tag] target, and a press reaches it. */
    private fun assertReplyClear(
        lock: Rect,
        tag: String,
    ) {
        val cancelReply = bounds("Cancel reply")
        val target = composeRule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
        listOf(lock, target).forEach { item ->
            assertTrue("$item clears the reply preview's $cancelReply", !item.overlapsStrictly(cancelReply))
        }
        composeRule.onNodeWithContentDescription("Cancel reply").performTouchInput {
            down(center)
            up()
        }
        composeRule.runOnIdle { assertEquals("a press on Cancel reply reaches it", 1, replyCancels) }
    }

    /** Presses and releases the control's center by touch, with no move between, as a still finger would. */
    private fun press(tag: String) {
        composeRule.onNodeWithTag(tag).performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
    }

    /** The drawn bounds of the glyph with [description], read from the unmerged tree inside its button. */
    private fun glyph(description: String): Rect =
        composeRule
            .onNodeWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    /** Hosts the real composer bar at [width] dp and [fontScale], optionally replying, and locks a recording. */
    @Composable
    private fun stripHost(
        width: Int,
        rtl: Boolean,
        fontScale: Float,
        reply: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
        val controller = remember { fakeVoice(context, scope) }
        val dictation = remember { idleDictation() }
        LaunchedEffect(controller) {
            voice = controller
            check(controller.start())
            controller.lock()
        }
        DisposableEffect(controller) {
            onDispose {
                controller.release()
                scope.cancel()
            }
        }
        val alice = testAccount("alice", "aa".repeat(32))
        val bob = testAccount("bob", "bb".repeat(32))
        CompositionLocalProvider(
            LocalDensity provides Density(1f, fontScale),
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Surface(Modifier.width(width.dp).testTag(HOST_TAG)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            appState = emptyAppState(accounts = listOf(alice, bob), activeAccountRef = "bob"),
                            draftAccountRef = "alice",
                            replyingTo = replyRecord().takeIf { reply },
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = { replyCancels++ },
                            onSend = { _, _ -> },
                            initialDraft = TextFieldValue(""),
                            onPickDocument = {},
                            dictationController = dictation,
                            dictationAccountRef = "alice",
                            dictationGroupIdHex = "strip-fit-group",
                            voiceRecordingController = controller,
                        )
                    }
                }
            }
        }
    }

    /** Returns the root bounds of the node with the given content description. */
    private fun bounds(description: String): Rect {
        val node = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode()
        return node.boundsInRoot
    }

    private companion object {
        const val HOST_TAG = "strip-fit"
    }
}

/** Whether two rectangles share area beyond a one-pixel rounding allowance. */
private fun Rect.overlapsStrictly(other: Rect): Boolean {
    val acrossWidth = left < other.right - 1f && other.left < right - 1f
    return acrossWidth && top < other.bottom - 1f && other.top < bottom - 1f
}

/** Whether [inner] lies inside this rectangle, allowing one pixel of rounding. */
private fun Rect.contains(inner: Rect): Boolean {
    val withinWidth = inner.left >= left - 1f && inner.right <= right + 1f
    return withinWidth && inner.top >= top - 1f && inner.bottom <= bottom + 1f
}

/** A received text message for the composer to reply to. */
private fun replyRecord() =
    AppMessageRecordFfi(
        invalidated = false,
        messageIdHex = "parent",
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = "Parent message",
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = ByteArray(0),
            ),
        kind = 9uL,
        tags = emptyList(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
        sticker = null,
    )

/** A voice controller whose session records nothing and runs on unconfined dispatchers. */
private fun fakeVoice(
    context: Context,
    scope: CoroutineScope,
): VoiceRecordingController =
    VoiceRecordingController(
        context,
        context.cacheDir,
        scope,
        { true },
        { _, _ -> },
        { throw it },
        recorderFactory = { _, _, _ ->
            object : VoiceRecordingSession {
                /** Pretends recording began without touching the microphone. */
                override fun start() = Unit

                /** Yields no recording, since the fake session captures no audio. */
                override fun stop(): VoiceRecordingResult? = null

                /** Discards nothing, because the fake session holds no audio. */
                override fun cancel() = Unit
            }
        },
        mainDispatcher = Dispatchers.Unconfined,
        recorderDispatcher = Dispatchers.Unconfined,
    )

/** A dictation controller for an empty draft whose platform never starts recognition. */
private fun idleDictation(): ConversationDictationController =
    ConversationDictationController(
        platform = IdleDictationPlatform,
        readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(""), 0L) },
        writeDraft = { _, _, _, _ -> 0L },
        disclosureAccepted = { true },
        markDisclosureAccepted = {},
    )

private object IdleDictationPlatform : ConversationDictationPlatform {
    /** Reports the microphone permission as granted. */
    override fun hasRecordAudioPermission() = true

    /** Reports speech recognition as available so the dictation slot renders. */
    override fun recognitionAvailable() = true

    /** Fails the test if dictation is ever started, since this host only renders the composer. */
    override fun createSession(listener: ConversationDictationRecognitionListener) = error("Unexpected recognition")
}
