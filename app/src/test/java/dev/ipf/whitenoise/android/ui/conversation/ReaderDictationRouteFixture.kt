package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTargetValidation
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_FULL_SCREEN_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.util.ReflectionHelpers
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener as RecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

/** Uses the production draft and mounted send route with synthetic recognition and target validation. */
internal class ReaderDictationRouteFixture(
    private val rule: ComposeContentTestRule,
    conversation: ConversationController,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val platform = ReaderDictationPlatform()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dictation: ConversationDictationController

    init {
        val appState = conversation.appState
        appState.attachConversationController(conversation)
        dictation =
            ConversationDictationController(
                platform = platform,
                readDraft = { account, group ->
                    ConversationDictationDraftSnapshot(
                        appState.draftStore.getDraft(account, group)?.textFieldValue ?: TextFieldValue(""),
                        appState.composerDraftGeneration(account, group),
                    )
                },
                writeDraft = { account, group, expected, value ->
                    if (appState.composerDraftGeneration(account, group) == expected) {
                        appState.setDraft(account, group, value)
                        appState.composerDraftGeneration(account, group)
                    } else {
                        null
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                targetValidationScope = scope,
                sendTranscriptIfOriginUnchanged = appState::sendDictationTranscriptIfOriginUnchanged,
            )
        ReflectionHelpers.setField(appState, "conversationDictationOwner", lazyOf(dictation))
    }

    fun assertSend(
        publishCount: () -> Int,
        awaitPublication: () -> Unit,
    ) {
        openReaderAndStartDictation()
        rule.runOnIdle { assertTrue(dictation.state is ConversationDictationState.Starting) }
        rule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        rule.runOnIdle { platform.listener.onResult("Dictation from Read More") }
        awaitPublication()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(
                "the real transport must publish the dictation acceptance revision",
                1,
                dictation.pendingSendRevision(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    ConversationTimelineTestIds.GROUP_ID,
                ),
            )
        }
        rule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).assertDoesNotExist()
        rule.onNodeWithText("Dictation from Read More").assertIsDisplayed()
        assertEquals(1, publishCount())
    }

    fun assertPaste(publishCount: () -> Int) {
        openReaderAndStartDictation()
        rule.onNodeWithContentDescription(context.getString(R.string.paste_dictation_transcript)).performClick()
        rule.runOnIdle { platform.listener.onResult("Paste-only reader draft") }
        rule.waitForIdle()
        rule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).assertIsDisplayed()
        rule.onNodeWithText("Paste-only reader draft").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, publishCount())
            assertEquals(
                0,
                dictation.pendingSendRevision(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    ConversationTimelineTestIds.GROUP_ID,
                ),
            )
        }
    }

    private fun openReaderAndStartDictation() {
        rule.waitForIdle()
        val readMoreId =
            rule
                .onAllNodesWithText(context.getString(R.string.message_read_more))
                .fetchSemanticsNodes()
                .first { it.boundsInRoot.width > 0f }
                .id
        rule.onNode(SemanticsMatcher("visible Read More") { it.id == readMoreId }).performClick()
        rule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.attach_options)).performClick()
        rule.onNodeWithText(context.getString(R.string.dictate_text)).performClick()
    }

    fun close() = scope.cancel()
}

private class ReaderDictationPlatform : ConversationDictationPlatform {
    lateinit var listener: RecognitionListener

    override fun hasRecordAudioPermission() = true

    override fun recognitionAvailable() = true

    override fun createSession(listener: RecognitionListener): RecognitionSession {
        this.listener = listener
        return object : RecognitionSession {
            override fun start() = Unit

            override fun stop() = Unit

            override fun cancel() = Unit

            override fun destroy() = Unit
        }
    }
}
