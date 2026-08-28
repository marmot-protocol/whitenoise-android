package dev.ipf.whitenoise.android.ui.conversation.composer

import android.net.Uri
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import kotlin.math.ceil
import kotlin.math.floor

internal const val COMPOSER_RESIZE_INDICATOR_TAG = "composer-resize-indicator"
internal const val COMPOSER_PILL_SURFACE_TAG = "composer-pill-surface"

internal data class ComposerSelectionLayout(
    val top: Float,
    val bottom: Float,
    val activeCaretTop: Float,
    val activeCaretBottom: Float,
)

private data class ComposerTextLayoutSnapshot(
    val sourceText: String,
    val transformedText: TransformedText,
    val result: TextLayoutResult,
)

/**
 * Returns the smallest scroll offset that exposes the current selection. A
 * selection that fits is kept wholly visible; a selection taller than the
 * viewport follows its active edge instead of jumping to the end of the
 * draft. Returning the current offset is intentional when no motion is
 * needed, which keeps bulk IME commits from fighting the timeline re-anchor.
 */
internal fun composerCaretScrollTarget(
    currentScroll: Int,
    viewportHeight: Int,
    maxScroll: Int,
    selection: ComposerSelectionLayout,
): Int {
    if (viewportHeight <= 0 || maxScroll <= 0) return currentScroll.coerceIn(0, maxScroll.coerceAtLeast(0))

    val viewportTop = currentScroll.toFloat()
    val viewportBottom = viewportTop + viewportHeight
    val selectionHeight = (selection.bottom - selection.top).coerceAtLeast(0f)
    val desired =
        if (selectionHeight <= viewportHeight) {
            when {
                selection.top < viewportTop -> floor(selection.top).toInt()
                selection.bottom > viewportBottom -> ceil(selection.bottom - viewportHeight).toInt()
                else -> currentScroll
            }
        } else {
            when {
                selection.activeCaretTop < viewportTop -> floor(selection.activeCaretTop).toInt()
                selection.activeCaretBottom > viewportBottom ->
                    ceil(selection.activeCaretBottom - viewportHeight).toInt()
                else -> currentScroll
            }
        }
    return desired.coerceIn(0, maxScroll)
}

private fun composerSelectionLayout(
    layout: TextLayoutResult,
    value: TextFieldValue,
    transformedText: TransformedText,
): ComposerSelectionLayout {
    fun cursorRect(originalOffset: Int) =
        layout.getCursorRect(
            transformedText.offsetMapping
                .originalToTransformed(originalOffset.coerceIn(0, value.text.length))
                .coerceIn(0, transformedText.text.length),
        )

    val start = cursorRect(value.selection.start)
    val active = cursorRect(value.selection.end)
    return ComposerSelectionLayout(
        top = minOf(start.top, active.top),
        bottom = maxOf(start.bottom, active.bottom),
        activeCaretTop = active.top,
        activeCaretBottom = active.bottom,
    )
}

private suspend fun ScrollState.keepComposerSelectionVisible(selection: ComposerSelectionLayout) {
    val target =
        composerCaretScrollTarget(
            currentScroll = value,
            viewportHeight = viewportSize,
            maxScroll = maxValue,
            selection = selection,
        )
    if (target != value) scrollTo(target)
}

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
    onDictation: (() -> Unit)? = null,
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
    onHeightDragStarted: () -> Unit = {},
    onHeightDrag: (Float) -> Unit = {},
    onHeightDragStopped: () -> Unit = {},
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
    // Automatic expansion changes both this pill's padding and ComposerBar's
    // outer trailing reservation. Measure the threshold against the compact
    // pill width so the chosen mode cannot invalidate its own line count.
    compactMeasurementWidth: Dp? = null,
    compactMeasurementReservesTrailingAction: Boolean = trailingAction != null,
    inputContentVisible: Boolean = true,
    inputFocusEnabled: Boolean = true,
    onMultilineControlsChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val resizeComposerDescription = stringResource(R.string.composer_resize)
    val latestOnPasteImageUris by rememberUpdatedState(onPasteImageUris)
    val latestOnPreImeBack by rememberUpdatedState(onPreImeBack)
    val latestOnHeightDragStarted by rememberUpdatedState(onHeightDragStarted)
    val latestOnHeightDrag by rememberUpdatedState(onHeightDrag)
    val latestOnHeightDragStopped by rememberUpdatedState(onHeightDragStopped)
    var composerFocused by remember { mutableStateOf(false) }
    // Gesture/predictive Back reaches the IME before the activity's ordinary
    // BackHandler. While this field owns focus, register ahead of the IME so an
    // explicit Back clears focus; IME-only geometry changes (including a
    // keyboard-to-voice handoff) never invoke this callback.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ComposerPlatformPreImeBackHandler(
            enabled = preImeBackEnabled && composerFocused,
            onBack = { latestOnPreImeBack() },
            overlayBackRegistrar = overlayBackRegistrar,
        )
    } else {
        BackHandler(enabled = preImeBackEnabled && composerFocused) { latestOnPreImeBack() }
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
    val transformedText =
        remember(textFieldValue.text, mentionVisualTransformation) {
            mentionVisualTransformation.filter(AnnotatedString(textFieldValue.text))
        }
    val composerScrollState = rememberScrollState()
    var textLayoutSnapshot by remember { mutableStateOf<ComposerTextLayoutSnapshot?>(null) }
    val selectionLayout =
        remember(textLayoutSnapshot, textFieldValue.text, textFieldValue.selection) {
            textLayoutSnapshot
                ?.takeIf { it.sourceText == textFieldValue.text }
                ?.let { snapshot ->
                    composerSelectionLayout(
                        layout = snapshot.result,
                        value = textFieldValue,
                        transformedText = snapshot.transformedText,
                    )
                }
        }
    LaunchedEffect(selectionLayout, composerScrollState.viewportSize, composerScrollState.maxValue) {
        selectionLayout?.let { composerScrollState.keepComposerSelectionVisible(it) }
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
            (if (onDictation != null) 48.dp else 0.dp) +
            (if (hasAttachmentAction) 36.dp else 0.dp) +
            (if (trailingAction != null) 44.dp else 0.dp)
    val compactMeasurementTrailingReserve =
        4.dp +
            (if (onDictation != null) 48.dp else 0.dp) +
            (if (hasAttachmentAction) 36.dp else 0.dp) +
            (if (compactMeasurementReservesTrailingAction) 44.dp else 0.dp)
    val expansionAnimationSpec =
        tween<Dp>(
            durationMillis = COMPOSER_EXPANSION_ANIMATION_MILLIS,
            easing = FastOutSlowInEasing,
        )
    val borderHeaderInset by
        animateDpAsState(
            targetValue = if (expandedLayout) 28.dp else 0.dp,
            animationSpec = expansionAnimationSpec,
            label = "composer border header inset",
        )
    val editorStartInset by
        animateDpAsState(
            targetValue = if (expandedLayout) 12.dp else 52.dp,
            animationSpec = expansionAnimationSpec,
            label = "composer editor start inset",
        )
    val editorTopInset by
        animateDpAsState(
            targetValue = if (expandedLayout) 20.dp else 10.dp,
            animationSpec = expansionAnimationSpec,
            label = "composer editor top inset",
        )
    val editorEndInset by
        animateDpAsState(
            targetValue = if (expandedLayout) 12.dp else compactTrailingReserve,
            animationSpec = expansionAnimationSpec,
            label = "composer editor end inset",
        )
    val editorBottomInset by
        animateDpAsState(
            targetValue = if (expandedLayout) 48.dp else 10.dp,
            animationSpec = expansionAnimationSpec,
            label = "composer editor bottom inset",
        )
    val emojiBottomInset by
        animateDpAsState(
            targetValue = if (expandedLayout) 4.dp else 0.dp,
            animationSpec = expansionAnimationSpec,
            label = "composer emoji bottom inset",
        )
    // The expanded resize layer occupies the same coordinates as the first
    // line while its header padding is still animating out of compact mode.
    // Do not expose or draw that layer until the editor has cleared its full
    // 48dp target; otherwise a quick caret/selection gesture can resize the
    // composer during the transition.
    val resizeTargetReady = borderHeaderInset == 28.dp && editorTopInset == 20.dp
    val composerTextStyle =
        LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            textDirection = TextDirection.ContentOrLtr,
        )
    val textMeasurer = rememberTextMeasurer()
    val compactLineCount =
        compactMeasurementWidth?.let { measurementWidth ->
            val maxTextWidthPx =
                with(density) {
                    (measurementWidth - 52.dp - compactMeasurementTrailingReserve)
                        .coerceAtLeast(1.dp)
                        .roundToPx()
                }
            remember(
                transformedText.text,
                composerTextStyle,
                maxTextWidthPx,
                textMeasurer,
            ) {
                textMeasurer
                    .measure(
                        text = transformedText.text,
                        style = composerTextStyle,
                        constraints = Constraints(maxWidth = maxTextWidthPx),
                    ).lineCount
            }
        }
    val expandedHeightModifier =
        if (expansionMode == ComposerExpansionMode.Automatic) {
            Modifier
        } else {
            Modifier.fillMaxHeight()
        }

    fun updateMultilineControls(lineCount: Int) {
        val nextMultilineControls =
            when {
                multilineControls && lineCount <= 1 -> false
                !multilineControls && lineCount >= 3 -> true
                else -> multilineControls
            }
        if (nextMultilineControls != multilineControls) {
            multilineControls = nextMultilineControls
            onMultilineControlsChanged(nextMultilineControls)
        }
    }

    if (compactLineCount != null) {
        LaunchedEffect(compactLineCount) {
            updateMultilineControls(compactLineCount)
        }
    }

    Box(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(22.dp),
            border = amoledSurfaceBorderStroke(),
            modifier =
                Modifier
                    .padding(top = borderHeaderInset)
                    .fillMaxWidth()
                    .then(expandedHeightModifier)
                    .testTag(COMPOSER_PILL_SURFACE_TAG),
        ) {
            val boxAlignment = if (expandedLayout) Alignment.TopStart else Alignment.CenterStart
            Box(
                modifier =
                    Modifier
                        .heightIn(min = 44.dp)
                        .then(expandedHeightModifier),
            ) {
                Box(
                    contentAlignment = boxAlignment,
                    modifier =
                        Modifier
                            .align(boxAlignment)
                            .fillMaxWidth()
                            .then(expandedHeightModifier)
                            .padding(
                                start = editorStartInset,
                                top = editorTopInset,
                                end = editorEndInset,
                                bottom = editorBottomInset,
                            ).alpha(if (inputContentVisible) 1f else 0f)
                            .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onValueChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(expandedHeightModifier)
                                // The automatic composer has a hard viewport ceiling.
                                // Measure the editor at its natural height and own the
                                // resulting scroll state here so programmatic bulk
                                // commits can follow the real selection, not merely
                                // the final text line or the conversation tail.
                                .verticalScroll(composerScrollState)
                                .focusProperties { canFocus = inputFocusEnabled }
                                .contentReceiver(pasteImageReceiver)
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
                        textStyle = composerTextStyle,
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
                            if (compactLineCount == null) updateMultilineControls(layout.lineCount)
                            val nextSnapshot =
                                ComposerTextLayoutSnapshot(
                                    sourceText = textFieldValue.text,
                                    transformedText = transformedText,
                                    result = layout,
                                )
                            if (textLayoutSnapshot != nextSnapshot) textLayoutSnapshot = nextSnapshot
                        },
                    )
                    if (textFieldValue.text.isEmpty()) {
                        Text(
                            stringResource(R.string.message),
                            style = LocalTextStyle.current.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(
                                start = 4.dp,
                                bottom = emojiBottomInset,
                            ).alpha(if (inputContentVisible) 1f else 0f)
                            .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    TextEntryEmojiAction(
                        pickerOpen = emojiPickerOpen,
                        enabled = inputContentVisible,
                        onClick = onEmojiPickerToggle,
                        togglesKeyboard = true,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .height(if (onDictation != null) 48.dp else 44.dp),
                ) {
                    if (onDictation != null) {
                        IconButton(
                            onClick = onDictation,
                            enabled = inputContentVisible,
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .alpha(if (inputContentVisible) 1f else 0f)
                                    .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                        ) {
                            // A waveform keeps text dictation visually distinct from
                            // the plain microphone used by hold-to-record voice notes.
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = stringResource(R.string.dictate_text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
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
            }
        }

        if (expandedLayout && resizeTargetReady && inputContentVisible) {
            // Keep a transparent 96x48dp gesture target for accessibility, but
            // draw feedback only on the visible handle. The surface starts at
            // 28dp so the opaque 4dp handle sits wholly above its border while
            // the editor starts exactly below the target with only 20dp of
            // internal top padding.
            val toggleDescription =
                stringResource(
                    if (expansionMode == ComposerExpansionMode.FullScreen) {
                        R.string.composer_collapse
                    } else {
                        R.string.composer_expand_full_screen
                    },
                )
            ComposerResizeHandle(
                toggleDescription = toggleDescription,
                contentDescription = resizeComposerDescription,
                onExpansionToggle = onExpansionToggle,
                onHeightDragStarted = { latestOnHeightDragStarted() },
                onHeightDrag = { latestOnHeightDrag(it) },
                onHeightDragStopped = { latestOnHeightDragStopped() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ComposerResizeHandle(
    toggleDescription: String,
    contentDescription: String,
    onExpansionToggle: () -> Unit,
    onHeightDragStarted: () -> Unit,
    onHeightDrag: (Float) -> Unit,
    onHeightDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnHeightDragStarted by rememberUpdatedState(onHeightDragStarted)
    val latestOnHeightDrag by rememberUpdatedState(onHeightDrag)
    val latestOnHeightDragStopped by rememberUpdatedState(onHeightDragStopped)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var dragging by remember { mutableStateOf(false) }
    val handleScale =
        animateFloatAsState(
            targetValue = if (pressed || dragging) 1.22f else 1f,
            animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
            label = "composer resize handle scale",
        )
    val handleColor = composerResizeHandleColor()

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .width(96.dp)
                .height(48.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            latestOnHeightDragStarted()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            latestOnHeightDrag(dragAmount)
                        },
                        onDragEnd = {
                            dragging = false
                            latestOnHeightDragStopped()
                        },
                        onDragCancel = {
                            dragging = false
                            latestOnHeightDragStopped()
                        },
                    )
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClickLabel = toggleDescription,
                    role = Role.Button,
                    onClick = onExpansionToggle,
                ).semantics {
                    this.contentDescription = contentDescription
                },
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .testTag(COMPOSER_RESIZE_INDICATOR_TAG)
                .graphicsLayer { scaleX = handleScale.value }
                .background(handleColor, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun composerResizeHandleColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = 0.45f)
        .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
        .copy(alpha = 1f)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
@Suppress("FunctionNaming")
private fun ComposerPlatformPreImeBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
    overlayBackRegistrar: ComposerOverlayBackRegistrar?,
) {
    val latestOnBack by rememberUpdatedState(onBack)
    val backDispatcher = LocalView.current.findOnBackInvokedDispatcher()
    DisposableEffect(enabled, backDispatcher, overlayBackRegistrar) {
        val unregister =
            if (enabled) {
                val callback = OnBackInvokedCallback { latestOnBack() }
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
}
