package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchNavBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerAttachmentSheetState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.DisbandedGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.FrozenGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice

private val ConversationTopInteractionClearance = 64.dp

@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ConversationBottomBar(
    // Compact-height windows shrink the top bar, so the full-screen composer
    // reserves correspondingly less clearance below it.
    compactHeight: Boolean = false,
    selectionMode: Boolean,
    selectionActionAvailability: BatchSelectionActionAvailability,
    selectionForwardBlockedReason: ForwardBlockedReason?,
    onCopySelection: () -> Unit,
    onForwardSelection: () -> Unit,
    onSaveSelection: () -> Unit,
    onReplySelection: () -> Unit,
    onInfoSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    batchDeleteRetryState: BatchDeleteRetryState?,
    batchDeleteInFlight: Boolean,
    onRetryBatchDelete: () -> Unit,
    onDismissBatchDeleteFailure: () -> Unit,
    onCopyBatchDeleteReport: () -> Unit,
    searchOpen: Boolean,
    searchMatchCount: Int,
    searchActiveIndex: Int,
    hasSearchQuery: Boolean,
    onPreviousSearchMatch: () -> Unit,
    onNextSearchMatch: () -> Unit,
    hasError: Boolean,
    composerGate: ComposerGate,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    messageTextCopy: MessageTextCopy,
    onBack: () -> Unit,
    initialDraft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    composerTextState: ComposerTextState,
    composerAttachmentSheet: ComposerAttachmentSheetState,
    onAfterSend: () -> Unit,
    onPickFromGallery: () -> Unit,
    onPickRecentMedia: (Uri) -> Unit,
    onCaptureFromCamera: () -> Unit,
    onPickDocument: () -> Unit,
    onShareLocation: () -> Unit,
    onShareUser: () -> Unit,
    onShareContact: () -> Unit,
    onPasteImageUris: (List<Uri>) -> Unit,
    voiceRecordingController: VoiceRecordingController,
    mentionCandidates: List<MentionComposer.Candidate>,
    mentionPickerEnabled: Boolean,
    autoFocusOnEnter: Boolean,
    autoFocusOnDraftRestore: Boolean,
    autoFocusConsumedState: MutableState<Boolean>,
    composerFocus: FocusRequester,
    onComposerFocusChanged: (Boolean) -> Unit,
    onComposerPreImeBack: () -> Unit,
    onBottomInputChanged: () -> Unit,
    onKeyboardRestoreFromCustomInput: () -> Unit,
    onKeyboardRestoreFromCustomInputFailed: () -> Unit,
    recentEmojis: List<String>,
    onEmojiUsed: (String) -> Unit,
    onBottomChromeMeasured: (heightPx: Int, chromeBottomPx: Int) -> Unit,
) {
    val chromeInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                onBottomChromeMeasured(size.height, chromeInsets.getBottom(density))
            },
    ) {
        when {
            selectionMode ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    batchDeleteRetryState?.let { retryState ->
                        BatchDeleteFailureNotice(
                            state = retryState,
                            retryInFlight = batchDeleteInFlight,
                            onRetry = onRetryBatchDelete,
                            onDismiss = onDismissBatchDeleteFailure,
                            onCopyReport = onCopyBatchDeleteReport,
                        )
                    }
                    MessageSelectionBottomBar(
                        availability = selectionActionAvailability,
                        forwardBlockedReason = selectionForwardBlockedReason,
                        onCopy = onCopySelection,
                        onForward = onForwardSelection,
                        onSave = onSaveSelection,
                        onReply = onReplySelection,
                        onInfo = onInfoSelection,
                        onDelete = onDeleteSelection,
                    )
                }
            searchOpen ->
                ConversationSearchNavBar(
                    matchCount = searchMatchCount,
                    activeIndex = searchActiveIndex,
                    hasQuery = hasSearchQuery,
                    onPrev = onPreviousSearchMatch,
                    onNext = onNextSearchMatch,
                )
            hasError -> Unit
            else ->
                when (composerGate) {
                    ComposerGate.PENDING ->
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .height(64.dp),
                        )
                    ComposerGate.NOTICE -> RemovedMemberComposerNotice()
                    ComposerGate.FROZEN -> FrozenGroupComposerNotice()
                    ComposerGate.DISBANDED ->
                        DisbandedGroupComposerNotice(disbanded = controller.group.disbanded)
                    ComposerGate.INVITE ->
                        InvitePreviewActionBar(
                            mutationInFlight = controller.mutationInFlight,
                            onJoin = { appState.launchMutation { controller.acceptInvite() } },
                            onDecline = {
                                appState.launchMutation {
                                    if (controller.declineInvite()) onBack()
                                }
                            },
                        )
                    ComposerGate.COMPOSER -> {
                        val editingRecord =
                            controller.editingMessageId?.let { id ->
                                controller.timeline.firstOrNull { it.record.messageIdHex == id }?.record
                            }
                        ComposerBar(
                            replyingTo = controller.replyingTo,
                            replyingToMedia =
                                controller.replyingTo
                                    ?.let(controller::mediaReferencesFor)
                                    .orEmpty(),
                            replyingToDisplay =
                                controller.replyingTo
                                    ?.let { controller.replyTargetPreview(it, messageTextCopy) },
                            messageTextCopy = messageTextCopy,
                            onCancelReply = { controller.replyingTo = null },
                            onSend = { text, onAccepted ->
                                appState.launchMutation {
                                    appState.sendConversationText(controller, text, onAccepted)
                                }
                            },
                            initialDraft = initialDraft,
                            onDraftChange = onDraftChange,
                            draftKey = controller.group.groupIdHex,
                            textState = composerTextState,
                            attachmentSheetState = composerAttachmentSheet,
                            editingMessageId = controller.editingMessageId,
                            editingInitialText = editingRecord?.let { controller.displayedText(it) },
                            onCancelEdit = { controller.editingMessageId = null },
                            onAfterSend = onAfterSend,
                            onPickFromGallery = onPickFromGallery,
                            onPickRecentMedia = onPickRecentMedia,
                            onCaptureFromCamera = onCaptureFromCamera,
                            onPickDocument = onPickDocument,
                            onShareLocation = onShareLocation,
                            onShareUser = onShareUser,
                            onShareContact = onShareContact,
                            onPasteImageUris = onPasteImageUris,
                            voiceRecordingController = voiceRecordingController,
                            dictationController = appState.conversationDictation,
                            dictationAccountRef = appState.activeAccountRef,
                            dictationGroupIdHex = controller.group.groupIdHex,
                            appState = appState,
                            mentionCandidates = mentionCandidates,
                            mentionPickerEnabled = mentionPickerEnabled,
                            autoFocusOnEnter = autoFocusOnEnter,
                            autoFocusOnDraftRestore = autoFocusOnDraftRestore,
                            autoFocusConsumedState = autoFocusConsumedState,
                            enterKeyBehavior = appState.enterKeyBehavior,
                            composerFocus = composerFocus,
                            onComposerFocusChanged = onComposerFocusChanged,
                            onComposerPreImeBack = onComposerPreImeBack,
                            onBottomInputChanged = onBottomInputChanged,
                            onKeyboardRestoreFromCustomInput = onKeyboardRestoreFromCustomInput,
                            onKeyboardRestoreFromCustomInputFailed = onKeyboardRestoreFromCustomInputFailed,
                            recentEmojis = recentEmojis,
                            onEmojiUsed = onEmojiUsed,
                            topInteractionClearance =
                                if (compactHeight) {
                                    compactTopClearanceFor(LocalDensity.current.fontScale)
                                } else {
                                    ConversationTopInteractionClearance
                                },
                        )
                    }
                }
        }
    }
}
