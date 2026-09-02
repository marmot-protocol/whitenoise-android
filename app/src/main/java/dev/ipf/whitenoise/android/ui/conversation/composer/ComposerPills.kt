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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.floor

internal const val COMPOSER_RESIZE_INDICATOR_TAG = "composer-resize-indicator"
internal const val COMPOSER_PILL_SURFACE_TAG = "composer-pill-surface"

private val ExpandedBorderHeaderInset = 24.dp
private val CompactEditorStartInset = 52.dp
private val ExpandedEditorEndInset = 12.dp
private val CompactEditorTopInset = 12.dp
private val ExpandedEditorTopInset = 24.dp
private val CompactEditorBottomInset = 8.dp
private val ExpandedEditorBottomInset = 48.dp

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
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
    expandedTrailingActionInset: Dp = 0.dp,
    // Automatic expansion changes both this pill's padding and ComposerBar's
    // outer trailing reservation. Measure the threshold against the compact
    // pill width so the chosen mode cannot invalidate its own line count.
    compactMeasurementWidth: Dp? = null,
    compactMeasurementReservesTrailingAction: Boolean = trailingAction != null,
    compactOuterEndInset: Dp = 0.dp,
    inputContentVisible: Boolean = true,
    inputFocusEnabled: Boolean = true,
    onMultilineControlsChanged: (Boolean) -> Unit = {},
    // Compact-height viewports cannot afford the expanded control layout, whose
    // fixed header and action-row overhead consumes the whole compact composer
    // ceiling and squeezes the editor viewport to zero, so they pin the inline
    // single-row controls regardless of the measured line count.
    multilineControlsSuppressed: Boolean = false,
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
    val hasAttachmentAction =
        onPickFromGallery != null ||
            onPickDocument != null ||
            hasCameraCapture ||
            hasLocationShare ||
            hasUserShare ||
            hasContactShare
    var multilineControls by remember { mutableStateOf(false) }
    val targetDictationControlWidth =
        when {
            dictationControls != null -> 96.dp
            onDictation != null -> 48.dp
            else -> 0.dp
        }
    val dictationControlWidth by
        animateDpAsState(
            targetValue = targetDictationControlWidth,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "composer dictation control morph",
        )
    val compactTrailingReserve =
        4.dp +
            dictationControlWidth +
            (if (hasAttachmentAction) 36.dp else 0.dp) +
            (if (trailingAction != null) 44.dp else 0.dp)
    val compactMeasurementTrailingReserve =
        4.dp +
            dictationControlWidth +
            (if (hasAttachmentAction) 36.dp else 0.dp) +
            (if (compactMeasurementReservesTrailingAction) 44.dp else 0.dp)
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
                    (measurementWidth - CompactEditorStartInset - compactMeasurementTrailingReserve)
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
    val visualMultilineControls =
        when {
            multilineControlsSuppressed -> false
            else ->
                compactLineCount?.let { lineCount ->
                    when {
                        multilineControls && lineCount <= 1 -> false
                        !multilineControls && lineCount >= 3 -> true
                        else -> multilineControls
                    }
                } ?: multilineControls
        }
    val expandedLayout = visualMultilineControls || expansionMode != ComposerExpansionMode.Automatic
    // One progress value owns the moving editor and action edges. The handle's
    // 24dp border reservation is installed atomically when expansion starts;
    // animating that constraint made the already-expanded pill lose 24dp of
    // viewport height while a bulk replacement was settling.
    val expansionProgress =
        animateFloatAsState(
            targetValue = if (expandedLayout) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = COMPOSER_EXPANSION_ANIMATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            label = "composer layout progress",
        )
    var resizeTargetReady by remember { mutableStateOf(false) }
    LaunchedEffect(expandedLayout) {
        resizeTargetReady = false
        if (expandedLayout) {
            delay(COMPOSER_EXPANSION_ANIMATION_MILLIS.toLong())
            resizeTargetReady = true
        }
    }
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
                !multilineControls && lineCount >= 3 -> true
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
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(22.dp),
            border = amoledSurfaceBorderStroke(),
            modifier =
                Modifier
                    .deferredPadding(
                        top = {
                            if (expandedLayout || expansionProgress.value > 0f) {
                                ExpandedBorderHeaderInset
                            } else {
                                0.dp
                            }
                        },
                    ).fillMaxWidth()
                    .then(expandedHeightModifier)
                    .testTag(COMPOSER_PILL_SURFACE_TAG),
        ) {
            Box(
                modifier =
                    Modifier
                        .heightIn(min = 44.dp)
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
                                // Keep the editable text on one stable leading
                                // axis while the pill widens. Animating this
                                // inset made the live draft slide ~40dp during
                                // reflow, which read as a zoom/jitter on device.
                                start = { CompactEditorStartInset },
                                top = {
                                    interpolateDp(
                                        CompactEditorTopInset,
                                        ExpandedEditorTopInset,
                                        expansionProgress.value,
                                    )
                                },
                                end = {
                                    interpolateDp(
                                        compactTrailingReserve,
                                        ExpandedEditorEndInset,
                                        expansionProgress.value,
                                    )
                                },
                                bottom = {
                                    interpolateDp(
                                        CompactEditorBottomInset,
                                        ExpandedEditorBottomInset,
                                        expansionProgress.value,
                                    )
                                },
                            ).alpha(if (inputContentVisible) 1f else 0f)
                            .then(if (inputContentVisible) Modifier else Modifier.clearAndSetSemantics {}),
                ) {
                    val editorOverflowColor = composerResizeHandleColor()
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = onValueChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(expandedHeightModifier)
                                // Drawn outside the scroll modifier so the thumb
                                // paints in viewport coordinates over the clipped
                                // editor, only while the draft overflows it.
                                .drawWithContent {
                                    drawContent()
                                    drawComposerEditorOverflowAffordance(
                                        scrollValue = composerScrollState.value,
                                        maxScroll = composerScrollState.maxValue,
                                        color = editorOverflowColor,
                                    )
                                }.keepComposerSelectionVisibleDuringLayout(composerScrollState, layoutCorrectionGate) {
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
                            .deferredPadding(
                                start = { 4.dp },
                                bottom = {
                                    interpolateDp(
                                        0.dp,
                                        4.dp,
                                        expansionProgress.value,
                                    )
                                },
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
                            .height(if (onDictation != null || dictationControls != null) 48.dp else 44.dp),
                ) {
                    if (dictationControls != null) {
                        dictationControls()
                    } else if (onDictation != null) {
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
                    if (expandedTrailingActionInset > 0.dp) {
                        Spacer(
                            Modifier.deferredPadding(
                                end = {
                                    interpolateDp(
                                        0.dp,
                                        expandedTrailingActionInset,
                                        expansionProgress.value,
                                    )
                                },
                            ),
                        )
                    }
                    trailingAction?.invoke(this)
                }
            }
        }

        if (expandedLayout && resizeTargetReady && inputContentVisible) {
            // Keep a transparent 96x48dp gesture target for accessibility, but
            // draw feedback only on the visible handle. The surface starts at
            // 24dp so the accessible target straddles the border evenly. The
            // first editable line starts exactly at the target's lower edge;
            // no decorative header space sits between the two.
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

/** Renders the accessible resize gesture target and its border-mounted visual handle. */
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

/** Resolves an opaque handle color against the current composer surface. */
@Composable
private fun composerResizeHandleColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = 0.45f)
        .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
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
