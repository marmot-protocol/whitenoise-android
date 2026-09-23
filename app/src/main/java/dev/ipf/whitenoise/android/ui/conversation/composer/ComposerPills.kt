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
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.scrollBy
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.ui.common.TextEntryEmojiAction
import dev.ipf.whitenoise.android.ui.conversation.ComposerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.composerPreImeBackAction
import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentImageUriOrNull
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val COMPOSER_RESIZE_HANDLE_TAG = "composer-resize-handle"
internal const val COMPOSER_RESIZE_GESTURE_TAG = "composer-resize-gesture"
internal const val COMPOSER_RESIZE_ACCESSIBILITY_TAG = "composer-resize-accessibility"
internal const val COMPOSER_RESIZE_INDICATOR_TAG = "composer-resize-indicator"
internal const val COMPOSER_PILL_SURFACE_TAG = "composer-pill-surface"

private val CompactEditorStartInset = 72.dp
private val EditingEditorStartInset = 14.dp
private val ExpandedEditorEndInset = 14.dp
private val CompactEditorTopInset = 12.dp
private val CompactEditorBottomInset = 12.dp

// The drag strip's visible grip: Material's drag-handle proportions, drawn in the outline colour so it
// reads as chrome rather than content.
private val ComposerResizeHandleWidth = 32.dp
private val ComposerResizeHandleThickness = 4.dp
private val ExpandedEditorBottomInset = 44.dp

private const val COMPOSER_ACTION_CENTER_BIAS = 0.5f

/**
 * Vertical placement for the composer's inline action clusters: centred in the compact one-line row
 * and pinned to the bottom of the editing row, blending between the two with the animated editing
 * progress. The progress is read during placement so the text field never recomposes per frame.
 */
private class ComposerActionRowAlignment(
    private val horizontal: Alignment.Horizontal,
    private val editingProgress: () -> Float,
) : Alignment {
    /** Places the cluster at the horizontal edge and at the progress-weighted vertical bias. */
    override fun align(
        size: IntSize,
        space: IntSize,
        layoutDirection: LayoutDirection,
    ): IntOffset {
        val x = horizontal.align(size.width, space.width, layoutDirection)
        val slack = (space.height - size.height).coerceAtLeast(0)
        val bias = COMPOSER_ACTION_CENTER_BIAS + (1f - COMPOSER_ACTION_CENTER_BIAS) * editingProgress().coerceIn(0f, 1f)
        return IntOffset(x, (slack * bias).roundToInt())
    }
}

/** Interpolates one layout-space distance without allocating an animation object. */
private fun interpolateDp(
    start: Dp,
    end: Dp,
    fraction: Float,
): Dp = start + (end - start) * fraction

/**
 * Padding whose state is read during measurement instead of composition.
 * This lets the composer animate geometry without recomposing BasicTextField
 * on every frame.
 */
private fun Modifier.deferredPadding(
    start: () -> Dp = { 0.dp },
    top: () -> Dp = { 0.dp },
    end: () -> Dp = { 0.dp },
    bottom: () -> Dp = { 0.dp },
): Modifier =
    layout { measurable, constraints ->
        val startPx = start().roundToPx()
        val topPx = top().roundToPx()
        val endPx = end().roundToPx()
        val bottomPx = bottom().roundToPx()
        val horizontal = startPx + endPx
        val vertical = topPx + bottomPx
        val childConstraints =
            Constraints(
                minWidth = (constraints.minWidth - horizontal).coerceAtLeast(0),
                maxWidth = (constraints.maxWidth - horizontal).coerceAtLeast(0),
                minHeight = (constraints.minHeight - vertical).coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - vertical).coerceAtLeast(0),
            )
        val placeable =
            measurable.measure(childConstraints)
        val width = (placeable.width + horizontal).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeable.height + vertical).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placeable.placeRelative(startPx, topPx)
        }
    }

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

/** Maps the original selection into the current transformed text layout. */
private fun composerSelectionLayout(
    layout: TextLayoutResult,
    value: TextFieldValue,
    transformedText: TransformedText,
): ComposerSelectionLayout {
    /** Returns a cursor rectangle after clamping and transforming the original offset. */
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

/** Applies the smallest scroll correction needed to expose [selection]. */
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

/**
 * Reanchors the scroll before the measured editor is placed. The ordinary
 * effect remains the fallback for a layout result delivered after measure,
 * while this path prevents a newly pasted end caret from being painted off
 * screen for the first frame of a composer resize.
 */
private fun Modifier.keepComposerSelectionVisibleDuringLayout(
    scrollState: ScrollState,
    correctionGate: ComposerLayoutCaretCorrectionGate,
    selectionLayout: () -> ComposerSelectionLayout?,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        selectionLayout()?.let { selection ->
            val target =
                composerCaretScrollTarget(
                    currentScroll = scrollState.value,
                    viewportHeight = scrollState.viewportSize,
                    maxScroll = scrollState.maxValue,
                    selection = selection,
                )
            val delta = target - scrollState.value
            // One measure-time dispatch per distinct correction: this pass
            // reads geometry that can be one frame stale, so re-dispatching the
            // same correction against the settled effect-time value ping-pongs
            // the scroll forever and Compose never goes idle. The ordinary
            // effect owns steady-state convergence.
            if (delta != 0 && correctionGate.shouldCorrect(selection, scrollState.viewportSize, scrollState.maxValue)) {
                scrollState.dispatchRawDelta(delta.toFloat())
            }
        }
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }

/** Deduplicates identical measure-time caret corrections; see the caller. */
private class ComposerLayoutCaretCorrectionGate {
    private var lastSelection: ComposerSelectionLayout? = null
    private var lastViewport: Int = -1
    private var lastMaxScroll: Int = -1

    fun shouldCorrect(
        selection: ComposerSelectionLayout,
        viewport: Int,
        maxScroll: Int,
    ): Boolean {
        val changed = selection != lastSelection || viewport != lastViewport || maxScroll != lastMaxScroll
        if (changed) {
            lastSelection = selection
            lastViewport = viewport
            lastMaxScroll = maxScroll
        }
        return changed
    }
}

/**
 * Renders the editable composer pill and coordinates its compact, multiline,
 * and manually expanded geometry. BasicTextField keeps the pill independent
 * of Material's 56dp filled-field minimum.
 */
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
    attachmentMenu: (@Composable (androidx.compose.ui.unit.IntRect) -> Unit)? = null,
    onPickFromGallery: (() -> Unit)?,
    onPickDocument: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDictation: (() -> Unit)? = null,
    dictationControls: (@Composable RowScope.() -> Unit)? = null,
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
    onHeightDragSettled: ((Float) -> Unit)? = null,
    onHeightDragCancelled: (() -> Unit)? = null,
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
    expandedTrailingActionInset: Dp = 0.dp,
    // Automatic expansion changes both this pill's padding and ComposerBar's
    // outer trailing reservation. Measure the threshold against the compact
    // pill width so the chosen mode cannot invalidate its own line count.
    compactMeasurementWidth: Dp? = null,
    compactMeasurementReservesTrailingAction: Boolean = trailingAction != null,
    compactOuterEndInset: Dp = 0.dp,
    forceEditingLayout: Boolean = false,
    compactSingleLineEdit: Boolean = false,
    accessoryContent: (@Composable () -> Unit)? = null,
    voiceReviewContent: (@Composable () -> Unit)? = null,
    inputContentVisible: Boolean = true,
    inputFocusEnabled: Boolean = true,
    onMultilineControlsChanged: (Boolean) -> Unit = {},
    // Compact-height viewports cannot afford the expanded control layout, whose
    // fixed header and action-row overhead consumes the whole compact composer
    // ceiling and squeezes the editor viewport to zero, so they pin the inline
    // single-row controls regardless of the measured line count.
    multilineControlsSuppressed: Boolean = false,
    // Back has asked the keyboard to hide: the editing row collapses now, in the
    // same frame, instead of waiting for focus to clear once the IME inset lands.
    dismissInProgress: Boolean = false,
    // An accepted send has just emptied the field: the pill takes its one-line
    // geometry in the same frame, so the bubble it produced lands where it will
    // stay instead of riding the shrinking pill down.
    collapsedBySend: Boolean = false,
    // Reports that the send collapse has taken its snap so the owner can let
    // later geometry, a dismiss or a refocus of the empty field, tween again.
    onSendCollapseApplied: () -> Unit = {},
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
    val mentionComposition = textFieldValue.composition
    val mentionVisualTransformation =
        remember(highlightMentionChips, chipColor, mentionCandidateLookup, mentionComposition) {
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
                        // Legacy BasicTextField reports the IME's composing-character
                        // bounds through CursorAnchorInfo. A shortened mention label can
                        // map both ends of a non-empty composing range inside the stored
                        // `@npub...` to the same visible offset; Compose then forwards an
                        // empty range to fillBoundingBoxes and crashes. Keep the canonical
                        // text and identity mapping only for that transient composition.
                        // Friendly mention labels return as soon as the IME commits it.
                        val compositionCollapses =
                            mentionComposition?.takeUnless { it.collapsed }?.let { composition ->
                                visual.originalToTransformed(composition.min) >=
                                    visual.originalToTransformed(composition.max)
                            } == true
                        if (compositionCollapses) {
                            return@runCatching TransformedText(text, OffsetMapping.Identity)
                        }
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
    // Reading intent: a deliberate user scroll (touch drag, mouse wheel,
    // trackpad) anchors to the exact draft and selection it happened on and
    // suspends caret-following while that anchor still matches, so the next
    // layout pass cannot snap the viewport back to the caret. Any edit or
    // selection change invalidates the anchor synchronously — the comparison
    // is a plain value check readable during measure — which preserves the
    // paste/dictation/bulk-replacement first-frame caret guarantees.
    var readingScrollAnchor by remember { mutableStateOf<ComposerReadingAnchor?>(null) }
    // The gesture owner below lives in a pointerInput(Unit) block, so it must
    // read the live field value at arm time rather than a stale capture.
    val latestTextFieldValue by rememberUpdatedState(textFieldValue)
    val caretFollowSuspended =
        readingScrollAnchor?.matches(textFieldValue) == true
    SideEffect {
        // A mismatched anchor is retired for good, not merely dormant: an edit
        // that later restores the identical (text, selection) pair — type a
        // character, delete it — must not silently re-suspend caret-following
        // with no live reading intent behind it.
        if (readingScrollAnchor != null && !caretFollowSuspended) readingScrollAnchor = null
    }
    val layoutCorrectionGate = remember { ComposerLayoutCaretCorrectionGate() }
    var textLayoutSnapshot by remember { mutableStateOf<ComposerTextLayoutSnapshot?>(null) }
    val selectionLayout =
        remember(textLayoutSnapshot, textFieldValue.text, textFieldValue.selection, transformedText) {
            textLayoutSnapshot
                ?.takeIf {
                    it.sourceText == textFieldValue.text &&
                        it.transformedText == transformedText
                }?.let { snapshot ->
                    composerSelectionLayout(
                        layout = snapshot.result,
                        value = textFieldValue,
                        transformedText = snapshot.transformedText,
                    )
                }
        }
    LaunchedEffect(
        selectionLayout,
        composerScrollState.viewportSize,
        composerScrollState.maxValue,
        caretFollowSuspended,
    ) {
        if (!caretFollowSuspended) {
            selectionLayout?.let { composerScrollState.keepComposerSelectionVisible(it) }
        }
    }
    var attachmentAnchorBounds by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    val hasAttachmentAction =
        onPickFromGallery != null ||
            onPickDocument != null ||
            hasCameraCapture ||
            hasLocationShare ||
            hasUserShare ||
            hasContactShare
    var multilineControls by remember { mutableStateOf(false) }
    val leadingControlsWidth = if (hasAttachmentAction) 80.dp else 40.dp
    val reservedTrailingWidth =
        (if (expandedTrailingActionInset > 0.dp) expandedTrailingActionInset + 4.dp else 0.dp) +
            (if (trailingAction != null) 40.dp else 0.dp)
    val availableDictationWidth =
        compactMeasurementWidth?.let { width ->
            (width - leadingControlsWidth - reservedTrailingWidth).coerceAtLeast(0.dp)
        } ?: DICTATION_ACTIVE_ACTIONS_WIDTH
    val targetDictationControlWidth =
        when {
            dictationControls != null -> minOf(DICTATION_ACTIVE_ACTIONS_WIDTH, availableDictationWidth)
            onDictation != null -> 40.dp
            else -> 0.dp
        }
    val dictationControlWidth by
        animateDpAsState(
            targetValue = targetDictationControlWidth,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "composer dictation control morph",
        )
    val compactTrailingReserve =
        4.dp + dictationControlWidth + expandedTrailingActionInset +
            (if (trailingAction != null) 40.dp else 0.dp)
    // What the compact trailing reserve leaves over once the control row has taken its share: the 4dp
    // gap before the controls when nothing sits between them, and nothing at all when it does.
    val compactFreeTrailingGutter = if (expandedTrailingActionInset > 0.dp) 0.dp else 4.dp
    val compactMeasurementTrailingReserve =
        4.dp +
            dictationControlWidth +
            expandedTrailingActionInset +
            (if (compactMeasurementReservesTrailingAction) 40.dp else 0.dp)
    val composerTextStyle =
        MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textDirection = TextDirection.ContentOrLtr,
        )
    val textMeasurer = rememberTextMeasurer()
    val editingRequested =
        composerEditingRequested(
            focused = composerFocused,
            hasText = textFieldValue.text.isNotEmpty(),
            forceEditingLayout = forceEditingLayout,
            mode = expansionMode,
            dismissInProgress = dismissInProgress,
        )
    val compactEditStartInset =
        if (compactSingleLineEdit && !hasAttachmentAction) 40.dp else CompactEditorStartInset
    // A short edit can share the compact row with its actions. Measure at that row's actual
    // text width before choosing it; wrapping edits keep the full-width editing layout.
    val editFitsCompactRow =
        compactSingleLineEdit &&
            expansionMode == ComposerExpansionMode.Automatic &&
            compactMeasurementWidth?.let { measurementWidth ->
                val compactWidthPx =
                    with(density) {
                        (measurementWidth - (compactEditStartInset + compactMeasurementTrailingReserve))
                            .coerceAtLeast(1.dp)
                            .roundToPx()
                    }
                remember(transformedText.text, composerTextStyle, compactWidthPx, textMeasurer) {
                    val measuredText =
                        textMeasurer.measure(
                            text = transformedText.text,
                            style = composerTextStyle,
                            constraints = Constraints(maxWidth = compactWidthPx),
                        )
                    measuredText.lineCount == 1
                }
            } == true
    val compactDraftMeasurement =
        compactMeasurementWidth?.let { measurementWidth ->
            val editingWidthPx =
                with(density) {
                    (measurementWidth - (EditingEditorStartInset + ExpandedEditorEndInset))
                        .coerceAtLeast(1.dp)
                        .roundToPx()
                }
            val compactWidthPx =
                with(density) {
                    (measurementWidth - (compactEditStartInset + compactMeasurementTrailingReserve))
                        .coerceAtLeast(1.dp)
                        .roundToPx()
                }
            val startsEditing = editingRequested && !multilineControlsSuppressed && !editFitsCompactRow
            remember(
                transformedText.text,
                composerTextStyle,
                editingWidthPx,
                compactWidthPx,
                compactEditStartInset,
                startsEditing,
                multilineControlsSuppressed,
                textMeasurer,
            ) {
                composerDestinationTextLayout(
                    measurer = textMeasurer,
                    text = transformedText.text,
                    style = composerTextStyle,
                    editingWidthPx = editingWidthPx,
                    compactWidthPx = compactWidthPx,
                    startsEditing = startsEditing,
                    multilineControlsSuppressed = multilineControlsSuppressed,
                )
            }
        }
    val compactTextLayout = compactDraftMeasurement?.layout
    // The crossover is decided by the width the draft is leaving, even when the height targets the
    // width it is arriving at: the destination's own count can be lower and would suppress the change.
    val compactLineCount = compactDraftMeasurement?.crossoverLineCount
    // The pill is also used without a measurement width, where the editor's own layout is the only line
    // count there is. The grip needs one number from whichever path is live.
    var editorLineCount by remember { mutableIntStateOf(1) }
    // The prototype animates the measured text row independently of discrete full-screen resizing.
    // Read frames in measurement so the field and its selection owner are never replaced.
    //
    // The height shares the editing row's clock deliberately. On its own shorter one it settled first,
    // and for the ~60ms the insets kept animating it asserted a height the narrower editor could not
    // honour yet — a draft that wraps to three compact lines but two editing ones sat in a two-line box
    // with a third still in it. Finishing together, the height only claims to be final once the width is.
    val animatedTextHeight =
        animateIntAsState(
            targetValue = compactTextLayout?.size?.height ?: 0,
            animationSpec =
                composerGeometrySpec(
                    collapsedBySend = collapsedBySend,
                    durationMillis = COMPOSER_EXPANSION_ANIMATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            label = "composer text height",
        )
    val automaticTextHeight =
        if (compactTextLayout != null &&
            expansionMode == ComposerExpansionMode.Automatic &&
            !multilineControlsSuppressed
        ) {
            Modifier.layout { measurable, constraints ->
                val height = animatedTextHeight.value.coerceIn(constraints.minHeight, constraints.maxHeight)
                val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }
        } else {
            Modifier
        }
    val visualMultilineControls =
        when {
            multilineControlsSuppressed -> false
            else ->
                compactLineCount?.let { lineCount ->
                    when {
                        multilineControls && lineCount <= 1 -> false
                        !multilineControls && lineCount >= COMPOSER_MULTILINE_CONTROL_LINES -> true
                        else -> multilineControls
                    }
                } ?: multilineControls
        }
    val expandedLayout = visualMultilineControls || expansionMode != ComposerExpansionMode.Automatic

    // The grip marks a border that can be dragged, and the drag target exists from the second line on.
    // Tying it to the multiline controls hid it until the third line, leaving a resizable border unmarked
    // for exactly the drafts a reader is most likely to want smaller. Only the one-line row, which has
    // nothing to shrink, goes without.
    val composerCanResize =
        expansionMode != ComposerExpansionMode.Automatic || (compactLineCount ?: editorLineCount) > 1
    // Keep the editor instance and selection owner stable while empty reading
    // mode unfolds into the full-width editing row above the native controls.
    val editingLayout =
        !multilineControlsSuppressed &&
            ((editingRequested && !editFitsCompactRow) || expandedLayout)
    val editingProgress =
        animateFloatAsState(
            targetValue = if (editingLayout) 1f else 0f,
            animationSpec =
                composerGeometrySpec(
                    collapsedBySend = collapsedBySend,
                    durationMillis = COMPOSER_EXPANSION_ANIMATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            label = "composer editing row",
        )
    // The compact one-line row centres its inline actions; as the editing row
    // unfolds they slide down to the bottom action row. Read during placement.
    val leadingActionsAlignment =
        remember(editingProgress) { ComposerActionRowAlignment(Alignment.Start) { editingProgress.value } }
    val trailingActionsAlignment =
        remember(editingProgress) { ComposerActionRowAlignment(Alignment.End) { editingProgress.value } }
    // The editor and action edges animate without reserving space above the surface.
    val expansionProgress =
        animateFloatAsState(
            targetValue = if (expandedLayout) 1f else 0f,
            animationSpec =
                composerGeometrySpec(
                    collapsedBySend = collapsedBySend,
                    durationMillis = COMPOSER_EXPANSION_ANIMATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            label = "composer layout progress",
        )
    // Release the one-shot send collapse once every piece of geometry has
    // reached the target it snapped to, so a later dismiss or refocus of the
    // empty field tweens again. Geometry the send left unchanged settles at once.
    val latestOnSendCollapseApplied by rememberUpdatedState(onSendCollapseApplied)
    val textHeightTarget by rememberUpdatedState(compactTextLayout?.size?.height ?: 0)
    val editingTarget by rememberUpdatedState(if (editingLayout) 1f else 0f)
    val expansionTarget by rememberUpdatedState(if (expandedLayout) 1f else 0f)
    LaunchedEffect(collapsedBySend) {
        if (!collapsedBySend) return@LaunchedEffect
        snapshotFlow {
            animatedTextHeight.value == textHeightTarget &&
                editingProgress.value == editingTarget &&
                expansionProgress.value == expansionTarget
        }.first { settled -> settled }
        latestOnSendCollapseApplied()
    }
    val toggleDescription =
        stringResource(
            if (expansionMode != ComposerExpansionMode.Automatic) {
                R.string.composer_collapse
            } else {
                R.string.composer_expand_full_screen
            },
        )
    val latestOnExpansionToggle by rememberUpdatedState(onExpansionToggle)
    val expandedHeightModifier =
        if (expansionMode == ComposerExpansionMode.Automatic) {
            Modifier
        } else {
            Modifier.fillMaxHeight()
        }

    /** Applies the one-line/three-line hysteresis to the measured editor line count. */
    fun updateMultilineControls(lineCount: Int) {
        val nextMultilineControls =
            when {
                multilineControlsSuppressed -> false
                multilineControls && lineCount <= 1 -> false
                !multilineControls && lineCount >= COMPOSER_MULTILINE_CONTROL_LINES -> true
                else -> multilineControls
            }
        if (nextMultilineControls != multilineControls) {
            multilineControls = nextMultilineControls
            onMultilineControlsChanged(nextMultilineControls)
        }
    }

    SideEffect {
        if ((compactLineCount != null || multilineControlsSuppressed) && visualMultilineControls != multilineControls) {
            multilineControls = visualMultilineControls
            onMultilineControlsChanged(visualMultilineControls)
        }
    }

    Box(
        modifier =
            modifier.deferredPadding(
                end = {
                    interpolateDp(
                        compactOuterEndInset,
                        0.dp,
                        expansionProgress.value,
                    )
                },
            ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(expandedHeightModifier)
                    .testTag(COMPOSER_PILL_SURFACE_TAG),
        ) {
            Column(modifier = Modifier.then(expandedHeightModifier)) {
                if (accessoryContent != null) {
                    Box(
                        Modifier.boundedComposerAccessory().verticalScroll(rememberScrollState()),
                    ) {
                        accessoryContent()
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f, fill = expansionMode != ComposerExpansionMode.Automatic)
                            .heightIn(min = if (voiceReviewContent == null) 48.dp else 96.dp)
                            .then(expandedHeightModifier),
                ) {
                    Box(
                        contentAlignment = Alignment.TopStart,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .then(expandedHeightModifier)
                                // The text field's internal handlers consume plain
                                // drags without ever scrolling this height-capped
                                // viewport, so the editor's one explicit scroll owner
                                // lives here, covering the whole editor viewport:
                                // early vertical drags and wheel/trackpad ticks drive
                                // composerScrollState directly and arm reading
                                // intent, while taps and long-press selection pass
                                // through untouched.
                                .pointerInput(Unit) {
                                    composerEditorReadingScrollGestures(
                                        scrollBy = { delta ->
                                            val before = composerScrollState.value
                                            composerScrollState.dispatchRawDelta(delta)
                                            composerScrollState.value != before
                                        },
                                        onReadingScroll = {
                                            // A non-overflowing editor has nothing to
                                            // read toward; arming would only suspend
                                            // caret-following for no scroll intent.
                                            if (composerScrollState.maxValue > 0) {
                                                readingScrollAnchor = ComposerReadingAnchor.of(latestTextFieldValue)
                                            }
                                        },
                                    )
                                }.deferredPadding(
                                    start = {
                                        interpolateDp(
                                            if (hasAttachmentAction) CompactEditorStartInset else 40.dp,
                                            EditingEditorStartInset,
                                            editingProgress.value,
                                        )
                                    },
                                    top = {
                                        CompactEditorTopInset
                                    },
                                    end = {
                                        interpolateDp(
                                            compactTrailingReserve,
                                            ExpandedEditorEndInset,
                                            editingProgress.value,
                                        )
                                    },
                                    bottom = {
                                        interpolateDp(
                                            CompactEditorBottomInset,
                                            ExpandedEditorBottomInset,
                                            editingProgress.value,
                                        )
                                    },
                                ).alpha(if (inputContentVisible) 1f else 0f)
                                .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                    ) {
                        val editorOverflowColor = composerOverflowIndicatorColor()
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = onValueChange,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(expandedHeightModifier)
                                    .then(automaticTextHeight)
                                    // Drawn outside the scroll modifier so the thumb
                                    // paints in viewport coordinates over the clipped
                                    // editor, only while the draft overflows it.
                                    .drawWithContent {
                                        drawContent()
                                        drawComposerEditorOverflowAffordance(
                                            scrollValue = composerScrollState.value,
                                            maxScroll = composerScrollState.maxValue,
                                            color = editorOverflowColor,
                                            // Painted in the inset the editor already leaves, so the
                                            // thumb never covers a glyph and the row keeps its width.
                                            // The compact row reserves its trailing space for the
                                            // dictation and send controls, so only the gap before them
                                            // is free; without one the helper falls back inside the
                                            // editor rather than painting over a control.
                                            outerGutterPx =
                                                interpolateDp(
                                                    compactFreeTrailingGutter,
                                                    ExpandedEditorEndInset,
                                                    editingProgress.value,
                                                ).toPx(),
                                        )
                                    }.keepComposerSelectionVisibleDuringLayout(
                                        composerScrollState,
                                        layoutCorrectionGate,
                                    ) {
                                        if (readingScrollAnchor?.matches(textFieldValue) == true) {
                                            return@keepComposerSelectionVisibleDuringLayout null
                                        }
                                        textLayoutSnapshot
                                            ?.takeIf {
                                                it.sourceText == textFieldValue.text &&
                                                    it.transformedText == transformedText
                                            }?.let { snapshot ->
                                                composerSelectionLayout(
                                                    layout = snapshot.result,
                                                    value = textFieldValue,
                                                    transformedText = snapshot.transformedText,
                                                )
                                            }
                                    }.semantics {
                                        // Accessibility scrolls are reading intent too:
                                        // arm the anchor before moving the shared state,
                                        // overriding verticalScroll's un-anchored action.
                                        scrollBy { _, y ->
                                            // Report success and arm reading intent
                                            // only when the viewport actually moved:
                                            // a boundary or zero-delta action must
                                            // let the service announce the edge or
                                            // move to another scroll container.
                                            val before = composerScrollState.value
                                            composerScrollState.dispatchRawDelta(y)
                                            val moved = composerScrollState.value != before
                                            if (moved) {
                                                readingScrollAnchor = ComposerReadingAnchor.of(textFieldValue)
                                            }
                                            moved
                                        }
                                    }
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
                                editorLineCount = layout.lineCount
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
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Box(
                        modifier =
                            Modifier
                                .align(leadingActionsAlignment)
                                .deferredPadding(
                                    start = { 0.dp },
                                ).alpha(if (inputContentVisible) 1f else 0f)
                                .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hasAttachmentAction) {
                                Box(
                                    modifier =
                                        Modifier.width(40.dp).height(48.dp).onGloballyPositioned { coordinates ->
                                            val position = coordinates.positionInWindow()
                                            attachmentAnchorBounds =
                                                androidx.compose.ui.unit.IntRect(
                                                    position.x.roundToInt(),
                                                    position.y.roundToInt(),
                                                    position.x.roundToInt() + coordinates.size.width,
                                                    position.y.roundToInt() + coordinates.size.height,
                                                )
                                        },
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    IconButton(
                                        onClick = onAttachmentsToggle,
                                        enabled = inputContentVisible,
                                        modifier =
                                            Modifier
                                                .width(32.dp)
                                                .height(48.dp)
                                                .alpha(if (inputContentVisible) 1f else 0f)
                                                .then(
                                                    if (inputContentVisible) {
                                                        Modifier
                                                    } else {
                                                        Modifier.clearAndSetSemantics {}
                                                    },
                                                ),
                                    ) {
                                        // Swap the glyph on open (X) the way the emoji toggle swaps
                                        // to a keyboard, so sighted users get a visual cue, not just
                                        // a changed content description.
                                        Icon(
                                            painter =
                                                painterResource(
                                                    if (attachmentSheetOpen) R.drawable.ic_close else R.drawable.ic_add,
                                                ),
                                            contentDescription =
                                                stringResource(
                                                    if (attachmentSheetOpen) {
                                                        R.string.close
                                                    } else {
                                                        R.string.attach_options
                                                    },
                                                ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    attachmentMenu?.invoke(attachmentAnchorBounds)
                                }
                            }
                            TextEntryEmojiAction(
                                pickerOpen = emojiPickerOpen,
                                enabled = inputContentVisible,
                                onClick = onEmojiPickerToggle,
                                togglesKeyboard = true,
                                modifier = Modifier.width(32.dp).height(48.dp),
                                iconSize = 24.dp,
                                emojiIcon = painterResource(R.drawable.ic_emoji_smileys),
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .align(
                                    if (voiceReviewContent == null) trailingActionsAlignment else Alignment.BottomEnd,
                                ).height(48.dp),
                    ) {
                        if (dictationControls != null) {
                            Row(
                                Modifier.layout { measurable, constraints ->
                                    val reserved = (leadingControlsWidth + reservedTrailingWidth).roundToPx()
                                    val available = (constraints.maxWidth - reserved).coerceAtLeast(0)
                                    val maximum = minOf(DICTATION_ACTIVE_ACTIONS_WIDTH.roundToPx(), available)
                                    val child = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maximum))
                                    layout(child.width, child.height) { child.placeRelative(0, 0) }
                                },
                            ) { dictationControls() }
                        } else if (onDictation != null) {
                            IconButton(
                                onClick = onDictation,
                                enabled = inputContentVisible,
                                modifier =
                                    Modifier
                                        .width(40.dp)
                                        .height(48.dp)
                                        .alpha(if (inputContentVisible) 1f else 0f)
                                        .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                            ) {
                                // The prototype's microphone is dictation; voice notes use the waveform glyph.
                                Icon(
                                    painter = painterResource(R.drawable.ic_mic),
                                    contentDescription = stringResource(R.string.dictate_text),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        if (expandedTrailingActionInset > 0.dp) {
                            Spacer(
                                Modifier.width(expandedTrailingActionInset + 4.dp),
                            )
                        }
                        trailingAction?.invoke(this)
                    }
                    if (voiceReviewContent != null) {
                        Box(Modifier.matchParentSize()) { voiceReviewContent() }
                    }
                }
            }
        }

        if (inputContentVisible && !multilineControlsSuppressed) {
            if (composerCanResize) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG)
                            .semantics {
                                contentDescription = resizeComposerDescription
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction(toggleDescription) {
                                            latestOnExpansionToggle()
                                            true
                                        },
                                    )
                            },
                )
            }
            // This existing padding contains no editor or accessory content. A
            // border-only pointer owner leaves reading drags and selection to
            // BasicTextField; a separate semantics leaf exposes the accessible action.
            ComposerResizeGestureStrip(
                showHandle = composerCanResize,
                onHeightDragStarted = { latestOnHeightDragStarted() },
                onHeightDrag = { latestOnHeightDrag(it) },
                onHeightDragStopped = { latestOnHeightDragStopped() },
                onHeightDragSettled = onHeightDragSettled,
                onHeightDragCancelled = onHeightDragCancelled,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** Keeps accessory overflow scrollable while reserving at least half the finite viewport for input. */
private fun Modifier.boundedComposerAccessory(): Modifier =
    layout { measurable, constraints ->
        val limit = if (constraints.hasBoundedHeight) constraints.maxHeight / 2 else constraints.maxHeight
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = limit))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/** Resizes from the existing top border without covering editable or accessory content. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
private fun ComposerResizeGestureStrip(
    showHandle: Boolean,
    onHeightDragStarted: () -> Unit,
    onHeightDrag: (Float) -> Unit,
    onHeightDragStopped: () -> Unit,
    onHeightDragSettled: ((Float) -> Unit)?,
    onHeightDragCancelled: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val latestOnHeightDragStarted by rememberUpdatedState(onHeightDragStarted)
    val latestOnHeightDrag by rememberUpdatedState(onHeightDrag)
    val latestOnHeightDragStopped by rememberUpdatedState(onHeightDragStopped)
    val latestOnHeightDragSettled by rememberUpdatedState(onHeightDragSettled)
    val latestOnHeightDragCancelled by rememberUpdatedState(onHeightDragCancelled)
    var gestureCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(8.dp)
                .testTag(COMPOSER_RESIZE_GESTURE_TAG)
                .onGloballyPositioned { gestureCoordinates = it }
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            velocityTracker.resetTracking()
                            latestOnHeightDragStarted()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            val rootPosition = gestureCoordinates?.localToRoot(change.position) ?: change.position
                            velocityTracker.addPosition(change.uptimeMillis, rootPosition)
                            change.consume()
                            latestOnHeightDrag(dragAmount)
                        },
                        onDragEnd = {
                            val settle = latestOnHeightDragSettled
                            if (settle != null) {
                                settle(velocityTracker.calculateVelocity().y)
                            } else {
                                latestOnHeightDragStopped()
                            }
                        },
                        onDragCancel = {
                            val cancel = latestOnHeightDragCancelled
                            if (cancel != null) cancel() else latestOnHeightDragStopped()
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        // Drawn only once the composer is tall enough to resize; the one-line composer has nothing to drag.
        if (showHandle) {
            Box(
                Modifier
                    .size(width = ComposerResizeHandleWidth, height = ComposerResizeHandleThickness)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag(COMPOSER_RESIZE_HANDLE_TAG),
            )
        }
    }
}

/** Resolves an opaque editor overflow indicator against the current composer surface. */
@Composable
private fun composerOverflowIndicatorColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = 0.45f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        .copy(alpha = 1f)

/** Registers the focused composer ahead of the IME for Android predictive/system Back. */
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
