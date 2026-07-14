package dev.ipf.whitenoise.android.ui.conversation.composer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.replyMediaKindFromMime
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

/**
 * Whether the composer bottom cluster (reply preview, edit banner, mention
 * picker, input row, and inline emoji/attachment panes) should apply
 * [imePadding]. Suppressed while the emoji pane owns the bottom region so the
 * keyboard/emoji swap does not double-count insets (#808, #895, #1109), but
 * restored while the emoji search field is active so the IME cannot cover the
 * filtered results grid (#1222). The attachment sheet owns the bottom region
 * the same way the emoji pane does, and has no in-pane text input.
 */
internal fun composerBottomClusterAppliesImePadding(
    showEmojiPane: Boolean,
    composerEmojiSearchActive: Boolean,
    showAttachmentPane: Boolean = false,
): Boolean = (!showEmojiPane || composerEmojiSearchActive) && !showAttachmentPane

internal fun composerBottomClusterModifier(
    showEmojiPane: Boolean,
    composerEmojiSearchActive: Boolean,
    base: Modifier = Modifier,
    showAttachmentPane: Boolean = false,
): Modifier {
    val withNav = base.navigationBarsPadding()
    return if (composerBottomClusterAppliesImePadding(showEmojiPane, composerEmojiSearchActive, showAttachmentPane)) {
        withNav.imePadding()
    } else {
        withNav
    }
}

/**
 * While the attachment pane replaces an animating IME, reserve at least the
 * IME's live height. The pane then follows the system inset down in one smooth
 * handoff and naturally stops shrinking when its own content becomes taller.
 */
internal fun composerAttachmentPaneMinimumHeight(
    showAttachmentPane: Boolean,
    currentImeHeight: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp = if (showAttachmentPane) currentImeHeight else 0.dp

/**
 * A focus request emits its own focus callback. Ignore that callback while a
 * pane-to-IME handoff is already running so it cannot start a second keyboard
 * request and a second transcript re-anchor.
 */
internal fun shouldStartComposerKeyboardRestore(
    paneOpen: Boolean,
    keyboardRestorePending: Boolean,
): Boolean = paneOpen && !keyboardRestorePending

/**
 * Starting a reply grows the bottom input cluster by inserting the preview card.
 * Re-anchor only on the null -> non-null edge; recompositions while the same
 * reply is active must not keep stealing scroll while the user types (#1109).
 */
internal fun shouldReanchorBottomInputForReplyTargetChange(
    hadReplyTarget: Boolean,
    hasReplyTarget: Boolean,
): Boolean = hasReplyTarget && !hadReplyTarget

@Composable
internal fun RemovedMemberComposerNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Text(
            // Explains the disabled composer for a member who left or was
            // removed; the timeline system row carries the left-vs-removed
            // distinction on its own.
            text = stringResource(R.string.you_are_no_longer_a_member),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Inserts an emoji at the composer's current selection, replacing any selected
 * range and moving the caret just after the inserted glyph. Kept pure so the
 * cursor math is pinned by local unit tests instead of only by Compose wiring.
 */
internal fun insertComposerEmoji(
    value: TextFieldValue,
    emoji: String,
): TextFieldValue {
    val text = value.text
    val start =
        minOf(value.selection.start, value.selection.end)
            .coerceIn(0, text.length)
    val end =
        maxOf(value.selection.start, value.selection.end)
            .coerceIn(start, text.length)
    val updatedText =
        buildString {
            append(text, 0, start)
            append(emoji)
            append(text, end, text.length)
        }
    val caret = start + emoji.length
    return value.copy(text = updatedText, selection = TextRange(caret), composition = null)
}

/**
 * Hoisted composer text state (#1206). Sharing one instance across the main
 * composer and the long-message reader's composer keeps their in-progress text
 * and edit-restore state from drifting — both `ComposerBar`s delegate their
 * `textFieldValue`/`preEditFieldValue` to the same backing [MutableState].
 */
@Stable
internal class ComposerTextState(
    initial: TextFieldValue,
) {
    val valueState: MutableState<TextFieldValue> = mutableStateOf(initial)
    val preEditState: MutableState<TextFieldValue?> = mutableStateOf(null)
}

@Composable
internal fun rememberComposerTextState(
    draftKey: Any?,
    initialDraft: String,
): ComposerTextState = remember(draftKey) { ComposerTextState(TextFieldValue(initialDraft)) }

@Composable
internal fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    messageTextCopy: MessageTextCopy,
    onCancelReply: () -> Unit,
    onSend: (text: String, onAccepted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: String = "",
    onDraftChange: (String) -> Unit = {},
    draftKey: Any? = null,
    onAfterSend: () -> Unit = {},
    onPickFromGallery: (() -> Unit)? = null,
    onPickRecentMedia: ((Uri) -> Unit)? = null,
    onCaptureFromCamera: (() -> Unit)? = null,
    onPickDocument: (() -> Unit)? = null,
    onShareLocation: (() -> Unit)? = null,
    onShareUser: (() -> Unit)? = null,
    onShareContact: (() -> Unit)? = null,
    onPasteImageUris: ((List<Uri>) -> Unit)? = null,
    voiceRecordingController: dev.ipf.whitenoise.android.audio.VoiceRecordingController? = null,
    editingMessageId: String? = null,
    editingInitialText: String? = null,
    onCancelEdit: () -> Unit = {},
    appState: WhiteNoiseAppState? = null,
    // Group @-mention picker (#414): candidates the picker filters over, and
    // whether this conversation is a group (the picker is suppressed in DMs —
    // a 1:1 chat has no one to disambiguate, so typing `@` stays literal).
    mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
    mentionPickerEnabled: Boolean = false,
    // When the conversation was just created in the same navigation step
    // (issue #321), request focus on the composer and raise the soft keyboard
    // once on entry so the user can type the first message without an extra
    // tap. One-shot: a guard flag stops a revisit / recomposition from
    // re-opening the IME, and the flag is not persisted across process death.
    autoFocusOnEnter: Boolean = false,
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
    // #589: the composer FocusRequester is hoisted from the conversation screen
    // so its resume lifecycle observer can restore focus after an app-switch.
    // Defaulted to a locally-remembered requester so other call sites keep the
    // previous self-contained behavior.
    composerFocus: FocusRequester = remember { FocusRequester() },
    // #589: surfaces the live focus state up to the conversation screen so the
    // resume observer can tell whether the keyboard was up when we were paused.
    onComposerFocusChanged: (Boolean) -> Unit = {},
    onBottomInputChanged: () -> Unit = {},
    onKeyboardRestoreFromCustomInput: () -> Unit = {},
    onKeyboardRestoreFromCustomInputFailed: () -> Unit = {},
    // #1206: shared so the long-message reader's composer and the main composer
    // don't keep divergent text/edit state. Defaults to a private per-instance
    // state, preserving standalone behavior for any other caller.
    textState: ComposerTextState = rememberComposerTextState(draftKey, initialDraft),
    // Hoisted so the conversation screen can dismiss the sheet on an outside
    // tap; defaults to a private instance for other call sites.
    attachmentSheetState: ComposerAttachmentSheetState = rememberComposerAttachmentSheetState(),
) {
    var composerEmojiPickerOpen by remember { mutableStateOf(false) }
    var composerEmojiSearchActive by remember { mutableStateOf(false) }
    var composerKeyboardRestorePending by remember { mutableStateOf(false) }
    // Field state is a TextFieldValue (not a bare String) so the caret can
    // be positioned at the end of the prefilled body on edit-entry, and so
    // a re-tap on a different message rebases the caret too. Keyed on
    // draftKey so switching to a different chat re-hydrates the text field
    // from that chat's saved draft rather than carrying state across.
    var textFieldValue by textState.valueState
    val text = textFieldValue.text
    // Snapshot the in-flight composer state (full TextFieldValue — text +
    // caret) when entering edit mode so cancelling restores both. Keyed on
    // the message id so a tap-Edit on a different message snapshots a fresh
    // baseline.
    var preEditFieldValue by textState.preEditState
    // Claim focus on edit-entry so the IME opens with the caret at the end
    // of the prefill, without making the user tap the field a second time.
    // `composerFocus` is now hoisted in via a parameter (#589) so the
    // conversation screen's resume observer can drive focus too.
    // Keyed on editingMessageId only: prefill once when an edit session starts,
    // not on every reprojection of editingInitialText — otherwise a background
    // timeline update would overwrite the user's in-progress edit.
    LaunchedEffect(editingMessageId) {
        if (editingMessageId != null) {
            // Save the in-flight composer once per edit session, then push
            // the message's current text into the input so the user edits
            // from where it stands today (which is the latest applied edit
            // if there's already an edit chain). Selection at `length` lands
            // the caret past the last character — same caret model as a
            // long-press-to-edit on every other modern chat composer.
            if (preEditFieldValue == null) preEditFieldValue = textFieldValue
            val prefill = editingInitialText.orEmpty()
            textFieldValue = TextFieldValue(text = prefill, selection = TextRange(prefill.length))
            onBottomInputChanged()
            runCatching { composerFocus.requestFocus() }
        } else if (preEditFieldValue != null) {
            // Edit cancelled or submitted: restore the draft the user had
            // been composing before they tapped Edit (text + original caret).
            textFieldValue = preEditFieldValue ?: TextFieldValue("")
            preEditFieldValue = null
        }
    }
    // #321: a just-created conversation opens directly with the composer ready.
    // Request focus and raise the soft keyboard exactly once, gated by a
    // plain-`remember` flag (NOT rememberSaveable) so it fires per composition
    // and never re-fires on a revisit or after process death. Skipped while
    // editing — the edit effect above already owns focus then.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    val currentImePaneHeight =
        with(density) {
            (imeInsets.getBottom(this) - navigationInsets.getBottom(this))
                .coerceAtLeast(0)
                .toDp()
        }
    var rememberedImePaneHeight by remember(configuration.orientation) { mutableStateOf(0.dp) }
    var lockedComposerEmojiPaneHeight by remember(configuration.orientation) { mutableStateOf(0.dp) }
    var lockedComposerAttachmentPaneHeight by remember(configuration.orientation) { mutableStateOf(0.dp) }
    LaunchedEffect(currentImePaneHeight, composerEmojiPickerOpen, attachmentSheetState.isOpen) {
        rememberedImePaneHeight =
            updatedComposerRememberedImeHeight(
                previousRememberedImeHeight = rememberedImePaneHeight,
                currentImeHeight = currentImePaneHeight,
                freezeUpdates = composerEmojiPickerOpen || attachmentSheetState.isOpen,
            )
    }
    val emojiPaneBaseHeight =
        composerEmojiPaneHeight(
            lockedPaneHeight = lockedComposerEmojiPaneHeight,
            currentImeHeight = currentImePaneHeight,
            rememberedImeHeight = rememberedImePaneHeight,
        )
    val emojiPaneHeight =
        if (composerEmojiSearchActive) {
            emojiPaneBaseHeight + ComposerEmojiPickerSearchExtraHeight
        } else {
            emojiPaneBaseHeight
        }
    // Keep exactly one opaque owner of the bottom region during the IME swap.
    // Fading this pane while the IME animates underneath exposes both surfaces
    // for several frames and looks like a duplicated, blinking composer.
    val showEmojiPane = composerEmojiPickerOpen
    val latestImePaneHeight by rememberUpdatedState(currentImePaneHeight)
    LaunchedEffect(showEmojiPane) {
        if (!showEmojiPane) {
            lockedComposerEmojiPaneHeight = 0.dp
            composerEmojiSearchActive = false
        }
    }
    // Attachment sheet: shares the emoji pane's IME-height model so opening
    // either surface swaps seamlessly with the keyboard and with each other.
    val attachmentPaneHeight =
        composerEmojiPaneHeight(
            lockedPaneHeight = lockedComposerAttachmentPaneHeight,
            currentImeHeight = currentImePaneHeight,
            rememberedImeHeight = rememberedImePaneHeight,
        )
    val attachmentPaneAlpha by animateFloatAsState(
        targetValue = if (attachmentSheetState.isOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "composerAttachmentPaneAlpha",
    )
    val showAttachmentPane = attachmentSheetState.isOpen || attachmentPaneAlpha > 0.01f
    val attachmentPaneMinimumHeight =
        composerAttachmentPaneMinimumHeight(
            showAttachmentPane = showAttachmentPane,
            currentImeHeight = currentImePaneHeight,
        )
    LaunchedEffect(showAttachmentPane) {
        if (!showAttachmentPane) lockedComposerAttachmentPaneHeight = 0.dp
    }
    // The sheet state is hoisted; make sure it never stays open once this
    // composer leaves composition (search or selection mode swaps the bar).
    DisposableEffect(attachmentSheetState) {
        onDispose { attachmentSheetState.dismiss() }
    }

    fun restoreKeyboardFromEmojiPane() {
        if (!shouldStartComposerKeyboardRestore(composerEmojiPickerOpen, composerKeyboardRestorePending)) return
        if (lockedComposerEmojiPaneHeight == 0.dp) {
            lockedComposerEmojiPaneHeight =
                composerEmojiPaneTargetHeight(
                    currentImeHeight = currentImePaneHeight,
                    rememberedImeHeight = rememberedImePaneHeight,
                )
        }
        composerEmojiSearchActive = false
        composerKeyboardRestorePending = true
        onKeyboardRestoreFromCustomInput()
        runCatching { composerFocus.requestFocus() }
        keyboardController?.show()
    }

    LaunchedEffect(composerKeyboardRestorePending, currentImePaneHeight, emojiPaneHeight, attachmentPaneHeight) {
        val targetPaneHeight = if (attachmentSheetState.isOpen) attachmentPaneHeight else emojiPaneHeight
        if (
            shouldSwapComposerEmojiPaneToIme(
                keyboardRestorePending = composerKeyboardRestorePending,
                currentImeHeight = currentImePaneHeight,
                targetImeHeight = targetPaneHeight,
            )
        ) {
            composerKeyboardRestorePending = false
            composerEmojiPickerOpen = false
            attachmentSheetState.dismiss()
        }
    }

    LaunchedEffect(composerKeyboardRestorePending) {
        if (composerKeyboardRestorePending) {
            delay(600L)
            if (composerKeyboardRestorePending) {
                composerKeyboardRestorePending = false
                if (latestImePaneHeight > 0.dp) {
                    // Some IMEs settle a few pixels below the reserved pane
                    // height. Never leave the custom pane covering a keyboard
                    // that is already visible just because the insets differ.
                    composerEmojiPickerOpen = false
                    attachmentSheetState.dismiss()
                } else {
                    onKeyboardRestoreFromCustomInputFailed()
                    focusManager.clearFocus(force = true)
                }
            }
        }
    }

    BackHandler(enabled = composerEmojiPickerOpen || attachmentSheetState.isOpen) {
        composerKeyboardRestorePending = false
        composerEmojiPickerOpen = false
        attachmentSheetState.dismiss()
    }
    var autoFocusConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocusOnEnter, editingMessageId) {
        if (autoFocusOnEnter && !autoFocusConsumed && editingMessageId == null) {
            autoFocusConsumed = true
            runCatching { composerFocus.requestFocus() }
            keyboardController?.show()
        }
    }
    // Starting a reply (swipe-to-reply or long-press → Reply both set the
    // controller's replyingTo) focuses the composer and raises the IME. Fire
    // only on the null → non-null edge so a recomposition mid-reply doesn't
    // re-toggle the keyboard while the user is already typing.
    var hadReplyTarget by remember { mutableStateOf(replyingTo != null) }
    LaunchedEffect(replyingTo) {
        val hasReplyTarget = replyingTo != null
        if (shouldReanchorBottomInputForReplyTargetChange(hadReplyTarget, hasReplyTarget)) {
            onBottomInputChanged()
            runCatching { composerFocus.requestFocus() }
            keyboardController?.show()
        }
        hadReplyTarget = hasReplyTarget
    }
    // Single send path shared by the FAB and the Enter key (#404). Clears the
    // input/draft and scroll-to-newest ONLY after the controller confirms the
    // optimistic bubble is committed (it invokes onAccepted then). If a guard
    // rejects the send the callback never runs, so the user's text stays in
    // the field instead of vanishing silently (issue #264). For an in-place
    // edit the controller short-circuits and never calls onAccepted; the
    // LaunchedEffect that watches `editingMessageId` restores the pre-edit
    // composer once edit state clears — so we pass a no-op and don't blank
    // the field here.
    val submitMessage: () -> Unit = {
        if (text.isNotBlank()) {
            val sendingEdit = editingMessageId != null
            val sentText = text
            onSend(sentText) {
                if (!sendingEdit) {
                    // onAccepted can land after the user has started typing the
                    // next message (Enter-to-send makes that common). Only clear
                    // if the field still holds exactly what we sent, so newly
                    // typed text is never wiped.
                    if (textFieldValue.text == sentText) {
                        textFieldValue = TextFieldValue("")
                        onDraftChange("")
                    }
                    onAfterSend()
                }
            }
        }
    }

    fun applyComposerFieldValue(value: TextFieldValue) {
        textFieldValue = value
        if (editingMessageId == null) onDraftChange(value.text)
    }

    fun deleteFromComposer() {
        val selection = textFieldValue.selection
        val textValue = textFieldValue.text
        val deleteStart =
            when {
                selection.start != selection.end -> minOf(selection.start, selection.end)
                selection.start <= 0 -> return
                else -> textValue.offsetByCodePoints(selection.start, -1)
            }
        val deleteEnd =
            if (selection.start != selection.end) {
                maxOf(selection.start, selection.end)
            } else {
                selection.start
            }
        val updatedText = textValue.removeRange(deleteStart, deleteEnd)
        applyComposerFieldValue(TextFieldValue(updatedText, selection = TextRange(deleteStart)))
    }

    fun openComposerEmojiPane() {
        attachmentSheetState.dismiss()
        composerKeyboardRestorePending = false
        composerEmojiSearchActive = false
        lockedComposerEmojiPaneHeight =
            composerEmojiPaneTargetHeight(
                currentImeHeight = currentImePaneHeight,
                rememberedImeHeight = rememberedImePaneHeight,
            )
        composerEmojiPickerOpen = true
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun showKeyboardFromEmojiPane() {
        attachmentSheetState.dismiss()
        restoreKeyboardFromEmojiPane()
    }

    fun openComposerAttachmentSheet() {
        composerKeyboardRestorePending = false
        composerEmojiPickerOpen = false
        lockedComposerAttachmentPaneHeight =
            composerEmojiPaneTargetHeight(
                currentImeHeight = currentImePaneHeight,
                rememberedImeHeight = rememberedImePaneHeight,
            )
        attachmentSheetState.open()
        onBottomInputChanged()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun restoreKeyboardFromAttachmentSheet() {
        if (!shouldStartComposerKeyboardRestore(attachmentSheetState.isOpen, composerKeyboardRestorePending)) return
        if (lockedComposerAttachmentPaneHeight == 0.dp) {
            lockedComposerAttachmentPaneHeight =
                composerEmojiPaneTargetHeight(
                    currentImeHeight = currentImePaneHeight,
                    rememberedImeHeight = rememberedImePaneHeight,
                )
        }
        composerKeyboardRestorePending = true
        onBottomInputChanged()
        runCatching { composerFocus.requestFocus() }
        keyboardController?.show()
    }
    Column(
        composerBottomClusterModifier(showEmojiPane, composerEmojiSearchActive, modifier.fillMaxWidth(), showAttachmentPane),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editingMessageId != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.editing_message),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_edit), modifier = Modifier.size(18.dp))
                    }
                }
            } else if (replyingTo != null) {
                val refs = remember(replyingTo.tags) { MediaReferenceParser.parseAllImetaTags(replyingTo.tags) }
                val mediaKind = remember(refs) { replyMediaKindFromMime(refs.firstOrNull()?.mediaType) }
                ReplyPreviewCard(
                    senderTitle =
                        if (replyingTo.direction == "sent") {
                            stringResource(R.string.reply_you)
                        } else {
                            appState?.displayName(replyingTo.sender) ?: replyingTo.sender.take(8)
                        },
                    isOwn = replyingTo.direction == "sent",
                    body = MessageProjector.displayBody(replyingTo, messageTextCopy),
                    mediaKind = mediaKind,
                    onClick = null,
                    onDismiss = onCancelReply,
                    mentionDisplayName = appState?.let { state -> { state.mentionDisplayName(it) } },
                )
            }
            // #414: live @-mention picker. Compute the open query from the current
            // caret; suppressed entirely in DMs and while editing/recording or with
            // no roster. Anchored directly above the composer input row, capped at
            // ~50% of the viewport height.
            val mentionQuery =
                if (mentionPickerEnabled && editingMessageId == null) {
                    MentionComposer
                        .activeMentionQuery(textFieldValue.text, textFieldValue.selection.start)
                        .takeIf { textFieldValue.selection.collapsed }
                } else {
                    null
                }
            val mentionMatches =
                remember(mentionQuery?.query, mentionCandidates) {
                    if (mentionQuery == null) emptyList() else MentionComposer.filter(mentionQuery.query, mentionCandidates)
                }
            if (mentionQuery != null && mentionMatches.isNotEmpty()) {
                val openQuery = mentionQuery
                MentionPicker(
                    candidates = mentionMatches,
                    onPick = { candidate ->
                        val insertion = MentionComposer.insertMention(textFieldValue.text, openQuery, candidate)
                        val updated = TextFieldValue(text = insertion.text, selection = TextRange(insertion.selection))
                        textFieldValue = updated
                        if (editingMessageId == null) onDraftChange(updated.text)
                        runCatching { composerFocus.requestFocus() }
                        composerEmojiPickerOpen = false
                        attachmentSheetState.dismiss()
                    },
                )
            }
            val activeRecordingController = voiceRecordingController?.takeIf { it.isRecording }
            val isRecordingVoice = activeRecordingController != null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Keep the text field composed while recording. Removing the focused
                // BasicTextField makes Android dismiss the IME, which then removes
                // imePadding and drops this whole bottom bar under the user's finger.
                // The recording strip is only a visual overlay; focus stays with the
                // hidden composer so an already-open keyboard remains open.
                Box(modifier = Modifier.weight(1f)) {
                    ComposerPill(
                        textFieldValue = textFieldValue,
                        composerFocus = composerFocus,
                        emojiPickerOpen = composerEmojiPickerOpen,
                        onComposerFocusChanged = { focused ->
                            if (focused && composerEmojiPickerOpen) restoreKeyboardFromEmojiPane()
                            if (focused && attachmentSheetState.isOpen) restoreKeyboardFromAttachmentSheet()
                            onComposerFocusChanged(focused)
                        },
                        onValueChange = { value ->
                            if (!isRecordingVoice) {
                                // #414: a single Backspace at the right edge of an
                                // `@npub1…` chip (or just past its trailing space,
                                // the post-insert caret position) deletes the whole
                                // chip in one keypress, so a mention reads as one
                                // token. Falls through to the verbatim IME edit
                                // otherwise.
                                //
                                // #607: an IME swipe-to-delete (hold-backspace and
                                // swipe) fires per-char or multi-char deletes that
                                // can land *inside* a chip, chopping it into a
                                // truncated `@npub1…` run. A partial chip corrupts
                                // the npub reference and crashes the composer's
                                // chip renderer / offset mapping. repairChipDeletion
                                // detects any deletion that partially overlaps a
                                // chip and widens it to remove the whole chip, so a
                                // partial-chip state can never reach the renderer.
                                val whole =
                                    MentionComposer.wholeChipBackspace(
                                        oldText = textFieldValue.text,
                                        oldCaret = textFieldValue.selection.start,
                                        newText = value.text,
                                        newCaret = value.selection.start,
                                    )
                                        ?: MentionComposer.repairChipDeletion(
                                            oldText = textFieldValue.text,
                                            newText = value.text,
                                        )
                                val edited =
                                    if (whole != null) {
                                        TextFieldValue(text = whole.text, selection = TextRange(whole.selection))
                                    } else {
                                        value
                                    }
                                // #414: keep the caret/selection out of the interior
                                // of any `@npub1…` chip so a tap, drag, or arrow key
                                // can't land inside the token (which would let a
                                // stray edit corrupt it or reopen the picker
                                // mid-token). Only in groups, where chips exist.
                                val applied =
                                    if (mentionPickerEnabled) {
                                        val clamped =
                                            MentionComposer.clampSelectionOutOfChips(
                                                edited.text,
                                                edited.selection.start,
                                                edited.selection.end,
                                            )
                                        if (clamped.start != edited.selection.start ||
                                            clamped.end != edited.selection.end
                                        ) {
                                            edited.copy(selection = TextRange(clamped.start, clamped.end))
                                        } else {
                                            edited
                                        }
                                    } else {
                                        edited
                                    }
                                applyComposerFieldValue(applied)
                            }
                        },
                        onEmojiPickerToggle = {
                            if (composerEmojiPickerOpen) {
                                showKeyboardFromEmojiPane()
                            } else {
                                openComposerEmojiPane()
                            }
                        },
                        onAttachmentsToggle = {
                            if (attachmentSheetState.isOpen) {
                                attachmentSheetState.dismiss()
                            } else {
                                openComposerAttachmentSheet()
                            }
                        },
                        attachmentSheetOpen = attachmentSheetState.isOpen,
                        hasCameraCapture = onCaptureFromCamera != null,
                        hasLocationShare = onShareLocation != null,
                        hasUserShare = onShareUser != null,
                        hasContactShare = onShareContact != null,
                        onPickFromGallery = onPickFromGallery,
                        onPickDocument = onPickDocument,
                        onPasteImageUris = onPasteImageUris?.takeIf { editingMessageId == null && !isRecordingVoice },
                        // #414: tint inserted `@npub1…` chips so they read as a
                        // single styled token while composing. Only when the picker
                        // is enabled (groups) — DMs never insert chips.
                        highlightMentionChips = mentionPickerEnabled,
                        mentionCandidates = mentionCandidates,
                        enterKeyBehavior = enterKeyBehavior,
                        onImeSend = submitMessage,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (isRecordingVoice) 0f else 1f)
                                .then(if (isRecordingVoice) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    if (activeRecordingController != null) {
                        RecordingStripLeading(
                            controller = activeRecordingController,
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .pointerInput(activeRecordingController) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    },
                        )
                    }
                }
                // Trailing MicHoldButton call site below must stay shared by both
                // recording and non-recording states; separate call sites break the
                // pointer-gesture identity for the active hold gesture.
                val showMicButton =
                    (text.isBlank() || isRecordingVoice) &&
                        editingMessageId == null &&
                        voiceRecordingController != null
                if (showMicButton && voiceRecordingController?.locked == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { voiceRecordingController.cancel() },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.voice_message_cancel),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        FloatingActionButton(
                            onClick = { voiceRecordingController.stop() },
                            modifier = Modifier.size(44.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else if (showMicButton) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        LockHintAbove(controller = voiceRecordingController!!)
                        MicHoldButton(controller = voiceRecordingController)
                    }
                } else {
                    FloatingActionButton(
                        onClick = { submitMessage() },
                        modifier = Modifier.size(44.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (showEmojiPane || showAttachmentPane) {
            // Box, not stacked children — during the 120ms crossfade both panes
            // can be visible and stacking them would double the cluster height.
            Box(Modifier.fillMaxWidth()) {
                if (showEmojiPane) {
                    ComposerEmojiPickerPane(
                        height = emojiPaneHeight,
                        alpha = 1f,
                        onEmojiPicked = { emoji ->
                            val updated = insertComposerEmoji(textFieldValue, emoji)
                            applyComposerFieldValue(updated)
                        },
                        onBackspace = ::deleteFromComposer,
                        onSearchActiveChange = {
                            composerEmojiSearchActive = it
                            onBottomInputChanged()
                        },
                    )
                }
                if (showAttachmentPane) {
                    ComposerAttachmentSheetPane(
                        alpha = attachmentPaneAlpha,
                        minimumHeight = attachmentPaneMinimumHeight,
                        onPickRecentMedia =
                            onPickRecentMedia?.let { pick ->
                                { uri ->
                                    attachmentSheetState.dismiss()
                                    pick(uri)
                                }
                            },
                        onPickFromGallery =
                            onPickFromGallery?.let { pick ->
                                {
                                    attachmentSheetState.dismiss()
                                    pick()
                                }
                            },
                        onPickDocument =
                            onPickDocument?.let { pick ->
                                {
                                    attachmentSheetState.dismiss()
                                    pick()
                                }
                            },
                        onCaptureFromCamera =
                            onCaptureFromCamera?.let { capture ->
                                {
                                    attachmentSheetState.dismiss()
                                    capture()
                                }
                            },
                        onShareLocation =
                            onShareLocation?.let { share ->
                                {
                                    attachmentSheetState.dismiss()
                                    share()
                                }
                            },
                        onShareUser =
                            onShareUser?.let { share ->
                                {
                                    attachmentSheetState.dismiss()
                                    share()
                                }
                            },
                        onShareContact =
                            onShareContact?.let { share ->
                                {
                                    attachmentSheetState.dismiss()
                                    share()
                                }
                            },
                        onComingSoon = { appState?.present(R.string.coming_soon) },
                    )
                }
            }
        }
    }
}
