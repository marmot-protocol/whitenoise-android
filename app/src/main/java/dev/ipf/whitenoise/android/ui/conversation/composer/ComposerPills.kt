package dev.ipf.whitenoise.android.ui.conversation.composer

import android.net.Uri
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.ui.common.TextEntryEmojiAction
import dev.ipf.whitenoise.android.ui.conversation.ComposerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.composerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentImageUriOrNull
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.flow.first

// BasicTextField (not Material3 TextField) so the pill height isn't pinned
// to the 56dp filled-textfield minimum.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerPill(
    textFieldValue: TextFieldValue,
    composerFocus: FocusRequester,
    emojiPickerOpen: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onEmojiPickerToggle: () -> Unit,
    onAttachmentsToggle: () -> Unit,
    attachmentSheetOpen: Boolean,
    onPickFromGallery: (() -> Unit)?,
    onPickDocument: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Gate inputs only: the sheet these open lives in ComposerBar, but the
    // attach button must appear whenever ANY attachment action is wired, not
    // just gallery/document.
    hasCameraCapture: Boolean = false,
    hasLocationShare: Boolean = false,
    hasUserShare: Boolean = false,
    hasContactShare: Boolean = false,
    highlightMentionChips: Boolean = false,
    mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
    onImeSend: () -> Unit = {},
    onPasteImageUris: ((List<Uri>) -> Unit)? = null,
    // #589: report the BasicTextField's focus edge up so the conversation
    // screen can record whether the keyboard was up when the app was paused.
    onComposerFocusChanged: (Boolean) -> Unit = {},
    preImeBackEnabled: Boolean = false,
    onPreImeBack: () -> Unit = {},
    overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
    expansionMode: ComposerExpansionMode = ComposerExpansionMode.Automatic,
    onExpansionToggle: () -> Unit = {},
    onHeightDrag: (Float) -> Unit = {},
    onHeightDragStopped: () -> Unit = {},
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
    inputContentVisible: Boolean = true,
    onMultilineControlsChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val resizeComposerDescription = stringResource(R.string.composer_resize)
    val latestOnPasteImageUris by rememberUpdatedState(onPasteImageUris)
    val latestOnPreImeBack by rememberUpdatedState(onPreImeBack)
    val latestOnHeightDrag by rememberUpdatedState(onHeightDrag)
    val latestOnHeightDragStopped by rememberUpdatedState(onHeightDragStopped)
    var composerFocused by remember { mutableStateOf(false) }
    val backDispatcher = LocalView.current.findOnBackInvokedDispatcher()
    // Gesture/predictive Back reaches the IME before the activity's ordinary
    // BackHandler. While this field owns focus, register ahead of the IME so an
    // explicit Back clears focus; IME-only geometry changes (including a
    // keyboard-to-voice handoff) never invoke this callback.
    DisposableEffect(preImeBackEnabled, composerFocused, backDispatcher, overlayBackRegistrar) {
        val unregister =
            if (preImeBackEnabled && composerFocused) {
                val callback = OnBackInvokedCallback { latestOnPreImeBack() }
                when {
                    overlayBackRegistrar != null ->
                        overlayBackRegistrar.register(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
                    backDispatcher != null -> {
                        backDispatcher.registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                            callback,
                        )
                        val unregisterCallback: () -> Unit = {
                            backDispatcher.unregisterOnBackInvokedCallback(callback)
                        }
                        unregisterCallback
                    }
                    else -> null
                }
            } else {
                null
            }
        onDispose { unregister?.invoke() }
    }
    val pasteImageReceiver =
        remember(context) {
            object : ReceiveContentListener {
                override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                    val onPaste = latestOnPasteImageUris ?: return transferableContent
                    if (!transferableContent.hasMediaType(MediaType.Image)) return transferableContent

                    val imageUris = mutableListOf<Uri>()
                    val remaining =
                        transferableContent.consume { item ->
                            val imageUri =
                                receiveContentImageUriOrNull(
                                    item = item,
                                    clipDescription = transferableContent.clipMetadata.clipDescription,
                                    resolveMime = { uri -> safeGetType(context.contentResolver, uri) },
                                )
                            if (imageUri != null) imageUris += imageUri
                            imageUri != null
                        }
                    if (imageUris.isNotEmpty()) onPaste(imageUris.distinct())
                    return remaining
                }
            }
        }
    // #414/#442: paint stored `@npub1…` chip runs as friendly visible labels
    // (`@alice` when the profile is resolved, short `@npub1…` otherwise)
    // while keeping the backing TextFieldValue canonical for send/markdown.
    val chipColor = MaterialTheme.colorScheme.primary
    val mentionCandidateLookup =
        remember(highlightMentionChips, mentionCandidates) {
            if (highlightMentionChips) MentionComposer.candidatesByNpub(mentionCandidates) else emptyMap()
        }
    val mentionVisualTransformation =
        remember(highlightMentionChips, chipColor, mentionCandidateLookup) {
            if (!highlightMentionChips) {
                VisualTransformation.None
            } else {
                VisualTransformation { text ->
                    // #607: the chip renderer must never crash the composer. The
                    // primary fix (MentionComposer.repairChipDeletion in
                    // onValueChange) makes a partial `@npub1…` chip state
                    // impossible, but degrade gracefully — fall back to the
                    // untransformed text — if any unforeseen malformed input
                    // state still drives buildAnnotatedString / the offset
                    // mapping out of bounds, rather than letting it throw.
                    runCatching {
                        val visual = MentionComposer.editingVisualText(text.text, mentionCandidateLookup)
                        val visualLength = visual.text.length
                        val styled =
                            buildAnnotatedString {
                                append(visual.text)
                                visual.ranges.forEach { range ->
                                    // Clamp span bounds into the transformed text
                                    // so a stale/oversized range can't trip
                                    // addStyle's range check.
                                    val spanStart = range.transformed.first.coerceIn(0, visualLength)
                                    val spanEnd = (range.transformed.last + 1).coerceIn(spanStart, visualLength)
                                    if (spanEnd > spanStart) {
                                        addStyle(
                                            SpanStyle(
                                                color = chipColor,
                                                fontWeight = FontWeight.Medium,
                                                background = chipColor.copy(alpha = 0.12f),
                                            ),
                                            spanStart,
                                            spanEnd,
                                        )
                                    }
                                }
                            }
                        val offsetMapping =
                            object : OffsetMapping {
                                override fun originalToTransformed(offset: Int): Int = visual.originalToTransformed(offset).coerceIn(0, visualLength)

                                override fun transformedToOriginal(offset: Int): Int = visual.transformedToOriginal(offset).coerceIn(0, text.text.length)
                            }
                        TransformedText(styled, offsetMapping)
                    }.getOrElse {
                        TransformedText(text, OffsetMapping.Identity)
                    }
                }
            }
        }
    val hasAttachmentAction =
        onPickFromGallery != null ||
            onPickDocument != null ||
            hasCameraCapture ||
            hasLocationShare ||
            hasUserShare ||
            hasContactShare
    var multilineControls by remember { mutableStateOf(false) }
    val expandedLayout = multilineControls || expansionMode != ComposerExpansionMode.Automatic
    val compactTrailingReserve =
        4.dp +
            (if (hasAttachmentAction) 36.dp else 0.dp) +
            (if (trailingAction != null) 44.dp else 0.dp)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        border = amoledSurfaceBorderStroke(),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .then(if (expansionMode == ComposerExpansionMode.Automatic) Modifier else Modifier.fillMaxHeight()),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (expansionMode == ComposerExpansionMode.Automatic) Modifier else Modifier.fillMaxHeight())
                        .alpha(if (inputContentVisible) 1f else 0f)
                        .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (expansionMode == ComposerExpansionMode.Automatic) Modifier else Modifier.fillMaxHeight())
                            .padding(
                                start = if (expandedLayout) 12.dp else 44.dp,
                                top = if (expandedLayout) 48.dp else 10.dp,
                                end = if (expandedLayout) 12.dp else compactTrailingReserve,
                                bottom = if (expandedLayout) 48.dp else 10.dp,
                            ).contentReceiver(pasteImageReceiver)
                            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                                when (
                                    composerPreImeBackAction(
                                        enabled = preImeBackEnabled,
                                        isBackKey = event.key == Key.Back,
                                        isKeyDown = event.type == KeyEventType.KeyDown,
                                    )
                                ) {
                                    ComposerPreImeBackAction.IGNORE -> false
                                    ComposerPreImeBackAction.CONSUME -> true
                                    ComposerPreImeBackAction.DISMISS -> {
                                        onPreImeBack()
                                        true
                                    }
                                }
                            }.focusRequester(composerFocus)
                            // #589: track focus so the conversation screen's
                            // resume observer knows whether the keyboard was up
                            // when the app was backgrounded (Case B gate).
                            .onFocusChanged {
                                composerFocused = it.isFocused
                                onComposerFocusChanged(it.isFocused)
                            }
                            // #404: honor the Enter-key toggle for hardware
                            // keyboards (Bluetooth/foldable/ChromeOS). Shift+Enter
                            // always inserts a line break as an escape hatch; in
                            // NewLine mode a bare Enter falls through to the normal
                            // newline insertion.
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    when {
                                        event.isShiftPressed -> false
                                        enterKeyBehavior == EnterKeyBehavior.SendMessage -> {
                                            onImeSend()
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                    textStyle =
                        LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            textDirection = TextDirection.ContentOrLtr,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = mentionVisualTransformation,
                    // #404: in SendMessage mode the soft-keyboard action sends;
                    // in NewLine mode the IME shows an Enter/newline key that
                    // inserts `\n`.
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            keyboardType = KeyboardType.Text,
                            imeAction =
                                if (enterKeyBehavior == EnterKeyBehavior.SendMessage) {
                                    ImeAction.Send
                                } else {
                                    ImeAction.Default
                                },
                        ),
                    keyboardActions = KeyboardActions(onSend = { onImeSend() }),
                    maxLines = Int.MAX_VALUE,
                    onTextLayout = { layout ->
                        val nextMultilineControls =
                            when {
                                multilineControls && layout.lineCount <= 1 -> false
                                !multilineControls && layout.lineCount >= 3 -> true
                                else -> multilineControls
                            }
                        if (nextMultilineControls != multilineControls) {
                            multilineControls = nextMultilineControls
                            onMultilineControlsChanged(nextMultilineControls)
                        }
                    },
                )
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        stringResource(R.string.message),
                        style = LocalTextStyle.current.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier =
                            Modifier.padding(
                                start = if (expandedLayout) 12.dp else 44.dp,
                                top = if (expandedLayout) 48.dp else 10.dp,
                            ),
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 4.dp, bottom = 4.dp)
                        .alpha(if (inputContentVisible) 1f else 0f)
                        .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
            ) {
                TextEntryEmojiAction(
                    pickerOpen = emojiPickerOpen,
                    enabled = true,
                    onClick = onEmojiPickerToggle,
                    togglesKeyboard = true,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .height(44.dp)
                        .padding(end = 0.dp),
            ) {
                if (hasAttachmentAction) {
                    IconButton(
                        onClick = onAttachmentsToggle,
                        enabled = inputContentVisible,
                        modifier =
                            Modifier
                                .size(36.dp)
                                .alpha(if (inputContentVisible) 1f else 0f)
                                .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                    ) {
                        // Swap the glyph on open (X) the way the emoji toggle swaps
                        // to a keyboard, so sighted users get a visual cue, not just
                        // a changed content description.
                        Icon(
                            if (attachmentSheetOpen) Icons.Default.Close else Icons.Default.AttachFile,
                            contentDescription =
                                stringResource(
                                    if (attachmentSheetOpen) R.string.close else R.string.attach_options,
                                ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                trailingAction?.invoke(this)
            }

            if (expandedLayout && inputContentVisible) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(96.dp)
                            .height(48.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        latestOnHeightDrag(dragAmount)
                                    },
                                    onDragEnd = { latestOnHeightDragStopped() },
                                    onDragCancel = { latestOnHeightDragStopped() },
                                )
                            }.semantics {
                                contentDescription = resizeComposerDescription
                            },
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
                IconButton(
                    onClick = onExpansionToggle,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(48.dp),
                ) {
                    Icon(
                        if (expansionMode == ComposerExpansionMode.FullScreen) {
                            Icons.Default.CloseFullscreen
                        } else {
                            Icons.Default.OpenInFull
                        },
                        contentDescription =
                            stringResource(
                                if (expansionMode == ComposerExpansionMode.FullScreen) {
                                    R.string.composer_collapse
                                } else {
                                    R.string.composer_expand_full_screen
                                },
                            ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
