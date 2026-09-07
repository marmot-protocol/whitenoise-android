package dev.ipf.whitenoise.android.ui.conversation.composer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.core.codePointBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.codePointBoundaryAtOrBefore
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrAfter
import dev.ipf.whitenoise.android.core.graphemeBoundaryAtOrBefore
import dev.ipf.whitenoise.android.core.replyBodyWithTypedMediaFallback
import dev.ipf.whitenoise.android.core.typedReplyMediaFallback
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.conversation.composerMultilineControlsSuppressed
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
import dev.ipf.whitenoise.android.ui.conversation.resolveAutomaticComposerCeiling
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

private val ComposerManualMinimumHeight = 144.dp

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
internal fun insertEmojiAtSelection(
    value: TextFieldValue,
    emoji: String,
): TextFieldValue {
    val text = value.text
    val rawStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val rawEnd = maxOf(value.selection.start, value.selection.end).coerceIn(rawStart, text.length)
    val collapsed = rawStart == rawEnd
    val start =
        if (collapsed) {
            text.graphemeBoundaryAtOrAfter(rawStart)
        } else {
            text.graphemeBoundaryAtOrBefore(rawStart)
        }
    val end = if (collapsed) start else text.graphemeBoundaryAtOrAfter(rawEnd)
    val updatedText =
        buildString {
            append(text, 0, start)
            append(emoji)
            append(text, end, text.length)
        }
    val caret = start + emoji.length
    return value.copy(text = updatedText, selection = TextRange(caret), composition = null)
}

/** Deletes the selected range, or the code point before a clamped caret. */
internal fun deleteComposerSelectionOrPreviousCodePoint(value: TextFieldValue): TextFieldValue? {
    val text = value.text
    val rawStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val rawEnd = maxOf(value.selection.start, value.selection.end).coerceIn(rawStart, text.length)
    val hasSelection = rawStart != rawEnd
    val rangeStart =
        if (hasSelection) {
            text.codePointBoundaryAtOrBefore(rawStart)
        } else {
            text.codePointBoundaryAtOrAfter(rawStart)
        }
    val rangeEnd = if (hasSelection) text.codePointBoundaryAtOrAfter(rawEnd) else rangeStart
    if (rangeStart == 0 && !hasSelection) return null
    val deleteStart = if (hasSelection) rangeStart else text.offsetByCodePoints(rangeStart, -1)
    val updatedText = text.removeRange(deleteStart, rangeEnd)
    return value.copy(text = updatedText, selection = TextRange(deleteStart), composition = null)
}

internal fun repairComposerMentionEdit(
    oldValue: TextFieldValue,
    proposedValue: TextFieldValue,
    clampMentionSelection: Boolean,
): TextFieldValue {
    val repaired =
        MentionComposer.wholeChipBackspace(
            oldText = oldValue.text,
            oldCaret = oldValue.selection.start,
            newText = proposedValue.text,
            newCaret = proposedValue.selection.start,
        )
            ?: MentionComposer.repairChipDeletion(
                oldText = oldValue.text,
                newText = proposedValue.text,
                includeAdjacentOwnedSeparator = oldValue.selection.collapsed,
            )
    val edited =
        repaired?.let { TextFieldValue(text = it.text, selection = TextRange(it.selection)) }
            ?: proposedValue
    if (!clampMentionSelection) return edited

    val clamped =
        MentionComposer.clampSelectionOutOfChips(
            edited.text,
            edited.selection.start,
            edited.selection.end,
        )
    return if (clamped.start != edited.selection.start || clamped.end != edited.selection.end) {
        edited.copy(selection = TextRange(clamped.start, clamped.end))
    } else {
        edited
    }
}

/**
 * Hoisted composer text state (#1206). Sharing one instance across the main
 * composer and the long-message reader's composer keeps their in-progress text
 * and edit-restore state from drifting — both `ComposerBar`s delegate their
 * `textFieldValue`/`preEditFieldValue` to the same backing [MutableState]. An
 * explicit external revision can rehydrate that state after another entry
 * point, such as Android system share, updates the persisted draft.
 */
@Stable
internal class ComposerTextState(
    initial: TextFieldValue,
) {
    val valueState: MutableState<TextFieldValue> = mutableStateOf(initial)
    val preEditState: MutableState<TextFieldValue?> = mutableStateOf(null)
}

// Last measured keyboard pane height per orientation, shared across composer
// instances for the life of the process. The keyboard's height belongs to the
// device and IME, not to a conversation, so a freshly entered chat can reserve
// the real keyboard space on its first emoji-pane open instead of guessing
// with the fallback height.
private val composerImePaneHeightMemory = mutableStateMapOf<Int, Dp>()

@Composable
internal fun rememberComposerTextState(
    draftKey: Any?,
    initialDraft: TextFieldValue = TextFieldValue(""),
    externalRevision: Any? = 0,
): ComposerTextState = remember(draftKey, externalRevision) { ComposerTextState(initialDraft) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    replyingToMedia: List<MediaAttachmentReferenceFfi> = emptyList(),
    replyingToDisplay: TimelineReplyDisplay? = null,
    messageTextCopy: MessageTextCopy,
    onCancelReply: () -> Unit,
    onSend: (text: String, onAccepted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: TextFieldValue = TextFieldValue(""),
    onDraftChange: (TextFieldValue) -> Unit = {},
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
    dictationController: ConversationDictationController? = null,
    dictationAccountRef: String? = null,
    dictationGroupIdHex: String? = null,
    dictationControlsVisible: Boolean = true,
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
    // #1455: a versioned restored draft opens with the composer focused and the
    // keyboard raised once. Legacy raw-string drafts keep end-of-text selection
    // without auto-focus. One-shot like [autoFocusOnEnter].
    autoFocusOnDraftRestore: Boolean = false,
    // Hoisted at conversation scope so search/selection bar swaps do not reset
    // the one-shot guard when the composer is removed and re-added.
    autoFocusConsumedState: MutableState<Boolean> = remember(draftKey) { mutableStateOf(false) },
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
    // #589: the composer FocusRequester is hoisted from the conversation screen
    // so its resume lifecycle observer can restore focus after an app-switch.
    // Defaulted to a locally-remembered requester so other call sites keep the
    // previous self-contained behavior.
    composerFocus: FocusRequester = remember { FocusRequester() },
    softwareKeyboardController: SoftwareKeyboardController? = LocalSoftwareKeyboardController.current,
    // #589: surfaces the live focus state up to the conversation screen so the
    // resume observer can tell whether the keyboard was up when we were paused.
    onComposerFocusChanged: (Boolean) -> Unit = {},
    onComposerPreImeBack: (() -> Unit)? = null,
    onBottomInputChanged: () -> Unit = {},
    onKeyboardRestoreFromCustomInput: () -> Unit = {},
    onKeyboardRestoreFromCustomInputFailed: () -> Unit = {},
    recentEmojis: List<String> = emptyList(),
    onEmojiUsed: (String) -> Unit = {},
    // #1206: shared so the long-message reader's composer and the main composer
    // don't keep divergent text/edit state. Defaults to a private per-instance
    // state, preserving standalone behavior for any other caller.
    textState: ComposerTextState = rememberComposerTextState(draftKey, initialDraft),
    // Hoisted so the conversation screen can dismiss the sheet on an outside
    // tap; defaults to a private instance for other call sites.
    attachmentSheetState: ComposerAttachmentSheetState = rememberComposerAttachmentSheetState(),
    // Injectable only for deterministic pre-IME Back behavior tests; production
    // uses the view's platform dispatcher.
    overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
    // The conversation Scaffold draws its top app bar above the bottom bar.
    // Reserve that interactive region so a full-height composer handle cannot
    // compete with the title/details action. Standalone readers default to no
    // reserved chrome because their parent already constrains the bottom bar.
    topInteractionClearance: Dp = 0.dp,
) {
    val actionColors = accountActionColors(appState)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var composerEmojiPickerOpen by remember { mutableStateOf(false) }
    var composerEmojiPickerRequested by remember { mutableStateOf(false) }
    var composerEmojiSearchActive by remember { mutableStateOf(false) }
    var composerKeyboardRestorePending by remember { mutableStateOf(false) }
    // The user-selected expansion mode survives rotation and window resizes:
    // manual and full-screen heights re-clamp against the live post-inset
    // maximum in composerHeightPx, so a stale pixel height cannot overflow the
    // rotated viewport. Only a chat switch or font-scale change resets it.
    var composerExpansion by
        remember(
            draftKey,
            configuration.fontScale,
        ) {
            mutableStateOf(ComposerExpansionState())
        }
    var dismissInputAfterCollapse by remember(draftKey) { mutableStateOf(false) }
    var composerHeightDragActive by remember(draftKey) { mutableStateOf(false) }
    var composerHeightTransitionEpoch by remember(draftKey) { mutableIntStateOf(0) }
    var completedComposerHeightTransitionEpoch by remember(draftKey) { mutableIntStateOf(0) }
    var composerHeightTransitionStartPx by remember(draftKey) { mutableFloatStateOf(0f) }
    var visibleComposerHeightPx by remember(draftKey) { mutableFloatStateOf(0f) }
    var composerUsesMultilineControls by
        remember(draftKey, configuration.orientation, configuration.fontScale) {
            mutableStateOf(false)
        }
    var automaticComposerHeightPx by
        remember(draftKey, configuration.orientation, configuration.fontScale) {
            mutableFloatStateOf(0f)
        }
    var customInputPaneHeightPx by remember(configuration.orientation) { mutableFloatStateOf(0f) }

    fun transitionComposerExpansion(next: ComposerExpansionState) {
        if (next != composerExpansion) {
            composerHeightTransitionStartPx =
                visibleComposerHeightPx.takeIf { it > 0f }
                    ?: automaticComposerHeightPx
            composerHeightTransitionEpoch += 1
            composerExpansion = next
        }
    }
    // Field state is a TextFieldValue (not a bare String) so the caret can
    // be positioned at the end of the prefilled body on edit-entry, and so
    // a re-tap on a different message rebases the caret too. Keyed on
    // draftKey so switching to a different chat re-hydrates the text field
    // from that chat's saved draft rather than carrying state across.
    var textFieldValue by textState.valueState
    val text = textFieldValue.text
    val dictationState = dictationController?.state ?: ConversationDictationState.Idle
    val dictationOwnedByComposer =
        dictationAccountRef != null &&
            dictationGroupIdHex != null &&
            dictationController?.isOwnedBy(dictationAccountRef, dictationGroupIdHex) == true
    val dictationPendingElsewhere = dictationController?.blocksNewRequest == true && !dictationOwnedByComposer
    // Snapshot the in-flight composer state (full TextFieldValue — text +
    // caret) when entering edit mode so cancelling restores both. Keyed on
    // the message id so a tap-Edit on a different message snapshots a fresh
    // baseline.
    var preEditFieldValue by textState.preEditState
    var composerTextEditOwnerId by remember(draftKey) { mutableStateOf<String?>(null) }
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
            composerTextEditOwnerId = editingMessageId
            onBottomInputChanged()
            runCatching { composerFocus.requestFocus() }
        } else if (preEditFieldValue != null) {
            // Edit cancelled or submitted: restore the draft the user had
            // been composing before they tapped Edit (text + original caret).
            textFieldValue = preEditFieldValue ?: TextFieldValue("")
            preEditFieldValue = null
            composerTextEditOwnerId = null
        } else {
            composerTextEditOwnerId = null
        }
    }
    // #321: a just-created conversation opens directly with the composer ready.
    // Request focus and raise the soft keyboard exactly once per draft key,
    // gated by a non-saveable flag so a direct in-place conversation switch
    // can focus the new draft without recomposition re-firing it. Skipped while
    // editing — the edit effect above already owns focus then.
    val keyboardController = softwareKeyboardController
    val focusManager = LocalFocusManager.current
    val controllerForDictation = dictationController
    val accountForDictation = dictationAccountRef
    val groupForDictation = dictationGroupIdHex
    val startAppOwnedDictation: (() -> Unit)? =
        if (controllerForDictation != null && accountForDictation != null && groupForDictation != null) {
            if (editingMessageId != null) {
                null
            } else {
                {
                    controllerForDictation.requestStart(
                        accountRef = accountForDictation,
                        groupIdHex = groupForDictation,
                        draft = textFieldValue,
                    )
                }
            }
        } else {
            null
        }
    LaunchedEffect(dismissInputAfterCollapse) {
        if (dismissInputAfterCollapse) {
            // The expanded/automatic modifier swap can replace the focus node.
            // Clear focus after that recomposition so it cannot be restored by
            // the layout transition triggered by the same Back callback.
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            dismissInputAfterCollapse = false
        }
    }
    val imeInsets = WindowInsets.ime
    val imeTargetInsets = WindowInsets.imeAnimationTarget
    val navigationInsets = WindowInsets.navigationBars
    val currentImePaneHeight =
        with(density) {
            (imeInsets.getBottom(this) - navigationInsets.getBottom(this))
                .coerceAtLeast(0)
                .toDp()
        }
    val targetImePaneHeight =
        with(density) {
            (imeTargetInsets.getBottom(this) - navigationInsets.getBottom(this))
                .coerceAtLeast(0)
                .toDp()
        }
    // Seeded from the process-wide memory: the keyboard's height is a property
    // of the device and IME, not of any one conversation, so the first
    // emoji-pane open in a freshly entered chat reserves the keyboard's real
    // space instead of the fallback guess (which made the later pane-to-
    // keyboard handoff grow the bottom region and cover the newest bubble).
    var rememberedImePaneHeight by remember(configuration.orientation) {
        mutableStateOf(composerImePaneHeightMemory[configuration.orientation] ?: 0.dp)
    }
    var lockedComposerEmojiPaneHeight by remember(configuration.orientation) { mutableStateOf(0.dp) }
    var lockedComposerAttachmentPaneHeight by remember(configuration.orientation) { mutableStateOf(0.dp) }
    LaunchedEffect(targetImePaneHeight, composerEmojiPickerOpen, attachmentSheetState.isOpen) {
        rememberedImePaneHeight =
            updatedComposerRememberedImeHeight(
                previousRememberedImeHeight = rememberedImePaneHeight,
                currentImeHeight = targetImePaneHeight,
                freezeUpdates = composerEmojiPickerOpen || attachmentSheetState.isOpen,
            )
        if (rememberedImePaneHeight > 0.dp) {
            composerImePaneHeightMemory[configuration.orientation] = rememberedImePaneHeight
        }
    }
    val emojiPaneBaseHeight =
        composerEmojiPaneHeight(
            lockedPaneHeight = lockedComposerEmojiPaneHeight,
            currentImeHeight = currentImePaneHeight,
            targetImeHeight = targetImePaneHeight,
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
    // The pane's rendered height is seeded from the live bottom inset on the
    // open edge, then animates to the locked target. Opening over a fully
    // shown keyboard seeds start == target, so nothing moves — the pane takes
    // the keyboard's exact space. Opening mid-IME-animation (a rapid tap
    // before the keyboard finished showing or hiding) continues the motion
    // the user is already watching instead of snapping the composer to the
    // pane's final height in one frame.
    val latestEmojiPaneHeight by rememberUpdatedState(emojiPaneHeight)
    val emojiPaneHeightAnim =
        remember(showEmojiPane) {
            Animatable(if (showEmojiPane) currentImePaneHeight else 0.dp, Dp.VectorConverter)
        }
    LaunchedEffect(emojiPaneHeightAnim, showEmojiPane) {
        // Only the open pane follows its target; the placeholder instance
        // created on close would otherwise animate invisibly for nothing.
        if (!showEmojiPane) return@LaunchedEffect
        snapshotFlow { latestEmojiPaneHeight }.collectLatest { target ->
            if (emojiPaneHeightAnim.value != target) {
                emojiPaneHeightAnim.animateTo(target, tween(durationMillis = 250, easing = FastOutSlowInEasing))
            }
        }
    }
    // Attachment sheet: shares the emoji pane's IME-height model so opening
    // either surface swaps seamlessly with the keyboard and with each other.
    val attachmentPaneHeight =
        composerEmojiPaneHeight(
            lockedPaneHeight = lockedComposerAttachmentPaneHeight,
            currentImeHeight = currentImePaneHeight,
            targetImeHeight = targetImePaneHeight,
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
                    targetImeHeight = targetImePaneHeight,
                    rememberedImeHeight = rememberedImePaneHeight,
                )
        }
        composerEmojiSearchActive = false
        composerEmojiPickerRequested = false
        composerKeyboardRestorePending = true
        onKeyboardRestoreFromCustomInput()
        // Focus and IME are requested synchronously. A request parked in an
        // effect is cancelled by the very recomposition a rapid reverse-tap
        // triggers, which left the pane waiting on a keyboard that was never
        // actually asked to show.
        runCatching { composerFocus.requestFocus() }
        keyboardController?.show()
    }

    fun releasePaneToKeyboard() {
        composerKeyboardRestorePending = false
        composerEmojiPickerOpen = false
        attachmentSheetState.dismiss()
    }

    LaunchedEffect(composerKeyboardRestorePending, currentImePaneHeight, targetImePaneHeight, emojiPaneHeightAnim.value) {
        if (attachmentSheetState.isOpen) {
            if (
                shouldSwapComposerEmojiPaneToIme(
                    keyboardRestorePending = composerKeyboardRestorePending,
                    currentImeHeight = currentImePaneHeight,
                    imeTargetHeight = targetImePaneHeight,
                )
            ) {
                releasePaneToKeyboard()
            }
            return@LaunchedEffect
        }
        when (
            composerEmojiPaneRestoreStep(
                keyboardRestorePending = composerKeyboardRestorePending,
                currentImeHeight = currentImePaneHeight,
                imeTargetHeight = targetImePaneHeight,
                lockedPaneHeight = lockedComposerEmojiPaneHeight,
                renderedPaneHeight = emojiPaneHeightAnim.value,
            )
        ) {
            ComposerPaneRestoreStep.HOLD -> Unit
            // The keyboard settled at a height the pane did not reserve (a
            // toolbar row toggled, or the pane opened at its fallback before
            // any keyboard was measured). Glide the pane there first; the
            // animation frames re-run this effect until the rendered pane
            // occupies exactly the keyboard's space, and only then swap.
            ComposerPaneRestoreStep.MATCH_PANE_TO_KEYBOARD -> {
                lockedComposerEmojiPaneHeight = targetImePaneHeight
                // The bottom region is about to change height, so the
                // bounds-identical-swap assumption behind the suppressed
                // ime-open re-anchor no longer holds: chase the newest bubble
                // through the glide so it is not left covered.
                onBottomInputChanged()
            }
            ComposerPaneRestoreStep.SWAP_TO_KEYBOARD -> releasePaneToKeyboard()
        }
    }

    LaunchedEffect(composerKeyboardRestorePending) {
        if (composerKeyboardRestorePending) {
            // Must outlast a full IME show (~400ms) plus the pane's
            // match-to-keyboard glide (250ms); at 600ms the timeout preempted
            // the glide when the pane opened at its fallback height and the
            // handoff ended with a visible step instead of a seamless swap.
            delay(900L)
            if (composerKeyboardRestorePending) {
                // The IME never reached the reserved pane. Always release the
                // pane — the user explicitly asked for the keyboard, so
                // re-anchoring to the picker would override that intent (and
                // can force-hide a keyboard that did come up, just at a
                // different height than the pane reserved).
                composerKeyboardRestorePending = false
                composerEmojiPickerRequested = false
                composerEmojiPickerOpen = false
                attachmentSheetState.dismiss()
                if (composerKeyboardRestoreTimeoutClearsFocus(latestImePaneHeight)) {
                    onKeyboardRestoreFromCustomInputFailed()
                    focusManager.clearFocus(force = true)
                } else {
                    // Released under a keyboard resting at some other height:
                    // the bottom region changed, so re-anchor the newest
                    // bubble the suppressed ime-open chase would have caught.
                    onBottomInputChanged()
                }
            }
        }
    }

    BackHandler(enabled = composerEmojiPickerOpen || attachmentSheetState.isOpen) {
        composerKeyboardRestorePending = false
        composerEmojiPickerRequested = false
        composerEmojiPickerOpen = false
        attachmentSheetState.dismiss()
    }
    var autoFocusConsumed by autoFocusConsumedState
    LaunchedEffect(draftKey, autoFocusOnEnter, autoFocusOnDraftRestore, editingMessageId) {
        val autoFocusRequested = autoFocusOnEnter || autoFocusOnDraftRestore
        if (autoFocusRequested && !autoFocusConsumed && editingMessageId == null) {
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
    // visible input and scrolls to newest only after the controller commits the
    // optimistic bubble. The MDK-owned draft deliberately remains intact until
    // the separate durable-acceptance callback clears it; otherwise process
    // death during the FFI call can erase a send MDK never accepted (#1216).
    // For an in-place edit the controller short-circuits and never calls
    // onAccepted, so the pre-edit composer is restored instead.
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
                        composerExpansion = ComposerExpansionState()
                    }
                    onAfterSend()
                }
            }
        }
    }

    fun applyComposerFieldValue(value: TextFieldValue) {
        textFieldValue = value
        if (editingMessageId == null) onDraftChange(value)
    }

    fun deleteFromComposer() {
        val proposedValue = deleteComposerSelectionOrPreviousCodePoint(textFieldValue) ?: return
        val updatedValue = repairComposerMentionEdit(textFieldValue, proposedValue, mentionPickerEnabled)
        applyComposerFieldValue(updatedValue)
    }

    fun openComposerEmojiPane() {
        attachmentSheetState.dismiss()
        if (composerKeyboardRestorePending) {
            // Reversing an in-flight restore abandons it; the failed callback
            // lets the conversation screen drop the reanchor suppression it
            // armed for a keyboard that is no longer coming.
            composerKeyboardRestorePending = false
            onKeyboardRestoreFromCustomInputFailed()
        }
        composerEmojiSearchActive = false
        if (!composerEmojiPickerOpen || lockedComposerEmojiPaneHeight == 0.dp) {
            lockedComposerEmojiPaneHeight =
                composerEmojiPaneTargetHeight(
                    currentImeHeight = currentImePaneHeight,
                    targetImeHeight = targetImePaneHeight,
                    rememberedImeHeight = rememberedImePaneHeight,
                )
        }
        composerEmojiPickerRequested = true
        composerEmojiPickerOpen = true
        // Hidden synchronously for the same reason the restore path shows
        // synchronously: a deferred hide can land after a newer request and
        // flip the bottom region against the user's latest choice.
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun showKeyboardFromEmojiPane() {
        attachmentSheetState.dismiss()
        restoreKeyboardFromEmojiPane()
    }

    fun openComposerAttachmentSheet() {
        composerKeyboardRestorePending = false
        composerEmojiPickerRequested = false
        composerEmojiPickerOpen = false
        lockedComposerAttachmentPaneHeight =
            composerEmojiPaneTargetHeight(
                currentImeHeight = currentImePaneHeight,
                targetImeHeight = targetImePaneHeight,
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
                    targetImeHeight = targetImePaneHeight,
                    rememberedImeHeight = rememberedImePaneHeight,
                )
        }
        composerKeyboardRestorePending = true
        onBottomInputChanged()
        runCatching { composerFocus.requestFocus() }
        keyboardController?.show()
    }
    LaunchedEffect(showEmojiPane, showAttachmentPane) {
        if (!showEmojiPane && !showAttachmentPane) customInputPaneHeightPx = 0f
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val appliesImePadding =
            composerBottomClusterAppliesImePadding(
                showEmojiPane = showEmojiPane,
                composerEmojiSearchActive = composerEmojiSearchActive,
                showAttachmentPane = showAttachmentPane,
            )
        val bottomInset =
            with(density) {
                val navigationBottom = navigationInsets.getBottom(this)
                if (appliesImePadding) {
                    maxOf(imeTargetInsets.getBottom(this), navigationBottom).toDp()
                } else {
                    navigationBottom.toDp()
                }
            }
        val boundedHeight =
            if (maxHeight == Dp.Infinity) {
                configuration.screenHeightDp.dp
            } else {
                maxHeight
            }
        val customInputPaneHeight = with(density) { customInputPaneHeightPx.toDp() }
        val maximumComposerHeight =
            (boundedHeight - statusBarTop - topInteractionClearance - bottomInset - customInputPaneHeight)
                .coerceAtLeast(44.dp)
        val automaticComposerCeiling = resolveAutomaticComposerCeiling(maximumComposerHeight)
        val maximumComposerHeightPx = with(density) { maximumComposerHeight.toPx() }
        val minimumManualComposerHeightPx =
            with(density) {
                ComposerManualMinimumHeight
                    .coerceAtMost(maximumComposerHeight)
                    .toPx()
            }
        val automaticComposerCeilingPx = with(density) { automaticComposerCeiling.toPx() }
        val resolvedAutomaticHeightPx =
            (automaticComposerHeightPx.takeIf { it > 0f } ?: with(density) { 44.dp.toPx() })
                .coerceAtMost(automaticComposerCeilingPx)
        val resolvedComposerHeight =
            with(density) {
                composerHeightPx(
                    state = composerExpansion,
                    automaticHeightPx = resolvedAutomaticHeightPx,
                    minimumManualHeightPx = minimumManualComposerHeightPx,
                    maximumHeightPx = maximumComposerHeightPx,
                ).toDp()
            }
        val expandedControlLayout =
            composerUsesMultilineControls || composerExpansion.mode != ComposerExpansionMode.Automatic
        val expandedActionInsetProgress =
            animateFloatAsState(
                targetValue = if (expandedControlLayout) 1f else 0f,
                animationSpec =
                    tween(
                        durationMillis = COMPOSER_EXPANSION_ANIMATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                label = "expanded composer action inset",
            )
        val composerHeightTransitionActive =
            composerHeightTransitionEpoch != completedComposerHeightTransitionEpoch
        val transitionTargetHeightPx =
            composerHeightPx(
                state = composerExpansion,
                automaticHeightPx = resolvedAutomaticHeightPx,
                minimumManualHeightPx = minimumManualComposerHeightPx,
                maximumHeightPx = maximumComposerHeightPx,
            )
        val composerHeightAnimation =
            remember(composerHeightTransitionEpoch) {
                Animatable(composerHeightTransitionStartPx)
            }
        // Read the animated value during measurement so height frames do not
        // recompose BasicTextField or the rest of this large composer tree.
        val animatedComposerHeightModifier =
            Modifier.layout { measurable, constraints ->
                val height =
                    composerHeightAnimation.value
                        .roundToInt()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, height) {
                    placeable.placeRelative(0, 0)
                }
            }
        LaunchedEffect(
            composerHeightTransitionEpoch,
            transitionTargetHeightPx,
        ) {
            if (composerHeightTransitionActive) {
                composerHeightAnimation.animateTo(
                    transitionTargetHeightPx,
                    tween(
                        durationMillis =
                            composerHeightAnimationDurationMillis(
                                mode = composerExpansion.mode,
                                dragActive = composerHeightDragActive,
                                discreteTransitionActive = composerHeightTransitionActive,
                            ),
                        easing = FastOutSlowInEasing,
                    ),
                )
                completedComposerHeightTransitionEpoch = composerHeightTransitionEpoch
            }
        }

        BackHandler(
            enabled =
                composerExpansion.mode != ComposerExpansionMode.Automatic &&
                    !showEmojiPane &&
                    !showAttachmentPane,
        ) {
            transitionComposerExpansion(collapseComposer(composerExpansion))
            onBottomInputChanged()
        }

        Column(
            composerBottomClusterModifier(
                showEmojiPane = showEmojiPane,
                composerEmojiSearchActive = composerEmojiSearchActive,
                base = Modifier.fillMaxWidth(),
                showAttachmentPane = showAttachmentPane,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            composerHeightDragActive -> Modifier.height(resolvedComposerHeight)
                            composerHeightTransitionActive -> animatedComposerHeightModifier
                            composerExpansion.mode == ComposerExpansionMode.Automatic ->
                                Modifier.heightIn(max = automaticComposerCeiling)
                            else -> Modifier.height(resolvedComposerHeight)
                        },
                    ).then(
                        if (composerExpansion.mode == ComposerExpansionMode.Automatic) {
                            Modifier
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.background)
                        },
                    ).onSizeChanged { size ->
                        visibleComposerHeightPx = size.height.toFloat()
                        if (
                            composerExpansion.mode == ComposerExpansionMode.Automatic &&
                            !composerHeightTransitionActive
                        ) {
                            automaticComposerHeightPx = size.height.toFloat()
                        }
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
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
                    val mediaFallback = remember(replyingToMedia) { typedReplyMediaFallback(replyingToMedia) }
                    val mediaKind =
                        replyingToDisplay?.mediaKind
                            ?: remember(mediaFallback, replyingTo.tags, replyingTo.sourceEpoch) {
                                composerReplyMediaKind(mediaFallback, replyingTo.tags, replyingTo.sourceEpoch)
                            }
                    val profileRevision = appState?.profileRevisionForCompose
                    val replyMentionDisplayName =
                        remember(appState, profileRevision) {
                            appState?.let { state ->
                                { bech32: String -> state.mentionDisplayName(bech32) }
                            }
                        }
                    val projectedReplyBody =
                        remember(replyingTo, messageTextCopy) {
                            MessageProjector.displayBody(replyingTo, messageTextCopy)
                        }
                    val replyBody =
                        replyingToDisplay?.body
                            ?: remember(replyingTo, projectedReplyBody, mediaFallback, messageTextCopy) {
                                replyBodyWithTypedMediaFallback(
                                    plaintext = replyingTo.plaintext,
                                    projectedBody = projectedReplyBody,
                                    mediaFallback = mediaFallback,
                                    copy = messageTextCopy,
                                )
                            }
                    ReplyPreviewCard(
                        senderTitle =
                            if (replyingTo.direction == "sent") {
                                stringResource(R.string.reply_you)
                            } else {
                                appState?.displayName(replyingTo.sender) ?: replyingTo.sender.take(8)
                            },
                        isOwn = replyingTo.direction == "sent",
                        body = replyBody,
                        mediaKind = mediaKind,
                        mediaFileName = replyingToDisplay?.mediaFileName ?: mediaFallback?.filename,
                        mediaType = replyingToDisplay?.mediaType ?: mediaFallback?.mediaType,
                        warning = replyingToDisplay?.warning,
                        onClick = null,
                        onDismiss = onCancelReply,
                        mentionDisplayName = replyMentionDisplayName,
                    )
                }
                // #414: live @-mention picker. Compute the open query from the current
                // caret; suppressed entirely in DMs or with no roster. Edit mode
                // intentionally shares this exact query/insertion contract so
                // canonical mention tokens survive save and reopen. The owner gate
                // suppresses the one composition between an edit-session change and
                // its LaunchedEffect prefill, when the field still belongs to the
                // previous draft or edit target.
                // Anchored directly above the composer input row, capped at
                // ~50% of the viewport height.
                val composerTextMatchesEditSession = composerTextEditOwnerId == editingMessageId
                val mentionQuery =
                    if (mentionPickerEnabled && composerTextMatchesEditSession) {
                        MentionComposer
                            .activeMentionQuery(textFieldValue.text, textFieldValue.selection.start)
                            .takeIf { textFieldValue.selection.collapsed }
                    } else {
                        null
                    }
                val mentionMatches =
                    remember(mentionQuery?.query, mentionCandidates) {
                        if (mentionQuery == null) {
                            emptyList()
                        } else {
                            MentionComposer.filter(mentionQuery.query, mentionCandidates)
                        }
                    }
                if (mentionQuery != null && mentionMatches.isNotEmpty()) {
                    val openQuery = mentionQuery
                    MentionPicker(
                        candidates = mentionMatches,
                        onPick = { candidate ->
                            val insertion = MentionComposer.insertMention(textFieldValue.text, openQuery, candidate)
                            val updated =
                                TextFieldValue(
                                    text = insertion.text,
                                    selection = TextRange(insertion.selection),
                                )
                            applyComposerFieldValue(updated)
                            runCatching { composerFocus.requestFocus() }
                            composerEmojiPickerOpen = false
                            composerEmojiPickerRequested = false
                            attachmentSheetState.dismiss()
                        },
                    )
                }
                val activeRecordingController = voiceRecordingController?.takeIf { it.isRecording }
                val isRecordingVoice = activeRecordingController != null
                val dictationActiveInComposer =
                    dictationControlsVisible &&
                        dictationOwnedByComposer &&
                        dictationState !is ConversationDictationState.Idle
                val dictationOriginHidden =
                    !dictationControlsVisible &&
                        dictationOwnedByComposer &&
                        dictationState !is ConversationDictationState.Idle
                val activeDictationController = dictationController?.takeIf { dictationActiveInComposer }
                val showMicButton =
                    (text.isBlank() || isRecordingVoice) &&
                        editingMessageId == null &&
                        voiceRecordingController != null &&
                        !dictationActiveInComposer
                val showPrimaryTrailingAction =
                    !dictationActiveInComposer &&
                        !dictationOriginHidden &&
                        !(showMicButton && dictationPendingElsewhere)
                val primaryTrailingActionWidth =
                    if (dictationActiveInComposer) {
                        0.dp
                    } else if (showMicButton && voiceRecordingController.locked) {
                        84.dp
                    } else {
                        44.dp
                    }
                val trailingControlsWidth = primaryTrailingActionWidth
                BoxWithConstraints(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (composerExpansion.mode == ComposerExpansionMode.Automatic) {
                                    Modifier.weight(1f, fill = false)
                                } else {
                                    Modifier.weight(1f)
                                },
                            ),
                ) {
                    // Keep the field composed and focusable throughout dictation. The
                    // app-owned controls replace only the waveform slot, so an open IME
                    // stays open and a closed IME is never raised implicitly.
                    ComposerPill(
                        textFieldValue = textFieldValue,
                        composerFocus = composerFocus,
                        emojiPickerOpen = composerEmojiPickerRequested,
                        onComposerFocusChanged = { focused ->
                            // A tap on the text field while a pane is open asks
                            // for the keyboard; the restore functions' pending
                            // guard drops the echo of their own focus request.
                            if (focused && composerEmojiPickerOpen) restoreKeyboardFromEmojiPane()
                            if (focused && attachmentSheetState.isOpen) restoreKeyboardFromAttachmentSheet()
                            onComposerFocusChanged(focused)
                        },
                        onValueChange = { value ->
                            if (!isRecordingVoice) {
                                val applied = repairComposerMentionEdit(textFieldValue, value, mentionPickerEnabled)
                                applyComposerFieldValue(applied)
                            }
                        },
                        onEmojiPickerToggle = {
                            if (composerEmojiPickerRequested) {
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
                        preImeBackEnabled = !composerEmojiPickerOpen && !attachmentSheetState.isOpen,
                        onPreImeBack = {
                            if (composerExpansion.mode != ComposerExpansionMode.Automatic) {
                                transitionComposerExpansion(collapseComposer(composerExpansion))
                                dismissInputAfterCollapse = true
                                onBottomInputChanged()
                            } else if (onComposerPreImeBack != null) {
                                onComposerPreImeBack()
                            } else {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                        },
                        hasCameraCapture = onCaptureFromCamera != null,
                        hasLocationShare = onShareLocation != null,
                        hasUserShare = onShareUser != null,
                        hasContactShare = onShareContact != null,
                        onPickFromGallery = onPickFromGallery,
                        onPickDocument = onPickDocument,
                        onPasteImageUris = onPasteImageUris?.takeIf { editingMessageId == null && !isRecordingVoice },
                        onDictation =
                            startAppOwnedDictation?.takeIf {
                                dictationState is ConversationDictationState.Idle &&
                                    !dictationPendingElsewhere &&
                                    !isRecordingVoice
                            },
                        dictationControls =
                            activeDictationController?.let { activeController ->
                                {
                                    ConversationDictationCompactActions(
                                        state = dictationState,
                                        controller = activeController,
                                    )
                                }
                            },
                        highlightMentionChips = mentionPickerEnabled,
                        mentionCandidates = mentionCandidates,
                        enterKeyBehavior = enterKeyBehavior,
                        onImeSend = submitMessage,
                        expansionMode = composerExpansion.mode,
                        onExpansionToggle = {
                            composerHeightDragActive = false
                            transitionComposerExpansion(toggleComposerFullScreen(composerExpansion))
                            onBottomInputChanged()
                        },
                        onHeightDragStarted = { composerHeightDragActive = true },
                        onHeightDrag = { dragAmount ->
                            composerExpansion =
                                dragComposerHeight(
                                    state = composerExpansion,
                                    dragDeltaYPx = dragAmount,
                                    automaticHeightPx = resolvedAutomaticHeightPx,
                                    minimumManualHeightPx = minimumManualComposerHeightPx,
                                    maximumHeightPx = maximumComposerHeightPx,
                                )
                        },
                        onHeightDragStopped = {
                            composerHeightDragActive = false
                            val settledExpansion =
                                settleComposerHeight(
                                    state = composerExpansion,
                                    automaticHeightPx = resolvedAutomaticHeightPx,
                                    minimumManualHeightPx = minimumManualComposerHeightPx,
                                    maximumHeightPx = maximumComposerHeightPx,
                                    deadbandPx = with(density) { 20.dp.toPx() },
                                )
                            transitionComposerExpansion(settledExpansion)
                            onBottomInputChanged()
                        },
                        overlayBackRegistrar = overlayBackRegistrar,
                        inputContentVisible = !isRecordingVoice,
                        inputFocusEnabled = !dismissInputAfterCollapse,
                        expandedTrailingActionInset = trailingControlsWidth,
                        compactMeasurementWidth =
                            (maxWidth - trailingControlsWidth - 8.dp)
                                .coerceAtLeast(1.dp),
                        compactMeasurementReservesTrailingAction = false,
                        compactOuterEndInset = trailingControlsWidth + 8.dp,
                        onMultilineControlsChanged = { composerUsesMultilineControls = it },
                        multilineControlsSuppressed = composerMultilineControlsSuppressed(automaticComposerCeiling),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (composerExpansion.mode == ComposerExpansionMode.Automatic) {
                                        Modifier
                                    } else {
                                        Modifier.fillMaxHeight()
                                    },
                                ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .expandedComposerActionRow { expandedActionInsetProgress.value },
                    ) {
                        // This call site stays shared by idle and recording states;
                        // moving it would break the active hold gesture's identity.
                        if (showPrimaryTrailingAction && showMicButton && voiceRecordingController.locked) {
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
                                modifier = Modifier.composerActionSize { expandedActionInsetProgress.value },
                                containerColor = actionColors.container,
                                contentColor = actionColors.content,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else if (showPrimaryTrailingAction && showMicButton) {
                            Box(contentAlignment = Alignment.BottomCenter) {
                                LockHintAbove(controller = voiceRecordingController)
                                MicHoldButton(controller = voiceRecordingController)
                            }
                        } else if (showPrimaryTrailingAction) {
                            FloatingActionButton(
                                onClick = { submitMessage() },
                                modifier = Modifier.composerActionSize { expandedActionInsetProgress.value },
                                containerColor = actionColors.container,
                                contentColor = actionColors.content,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    if (activeRecordingController != null) {
                        RecordingStripLeading(
                            controller = activeRecordingController,
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .padding(
                                        end =
                                            if (expandedControlLayout) {
                                                if (voiceRecordingController.locked) 84.dp else 44.dp
                                            } else if (voiceRecordingController.locked) {
                                                92.dp
                                            } else {
                                                52.dp
                                            },
                                    ).pointerInput(activeRecordingController) {
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
            }
            if (showEmojiPane || showAttachmentPane) {
                // Box, not stacked children — during the 120ms crossfade both panes
                // can be visible and stacking them would double the cluster height.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { customInputPaneHeightPx = it.height.toFloat() },
                ) {
                    if (showEmojiPane) {
                        ComposerEmojiPickerPane(
                            height = emojiPaneHeightAnim.value,
                            alpha = 1f,
                            recentEmojis = recentEmojis,
                            onEmojiUsed = onEmojiUsed,
                            onEmojiPicked = { emoji ->
                                val updated = insertEmojiAtSelection(textFieldValue, emoji)
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
}
