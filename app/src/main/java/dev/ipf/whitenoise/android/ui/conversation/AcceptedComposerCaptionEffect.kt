package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState

/** Clears the matching text in a reopened editor without replacing the mounted editor or its active send flight. */
@Composable
@Suppress("FunctionNaming")
internal fun ObserveAcceptedComposerCaption(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    accountRef: String?,
    textState: ComposerTextState,
) {
    val account = accountRef ?: return
    val group = controller.group.groupIdHex
    val accepted = appState.composerDraftExpansionBridge.sentPresentation.acceptedCaption(account, group)
    val editingMessage = controller.editingMessageId
    val preEdit = textState.preEditState.value
    LaunchedEffect(textState, accepted, editingMessage, preEdit) {
        val ownerIsCurrent = appState.activeAccountRef == account && controller.boundAccountRef == account
        if (accepted != null &&
            ownerIsCurrent &&
            appState.messageDraftRepository.coordinated.isTextCurrent(account, group, accepted.generation)
        ) {
            textState.clearReopenedAcceptedCaption(accepted.text, controller.editingMessageId != null)
        }
    }
}

/** Blanks only the owning draft field, leaving any active message edit or already-cleared editor alone. */
internal fun ComposerTextState.clearReopenedAcceptedCaption(
    sentText: String,
    editingMessage: Boolean,
) {
    val preEdit = preEditState.value
    if (preEdit != null) {
        if (preEdit.text == sentText) preEditState.value = TextFieldValue("")
    } else if (!editingMessage && valueState.value.text == sentText) {
        clearAccepted(acceptanceToken())
    }
}
