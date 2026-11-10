package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    /** Send stays disabled while the translation is pending, then delivers the translation, never the source. */
    @Test
    fun plainTextSendWaitsForAndDeliversTheTranslation() {
        val gate = CompletableDeferred<Unit>()
        val translation = translateMode { gate.await() }
        render(translation)
        val send = composeRule.onNodeWithContentDescription(context.getString(R.string.send))
        composeRule.waitUntil { translation.pending }
        send.assertIsNotEnabled().performClick()
        assertTrue(sent.isEmpty())

        gate.complete(Unit)
        composeRule.waitUntil { !translation.pending }
        send.assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf("de:Hello"), sent) }
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
        val translation = translateMode { gate.await() }
        render(translation)
        val editor = composeRule.onNode(hasSetTextAction())
        composeRule.waitUntil { translation.pending }
        editor.performImeAction()
        assertTrue(sent.isEmpty())

        gate.complete(Unit)
        composeRule.waitUntil { !translation.pending }
        editor.performImeAction()
        composeRule.runOnIdle { assertEquals(listOf("de:Hello"), sent) }
    }

    /** Starting dictation in this composer turns translate mode off, so the typed text is what Send will use. */
    @Test
    fun dictationStartClosesTranslateMode() {
        val translation = translateMode()
        val controller = idleDictationController()
        render(translation, dictationController = controller)
        composeRule.waitUntil { translation.outgoingFor(DRAFT) != null }

        composeRule.runOnIdle { assertTrue(controller.requestStart(ACCOUNT, GROUP, TextFieldValue(DRAFT))) }
        composeRule.waitUntil { !translation.active }
        assertFalse(translation.active)
        assertEquals(DRAFT, translation.outgoingFor(DRAFT))
    }

    /** Opens translate mode into German with an engine that waits on [beforeResult] and then prefixes the target. */
    private fun translateMode(beforeResult: suspend () -> Unit = {}): ComposerTranslationState =
        ComposerTranslationState(
            scope = scope,
            engine = { engine(beforeResult) },
            ownerCurrent = { true },
            initialTarget = "de",
            settleMillis = 0L,
        ).also { it.toggle() }

    /** Renders the real composer with [translation] supplied and every send path recorded into [sent]. */
    private fun render(
        translation: ComposerTranslationState,
        replyingTo: AppMessageRecordFfi? = null,
        attachments: Boolean = false,
        dictationController: ConversationDictationController? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
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
    private fun idleDictationController() =
        ConversationDictationController(
            platform = SilentDictationPlatform,
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(DRAFT), 0L) },
            writeDraft = { _, _, _, _ -> 0L },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
        )

    /** Grants permission and hands out a recognizer session that does nothing. */
    private data object SilentDictationPlatform : ConversationDictationPlatform {
        /** Has record audio permission. */
        override fun hasRecordAudioPermission() = true

        /** Recognition is available. */
        override fun recognitionAvailable() = true

        /** Returns a session whose controls are no-ops. */
        override fun createSession(listener: ConversationDictationRecognitionListener) =
            object : ConversationDictationRecognitionSession {
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

    private companion object {
        const val DRAFT = "Hello"
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val REPLY_MESSAGE_ID = "reply-message"
    }
}
