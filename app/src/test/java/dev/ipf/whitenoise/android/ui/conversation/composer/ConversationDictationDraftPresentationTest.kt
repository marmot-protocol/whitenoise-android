package dev.ipf.whitenoise.android.ui.conversation.composer

import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener as RecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationDictationDraftPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalRecoveryRehydratesTheMountedEditorAndRejectsItsOldCallbacks() {
        val fixture = Fixture()
        composeRule.setContent { fixture.RenderComposer() }
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        lateinit var oldEditor: ComposerTextState
        lateinit var oldAcceptance: ComposerAcceptanceToken
        composeRule.runOnIdle {
            oldEditor = fixture.editor
            oldAcceptance = oldEditor.acceptanceToken()
            fixture.failSend()
            // The old input writer and actual ComposerBar acceptance land before the editor redraw.
            fixture.writers.getValue(0)(TextFieldValue("stale edit"))
            fixture.lateSendAcceptance.invoke()
            assertEquals("", oldEditor.valueState.value.text)
            assertEquals("Draft recognized", fixture.draft.text)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft recognized").assertExists()
        composeRule.runOnIdle {
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertEquals(TextRange(16), fixture.editor.valueState.value.selection)
            assertEquals(42L, fixture.revision)
            assertEquals(0, fixture.editorWrites)
            assertFalse(fixture.editor.clearAccepted(oldAcceptance))
            val recoveredEditor = fixture.editor
            fixture.controller.onAppForegrounded()
            assertEquals(1, fixture.controller.completionRevision(ACCOUNT, GROUP))
            assertSame(recoveredEditor, fixture.editor)
        }
        composeRule.onNodeWithText("Draft recognized").performTextReplacement("Draft recognized edited")
        composeRule.runOnIdle {
            assertEquals("Draft recognized edited", fixture.draft.text)
            assertEquals(1, fixture.editorWrites)
            fixture.controller.cancel()
            assertEquals("Draft recognized edited", fixture.draft.text)
        }
    }

    @Test
    fun recoveredComposerOffersOrdinarySendAndImeWithoutDictationErrorControls() {
        val f = Fixture()
        composeRule.setContent { f.RenderComposer() }
        composeRule.runOnIdle { f.failSend() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText("Draft recognized").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("Draft recognized"), f.sent)
            assertTrue(f.controller.state is ConversationDictationState.Idle)
            f.lateSendAcceptance.invoke()
            assertEquals("", f.editor.valueState.value.text)
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Next message")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.runOnIdle { assertEquals(listOf("Draft recognized", "Next message"), f.sent) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.paste)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.retry)).assertDoesNotExist()
    }

    @Test
    fun submittedPrefixClearsMountedEditorAndAudioRetryKeepsNewEditsWithoutResending() {
        val f = Fixture(dispatchRecognized = true)
        composeRule.setContent { f.RenderComposer() }
        composeRule.runOnIdle {
            f.pendingAudio = true
            assertTrue(f.controller.requestStart(ACCOUNT, GROUP, f.draft))
            f.controller.send()
            f.result("recognized")
            f.runRestart()
            f.failTail()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf("Draft recognized"), f.dictationSends)
            assertEquals("", f.editor.valueState.value.text)
            assertEquals("", f.draft.text)
            assertTrue(f.controller.recoveryHandedToComposer)
            assertFalse(f.controller.completionControlsRequired)
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("New edit")
        composeRule.runOnIdle {
            f.controller.retry()
            f.runRestart()
            assertFalse(f.controller.completionControlsRequired)
            f.pendingAudio = false
            f.result("tail")
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New edit tail").assertExists()
        composeRule.runOnIdle {
            assertEquals("New edit tail", f.draft.text)
            assertEquals(listOf("Draft recognized"), f.dictationSends)
            assertTrue(f.controller.state is ConversationDictationState.Idle)
        }
    }

    @Test
    fun retainedAudioKeepsDictateVisibleAndOrdinarySendUsableWithoutNotifications() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        try {
            val f = Fixture()
            composeRule.setContent { f.RenderComposer() }
            composeRule.runOnIdle { f.retainTail() }
            composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text))
                .assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("dictation-recovery-panel").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.dictation_keep_later)).performClick()
            composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text)).assertIsDisplayed()
            composeRule.onNodeWithTag("dictation-composer")
                .captureRoboImage("src/test/snapshots/dictation_retained_compact_composer.png")
            composeRule.onNode(hasSetTextAction()).performTextReplacement("Edited message")
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            composeRule.runOnIdle { f.lateSendAcceptance.invoke() }
            composeRule.onNode(hasSetTextAction()).performTextReplacement("Next message")
            composeRule.onNode(hasSetTextAction()).performImeAction()
            composeRule.runOnIdle {
                assertEquals(listOf("Edited message", "Next message"), f.sent)
                assertTrue(f.pendingAudio)
                assertFalse(f.controller.completionControlsRequired)
            }
        } finally {
            shadowOf(manager).setNotificationsEnabled(true)
        }
    }

    @Test
    fun expandedComposerSettingsReturnOffersDraftOnlyRemainingAudioRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val f = Fixture(dispatchRecognized = true)
        composeRule.setContent { f.RenderComposer() }
        composeRule.onNode(hasSetTextAction()).performClick()
        val expand =
            composeRule.onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals(context.getString(R.string.composer_expand_full_screen), expand.label)
        composeRule.runOnIdle { assertTrue(expand.action()) }
        composeRule.runOnIdle {
            f.pendingAudio = true
            f.controller.requestStart(ACCOUNT, GROUP, f.draft)
            f.controller.send()
            f.result("recognized")
            f.runRestart()
            f.failTail()
            f.controller.onAppForegrounded()
            f.controller.onAppForegrounded()
        }
        val expandedActions =
            composeRule.onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(context.getString(R.string.composer_collapse), expandedActions.single().label)
        composeRule.onNodeWithTag("dictation-composer")
            .captureRoboImage("src/test/snapshots/dictation_retained_expanded_composer.png")
        composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text))
            .assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.dictation_retry_remaining)).performClick()
        composeRule.onNodeWithTag("dictation-recovery-status")
            .assertTextEquals(context.getString(R.string.dictation_processing))
        composeRule.onNodeWithText(context.getString(R.string.dictation_keep_later)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("New edit")
        composeRule.runOnIdle {
            f.runRestart()
            f.pendingAudio = false
            f.result("tail")
        }
        composeRule.onNodeWithText("New edit tail").assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("Draft recognized"), f.dictationSends) }
    }

    @Test
    fun staleDiscardDialogCannotConsumeAudioAfterConversationNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val f = Fixture()
        composeRule.setContent { f.RenderComposer() }
        composeRule.runOnIdle { f.retainTail() }
        composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.discard)).performClick()
        val oldDiscard =
            composeRule.onNode(hasText(context.getString(R.string.discard)) and hasClickAction())
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeRule.runOnIdle { f.visibleGroup = "other" }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dictation-discard-confirmation").assertDoesNotExist()
        composeRule.runOnIdle {
            oldDiscard()
            assertTrue(f.pendingAudio)
            assertTrue(f.controller.state is ConversationDictationState.Failed)
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.dictation_original_conversation)).assertIsDisplayed()
    }

    private class Fixture(
        private val dispatchRecognized: Boolean = false,
    ) {
        var draft by mutableStateOf(TextFieldValue("Draft", TextRange(5)))
        var visibleGroup by mutableStateOf(GROUP)

        // Native draft generations and editor presentation revisions are separate domains.
        var revision = 41L
        var editorWrites = 0
        private var elapsedMillis = 0L
        lateinit var editor: ComposerTextState
        lateinit var lateSendAcceptance: () -> Unit
        val writers = mutableMapOf<Int, (TextFieldValue) -> Unit>()
        val sent = mutableListOf<String>()
        private lateinit var listener: RecognitionListener
        private var recognitionCreated = false
        var pendingAudio = false
        val dictationSends = mutableListOf<String>()
        private val restarts = mutableListOf<() -> Unit>()
        val controller =
            ConversationDictationController(
                platform =
                    object : ConversationDictationPlatform {
                        override fun hasRecordAudioPermission(): Boolean = true

                        override fun recognitionAvailable(): Boolean = true

                        override fun callerAudioHasPending(): Boolean = pendingAudio

                        override fun discardCallerAudio(onClosed: () -> Unit): Boolean {
                            val retained = pendingAudio && recognitionCreated
                            if (retained) {
                                pendingAudio = false
                                onClosed()
                            }
                            return retained
                        }

                        override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean {
                            onClosed()
                            return true
                        }

                        override fun createSession(listener: RecognitionListener): RecognitionSession {
                            this@Fixture.listener = listener
                            recognitionCreated = true
                            return object : RecognitionSession {
                                override fun start() = listener.onReady()

                                override fun stop() = Unit

                                override fun cancel() = Unit

                                override fun destroy() = Unit

                                override fun usesCallerAudioCapture(): Boolean = pendingAudio

                                override fun acknowledgeCallerAudio(): Boolean = true

                                override fun callerAudioFullyFed(): Boolean = true

                                override fun callerAudioIsFinalChunk(): Boolean = true

                                override fun callerAudioContainsSpeech(): Boolean = true
                            }
                        }
                    },
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
                writeDraft = { _, _, expected, value ->
                    if (expected != revision) {
                        null
                    } else {
                        draft = value
                        revision += 1
                        revision
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                elapsedRealtime = { elapsedMillis },
                scheduleTimeout = { delay, callback ->
                    if (delay == 500L) restarts += callback
                    ConversationDictationTimeoutHandle { restarts.remove(callback) }
                },
                targetValidationScope =
                    if (dispatchRecognized) {
                        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    } else {
                        null
                    },
                sendTranscriptIfOriginUnchanged = { request ->
                    request.beginDispatch().also { claimed ->
                        if (claimed) {
                            dictationSends += request.payload
                            request.onPendingShown()
                        }
                    }
                },
            )

        @Composable
        fun RenderComposer() {
            val presentationRevision = controller.completionRevision(ACCOUNT, GROUP)
            val ownerKey = composerDraftOwnerKey(ACCOUNT, GROUP)
            editor = rememberComposerTextState(ownerKey, draft, 0 to presentationRevision)
            val writer = conversationDictationDraftWriter(controller, ACCOUNT, GROUP, presentationRevision, ::edit)
            writers[presentationRevision] = writer
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).testTag("dictation-composer")) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { text, onAccepted ->
                            sent += text
                            lateSendAcceptance = onAccepted
                        },
                        initialDraft = draft,
                        draftKey = ownerKey,
                        draftAccountRef = ACCOUNT,
                        draftGroupIdHex = GROUP,
                        onDraftChange = writer,
                        textState = editor,
                        dictationController = controller,
                        dictationAccountRef = ACCOUNT,
                        dictationGroupIdHex = visibleGroup,
                    )
                }
            }
        }

        fun failSend() {
            assertTrue(controller.requestStart(ACCOUNT, GROUP, draft))
            controller.send()
            listener.onResult("recognized")
        }

        fun retainTail() {
            pendingAudio = true
            controller.requestStart(ACCOUNT, GROUP, draft)
            controller.paste()
            result("recognized")
            runRestart()
            failTail()
        }

        fun result(text: String) = listener.onResult(text)

        fun failTail() = listener.onError(ConversationDictationFailure.NoMatch)

        fun runRestart() {
            elapsedMillis += 500L
            restarts.toList().also { restarts.clear() }.forEach { it() }
        }

        fun edit(value: TextFieldValue) {
            editorWrites += 1
            draft = value
            revision += 1
        }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
