package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationSendRequest
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.translation.ComposerTranslationState
import dev.ipf.whitenoise.android.translation.TranslationEngine
import dev.ipf.whitenoise.android.translation.TranslationEngineIdentity
import dev.ipf.whitenoise.android.translation.TranslationTrust
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drives the real composer Send paths with translate mode on, so only a ready translation can leave. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerTranslationSendTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sent = mutableListOf<String>()

    /** Stops any translation request the composer left running. */
    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Send is absent while the translation is pending (a ring holds its place), then delivers the translation. */
    @Test
    fun plainTextSendWaitsForAndDeliversTheTranslation() {
        val gate = CompletableDeferred<Unit>()
        val translation = translateMode(beforeResult = { gate.await() })
        render(translation)
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        composeRule.waitUntil { translation.pending }
        send.assertDoesNotExist()
        composeRule.onNodeWithTag("composer.send.progress").assertExists()
        composeRule.onNodeWithTag("composer.send.slot").assertExists()
        assertTrue(sent.isEmpty())

        gate.complete(Unit)
        awaitMain { !translation.pending }
        composeRule.onNodeWithTag("composer.send.progress").assertDoesNotExist()
        send.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf("de:Hello"), sent) }
    }

    /** A failed translation leaves neither Send nor a ring; the slot stays reserved. */
    @Test
    fun failedTranslationKeepsSendHidden() {
        val translation = translateMode(beforeResult = { error("offline") })
        render(translation)
        awaitMain { translation.failure != null }
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertDoesNotExist()
        composeRule.onNodeWithTag("composer.send.progress").assertDoesNotExist()
        composeRule.onNodeWithTag("composer.send.slot").assertExists()
        assertTrue(sent.isEmpty())
    }

    /** Translate mode without a target shows no Send and no ring. */
    @Test
    fun noTargetKeepsSendHiddenWithoutRing() {
        val translation = translateMode(initialTarget = null)
        render(translation)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertDoesNotExist()
        composeRule.onNodeWithTag("composer.send.progress").assertDoesNotExist()
        composeRule.onNodeWithTag("composer.send.slot").assertExists()
    }

    /** A reply goes through the same gate and carries the translation. */
    @Test
    fun replySendsTheTranslation() {
        val translation = translateMode()
        render(translation, replyingTo = timelineAppMessage(REPLY_MESSAGE_ID))
        composeRule.waitUntil { translation.outgoingFor(DRAFT) != null }
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("de:Hello"), sent) }
    }

    /** An attachment caption is translated before it reaches the attachment send path. */
    @Test
    fun attachmentCaptionSendsTheTranslation() {
        val translation = translateMode()
        render(translation, attachments = true)
        composeRule.waitUntil { translation.outgoingFor(DRAFT) != null }
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("de:Hello"), sent) }
    }

    /** The keyboard's Send action is gated like the button: nothing while pending, then the translation. */
    @Test
    fun imeSendWaitsForAndDeliversTheTranslation() {
        val gate = CompletableDeferred<Unit>()
        val translation = translateMode(beforeResult = { gate.await() })
        render(translation)
        val editor = composeRule.onNode(hasSetTextAction())
        composeRule.waitUntil { translation.pending }
        editor.performImeAction()
        assertTrue(sent.isEmpty())

        gate.complete(Unit)
        awaitMain { !translation.pending }
        editor.performImeAction()
        composeRule.runOnIdle { assertEquals(listOf("de:Hello"), sent) }
    }

    /** The preview stays idle during capture and refreshes the full editable draft after Paste. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun dictationPasteKeepsTranslateModeAndRefreshesTheDraft() {
        var calls = 0
        val translation = translateMode(beforeResult = { calls += 1 })
        val editor = ComposerTextState(TextFieldValue(DRAFT, TextRange(DRAFT.length)))
        val platform = TestDictationPlatform()
        val controller = dictationController(platform, editor)
        render(translation, dictationController = controller, textState = editor)
        composeRule.waitUntil { translation.outgoingFor(DRAFT) != null }

        composeRule.runOnIdle {
            assertTrue(controller.requestStart(ACCOUNT, GROUP, editor.valueState.value))
            platform.listener.onReady()
            editor.updateValue(TextFieldValue("Hello there", TextRange(DRAFT.length)))
        }
        composeRule.onNodeWithTag("composer.translation").assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(translation.active)
            assertEquals(1, calls)
            editor.updateValue(TextFieldValue(DRAFT, TextRange(DRAFT.length)))
            controller.paste()
            platform.listener.onResult("dictated")
            assertEquals("Hello dictated", editor.valueState.value.text)
            assertEquals(ConversationDictationState.Idle, controller.state)
        }
        awaitMain { translation.outgoingFor("Hello dictated") == "de:Hello dictated" }
        composeRule
            .onNodeWithTag("composer.translation.afterDictation")
            .captureRoboImage("src/test/snapshots/composer_translation_after_dictation_paste.png")
        composeRule.runOnIdle {
            editor.updateValue(TextFieldValue("Hello dictated later"))
        }
        awaitMain { translation.outgoingFor("Hello dictated later") == "de:Hello dictated later" }
    }

    /** Turning Translate on after Paste starts with the text already in the editor. */
    @Test
    fun translateModeCanStartAfterDictationPaste() {
        val translation = translateMode().also { it.close() }
        val editor = ComposerTextState(TextFieldValue(DRAFT, TextRange(DRAFT.length)))
        val platform = TestDictationPlatform()
        val controller = dictationController(platform, editor)
        render(translation, dictationController = controller, textState = editor)

        composeRule.runOnIdle {
            assertTrue(controller.requestStart(ACCOUNT, GROUP, editor.valueState.value))
            platform.listener.onReady()
            controller.paste()
            platform.listener.onResult("dictated")
            assertEquals("Hello dictated", editor.valueState.value.text)
            assertEquals(ConversationDictationState.Idle, controller.state)
            translation.toggle()
        }
        awaitMain { translation.outgoingFor("Hello dictated") == "de:Hello dictated" }
    }

    /** One Send per recording, even while the translator is slow or was reset during capture. */
    @Test
    fun dictationSendWaitsForTranslationAndDoesNotRepeatIt() {
        val allowTranslation = CompletableDeferred<Unit>()
        var calls = 0
        val translation =
            translateMode(
                beforeResult = {
                    calls += 1
                    if (calls > 1) allowTranslation.await()
                },
            )
        val editor = ComposerTextState(TextFieldValue(DRAFT, TextRange(DRAFT.length)))
        val platform = TestDictationPlatform()
        val controller =
            dictationController(platform, editor) { request ->
                request.beginDispatch().also { if (it) sent += request.payload }
            }
        render(translation, dictationController = controller, textState = editor)
        composeRule.waitUntil { translation.outgoingFor(DRAFT) == "de:Hello" }

        val dictate = composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text))
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        dictate.performClick()
        composeRule.runOnIdle {
            platform.listener.onReady()
            translation.invalidate()
        }
        send.assertIsEnabled().performClick()
        composeRule.runOnIdle { platform.listener.onResult("dictated") }
        awaitMain { calls == 2 }
        send.assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertTrue(sent.isEmpty()) }
        allowTranslation.complete(Unit)
        awaitMain { sent.size == 1 }
        composeRule.runOnIdle {
            assertEquals(ConversationDictationState.Idle, controller.state)
            assertEquals("", editor.valueState.value.text)
        }

        val firstListener = platform.listener
        dictate.performClick()
        composeRule.runOnIdle {
            assertNotSame(firstListener, platform.listener)
            platform.listener.onReady()
        }
        send.assertIsEnabled().performClick()
        composeRule.runOnIdle { platform.listener.onResult("next") }
        awaitMain { sent.size == 2 }
        composeRule.runOnIdle {
            assertEquals(3, calls)
            assertEquals(listOf("de:Hello dictated", "de:next"), sent)
        }
    }

    /**
     * Runs current main-looper work and lets the composer apply resulting state changes before checking [condition].
     */
    private fun awaitMain(condition: () -> Boolean) {
        repeat(MAX_FRAMES) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeByFrame()
            if (composeRule.runOnIdle(condition)) return
        }
        throw AssertionError("Condition still not satisfied after $MAX_FRAMES frames")
    }

    /** Opens translate mode into German with an engine that waits on [beforeResult] and then prefixes the target. */
    private fun translateMode(
        initialTarget: String? = "de",
        beforeResult: suspend () -> Unit = {},
    ): ComposerTranslationState =
        ComposerTranslationState(
            scope = scope,
            engine = { engine(beforeResult) },
            ownerCurrent = { true },
            initialTarget = initialTarget,
            settleMillis = 0L,
        ).also { it.toggle() }

    /** Renders the real composer with [translation] supplied and every send path recorded into [sent]. */
    private fun render(
        translation: ComposerTranslationState,
        replyingTo: AppMessageRecordFfi? = null,
        attachments: Boolean = false,
        dictationController: ConversationDictationController? = null,
        textState: ComposerTextState = ComposerTextState(TextFieldValue(DRAFT)),
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).testTag("composer.translation.afterDictation")) {
                    ComposerBar(
                        replyingTo = replyingTo,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { text, accepted ->
                            sent += text
                            accepted()
                        },
                        initialDraft = TextFieldValue(DRAFT),
                        hasPendingAttachments = attachments,
                        onSendAttachments = { text, done ->
                            sent += text
                            done(true)
                        },
                        dictationController = dictationController,
                        dictationAccountRef = ACCOUNT,
                        dictationGroupIdHex = GROUP,
                        composerTranslation = translation,
                        textState = textState,
                    )
                }
            }
        }
    }

    /** A local engine that never touches the network. */
    private fun engine(beforeResult: suspend () -> Unit) =
        object : TranslationEngine {
            override val identity = TranslationEngineIdentity("local", "verified", 1)
            override val trust = TranslationTrust.LOCAL

            /** Waits on the test gate, then returns the target-prefixed text. */
            override suspend fun translate(
                text: String,
                source: String,
                target: String,
            ): String {
                beforeResult()
                return "$target:$text"
            }
        }

    /** A dictation controller that can start without a real recognizer. */
    private fun dictationController(
        platform: TestDictationPlatform,
        editor: ComposerTextState,
        sendTranscript: suspend (ConversationDictationSendRequest) -> Boolean = { false },
    ) =
        ConversationDictationController(
            platform = platform,
            readDraft = { _, _ ->
                ConversationDictationDraftSnapshot(editor.valueState.value, editor.contentGeneration)
            },
            writeDraft = { _, _, revision, value ->
                if (revision != editor.contentGeneration) {
                    null
                } else {
                    editor.updateValue(value)
                    editor.contentGeneration
                }
            },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            targetValidationScope = scope,
            sendTranscriptIfOriginUnchanged = sendTranscript,
        )

    /** Grants permission and hands out a recognizer session that does nothing. */
    private class TestDictationPlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener
            private set

        /** Has record audio permission. */
        override fun hasRecordAudioPermission() = true

        /** Recognition is available. */
        override fun recognitionAvailable() = true

        /** Returns a session whose controls are no-ops. */
        override fun createSession(
            listener: ConversationDictationRecognitionListener,
        ): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                /** Starts nothing. */
                override fun start() = Unit

                /** Stops nothing. */
                override fun stop() = Unit

                /** Cancels nothing. */
                override fun cancel() = Unit

                /** Releases nothing. */
                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        const val DRAFT = "Hello"
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val REPLY_MESSAGE_ID = "reply-message"
        const val MAX_FRAMES = 60
    }
}
