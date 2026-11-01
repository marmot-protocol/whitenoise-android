package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.composerDraftOwnerKey
import dev.ipf.whitenoise.android.ui.conversation.composer.conversationDictationDraftWriter
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerDictationAction
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState

/** The production composer stays editable after real controller recovery through controlled platform ports. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroDictationDraftPresentation(fixture: MaestroPresentationFixture) {
    val state = remember { MaestroDictationDraftController() }
    var recovered by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val retained =
        when (fixture.scenario) {
            "dictation-draft-retained-send",
            "dictation-draft-retained-expanded-send",
            "dictation-draft-fresh-dictate",
            "dictation-draft-other-origin",
            -> true
            "dictation-draft-send",
            "dictation-draft-hardware-enter-send",
            "dictation-draft-edit-send",
            "dictation-draft-late-writer",
            "dictation-draft-delayed-acceptance",
            -> false
            else -> error("Unregistered recovered composer scenario")
        }
    DisposableEffect(state) { onDispose { state.close() } }
    Column(Modifier.fillMaxSize()) {
        if (!recovered) {
            Button(onClick = {
                state.recover(retained)
                fixture.record("draft-recovered")
                recovered = true
            }) { Text("Produce failed dictation result") }
        } else if (fixture.scenario.endsWith("late-writer")) {
            Button(onClick = {
                state.rejectOldWriter(MaestroDictationDraftController.EDITED)
                fixture.finish("stale-writer-rejected")
            }) { Text("Deliver old draft callback") }
        } else if (fixture.scenario.endsWith("fresh-dictate")) {
            Button(onClick = {
                state.verifyFreshTake()
                fixture.finish("fresh-take-verified")
            }) { Text("Check fresh dictation ownership") }
        } else if (fixture.scenario == "dictation-draft-other-origin") {
            MaestroDictationOriginControls(fixture, state)
        }
        Spacer(Modifier.weight(1f))
        MaestroRecoveredComposer(fixture, state, retained, sent, { sent = true })
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MaestroRecoveredComposer(
    fixture: MaestroPresentationFixture,
    state: MaestroDictationDraftController,
    retained: Boolean,
    sent: Boolean,
    onSent: () -> Unit,
) {
    val geometry = remember { MaestroDictationComposerGeometry() }
    val expandedCase = fixture.scenario == "dictation-draft-retained-expanded-send"
    if (expandedCase) MaestroDictationGeometryControls(fixture, geometry)
    val owner = composerDraftOwnerKey(MaestroDictationDraftController.ACCOUNT, state.visibleGroup)
    val revision =
        state.controller.completionRevision(
            MaestroDictationDraftController.ACCOUNT,
            state.visibleGroup,
        )
    val editor = rememberComposerTextState(owner, state.draft, 0 to revision)
    if (sent) MaestroDictationAcceptanceCheck(fixture, state, editor)
    val writer =
        conversationDictationDraftWriter(
            state.controller,
            MaestroDictationDraftController.ACCOUNT,
            state.visibleGroup,
            revision,
            { value, textChanged -> state.edit(value, textChanged) },
        )
    SideEffect { if (revision == 0 && state.initialWriter == null) state.initialWriter = writer }
    RememberMaestroPreviousDictate(state)
    ComposerBar(
        replyingTo = null,
        messageTextCopy = MessageTextCopy.Default,
        onCancelReply = {},
        onTimelineComposerMeasured = { foregroundHeight, _ -> geometry.height = foregroundHeight },
        onSend = { text, accepted ->
            if (expandedCase) check(geometry.expandedVerified) { "Expanded send needs actual geometry proof" }
            val expected =
                if (fixture.scenario.endsWith("edit-send")) {
                    MaestroDictationDraftController.EDITED
                } else {
                    MaestroDictationDraftController.RECOVERED
                }
            state.verifySend(text, expected, retained)
            check(!sent) { "Repeated ordinary send" }
            if (fixture.scenario == "dictation-draft-delayed-acceptance") {
                state.delayedAcceptance = accepted
            } else {
                accepted()
            }
            fixture.record("send-handoff")
            onSent()
        },
        initialDraft = state.draft,
        draftKey = owner,
        draftAccountRef = MaestroDictationDraftController.ACCOUNT,
        draftGroupIdHex = state.visibleGroup,
        onDraftChange = writer,
        textState = editor,
        dictationController = state.controller,
        dictationAccountRef = MaestroDictationDraftController.ACCOUNT,
        dictationGroupIdHex = state.visibleGroup,
    )
}

@Composable
@Suppress("FunctionNaming")
private fun MaestroDictationOriginControls(
    fixture: MaestroPresentationFixture,
    state: MaestroDictationDraftController,
) {
    if (state.visibleGroup == MaestroDictationDraftController.GROUP) {
        Button(onClick = {
            state.visibleGroup = MaestroDictationDraftController.OTHER
            fixture.record("origin-switched")
        }) { Text("Change displayed conversation") }
    } else {
        Text("Other conversation")
        Button(onClick = {
            state.verifyPreviousGesture()
            fixture.record("stale-gesture-rejected")
        }) { Text("Deliver previous Dictate gesture") }
        Button(onClick = {
            state.verifyOtherOrigin()
            fixture.finish("other-origin-verified")
        }) { Text("Check other conversation ownership") }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MaestroDictationAcceptanceCheck(
    fixture: MaestroPresentationFixture,
    state: MaestroDictationDraftController,
    editor: ComposerTextState,
) {
    Button(onClick = {
        if (fixture.scenario == "dictation-draft-delayed-acceptance") {
            check(editor.valueState.value.text == MaestroDictationDraftController.EDITED)
            val version = state.revision
            val accepted = checkNotNull(state.delayedAcceptance)
            accepted()
            check(editor.valueState.value.text == MaestroDictationDraftController.EDITED)
            check(state.draft.text == MaestroDictationDraftController.EDITED && state.revision == version)
            fixture.finish("stale-acceptance-rejected")
        } else {
            val acceptedText = editor.valueState.value.text
            check(acceptedText.isEmpty()) { "Accepted send did not clear the mounted editor" }
            fixture.finish("cleared-editor-verified")
        }
    }) {
        val label =
            if (fixture.scenario == "dictation-draft-delayed-acceptance") {
                "Accept old send after editing"
            } else {
                "Check cleared editor"
            }
        Text(label)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun RememberMaestroPreviousDictate(state: MaestroDictationDraftController) {
    val dictate =
        rememberComposerDictationAction(
            state.controller,
            MaestroDictationDraftController.ACCOUNT,
            state.visibleGroup,
            state.draft,
            null,
        )
    SideEffect {
        if (state.keptAudio &&
            state.visibleGroup == MaestroDictationDraftController.GROUP &&
            state.previousDictate == null
        ) {
            state.previousDictate = dictate
        }
    }
}
