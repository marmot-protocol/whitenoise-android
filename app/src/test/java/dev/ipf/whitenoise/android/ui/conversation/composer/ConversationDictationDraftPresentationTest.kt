package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationDraftPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun terminalRecoveryRehydratesTheMountedEditorAndRejectsItsOldCallbacks() {
        val fixture = Fixture()
        val writers = mutableMapOf<Int, (TextFieldValue) -> Unit>()
        lateinit var editor: ComposerTextState
        composeRule.setContent {
            val revision = fixture.controller.completionRevision(ACCOUNT, GROUP)
            editor = rememberComposerTextState(GROUP, fixture.draft, 0 to revision)
            val writer =
                conversationDictationDraftWriter(fixture.controller, ACCOUNT, GROUP, revision, fixture::edit)
            writers[revision] = writer
            BasicTextField(
                value = editor.valueState.value,
                onValueChange = {
                    editor.updateValue(it)
                    writer(it)
                },
                modifier = Modifier.testTag("recovery-editor"),
            )
        }
        composeRule.waitForIdle()
        lateinit var oldEditor: ComposerTextState
        lateinit var oldAcceptance: ComposerAcceptanceToken
        composeRule.runOnIdle {
            oldEditor = editor
            oldAcceptance = editor.acceptanceToken()
            fixture.failSend()
            // This input event arrives after the write, before Compose has replaced the editor.
            writers.getValue(0)(TextFieldValue("stale edit"))
            assertEquals("Draft recognized", fixture.draft.text)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("recovery-editor").assertTextEquals("Draft recognized")
        composeRule.runOnIdle {
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            assertEquals(TextRange(16), editor.valueState.value.selection)
            assertEquals(42L, fixture.revision)
            assertEquals(0, fixture.editorWrites)
            assertFalse(editor.clearAccepted(oldAcceptance))
            assertTrue(oldEditor.clearAccepted(oldAcceptance))
            writers.getValue(0)(TextFieldValue(""))
            assertEquals("Draft recognized", fixture.draft.text)
            val recoveredEditor = editor
            fixture.controller.onAppForegrounded()
            assertEquals(1, fixture.controller.completionRevision(ACCOUNT, GROUP))
            assertSame(recoveredEditor, editor)
            writers.getValue(1)(TextFieldValue("Draft recognized edited", TextRange(23)))
            assertEquals("Draft recognized edited", fixture.draft.text)
            assertEquals(1, fixture.editorWrites)
        }
    }

    private class Fixture {
        var draft by mutableStateOf(TextFieldValue("Draft", TextRange(5)))
        // Native draft generations and editor presentation revisions are separate domains.
        var revision = 41L
        var editorWrites = 0
        private lateinit var listener: ConversationDictationRecognitionListener
        val controller =
            ConversationDictationController(
                platform =
                    object : ConversationDictationPlatform {
                        override fun hasRecordAudioPermission(): Boolean = true

                        override fun recognitionAvailable(): Boolean = true

                        override fun createSession(
                            listener: ConversationDictationRecognitionListener,
                        ): ConversationDictationRecognitionSession {
                            this@Fixture.listener = listener
                            return object : ConversationDictationRecognitionSession {
                                override fun start() = listener.onReady()

                                override fun stop() = Unit

                                override fun cancel() = Unit

                                override fun destroy() = Unit
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
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )

        fun failSend() {
            assertTrue(controller.requestStart(ACCOUNT, GROUP, draft))
            controller.send()
            listener.onResult("recognized")
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
