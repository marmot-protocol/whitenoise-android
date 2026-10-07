package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
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

    private class Fixture {
        var draft by mutableStateOf(TextFieldValue("Draft", TextRange(5)))

        // Native draft generations and editor presentation revisions are separate domains.
        var revision = 41L
        var editorWrites = 0
        lateinit var editor: ComposerTextState
        lateinit var lateSendAcceptance: () -> Unit
        val writers = mutableMapOf<Int, (TextFieldValue) -> Unit>()
        private lateinit var listener: RecognitionListener
        val controller =
            ConversationDictationController(
                platform =
                    object : ConversationDictationPlatform {
                        override fun hasRecordAudioPermission(): Boolean = true

                        override fun recognitionAvailable(): Boolean = true

                        override fun createSession(listener: RecognitionListener): RecognitionSession {
                            this@Fixture.listener = listener
                            return object : RecognitionSession {
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

        @Composable
        fun RenderComposer() {
            val presentationRevision = controller.completionRevision(ACCOUNT, GROUP)
            val ownerKey = composerDraftOwnerKey(ACCOUNT, GROUP)
            editor = rememberComposerTextState(ownerKey, draft, 0 to presentationRevision)
            val writer = conversationDictationDraftWriter(controller, ACCOUNT, GROUP, presentationRevision, ::edit)
            writers[presentationRevision] = writer
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, onAccepted -> lateSendAcceptance = onAccepted },
                        initialDraft = draft,
                        draftKey = ownerKey,
                        draftAccountRef = ACCOUNT,
                        draftGroupIdHex = GROUP,
                        onDraftChange = writer,
                        textState = editor,
                    )
                }
            }
        }

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
