package dev.ipf.whitenoise.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.outlined.Schedule
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val TIMESTAMP_TAG = "markdown-timestamp"
private const val TIMESTAMP_CLOCK_PREFIX = "markdown-timestamp-clock:"
private const val TIMESTAMP_HOVER_OFFSET_PX = 24
internal val markdownTimestampInlineContentTag =
    buildAnnotatedString { appendInlineContent("clock", "◷") }
        .getStringAnnotations(0, 1)
        .single()
        .tag

/** Appends a clock placeholder and the local label, annotated with the canonical token over both. */
internal fun AnnotatedString.Builder.appendMarkdownTimestamp(inline: MarkdownInlineFfi.Timestamp) {
    val style = inline.style.code()
    val token = markdownTimestampToken(inline.unixSeconds, style)
    val start = length
    appendInlineContent(TIMESTAMP_CLOCK_PREFIX + token, "◷")
    append(' ')
    append(markdownTimestampLabel(inline.unixSeconds, style))
    addStringAnnotation(TIMESTAMP_TAG, token, start, length)
}

/**
 * Counts display invalidations for [timestamps]: clock, timezone and locale broadcasts, and
 * relative-label boundaries while the screen is started. [nowMillis] reads the wall clock.
 * Never rewrite the owned MDK document.
 */
@Composable
internal fun rememberTimestampRevision(
    timestamps: List<MarkdownInlineFfi.Timestamp>,
    nowMillis: () -> Long = System::currentTimeMillis,
): Long {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = timestamps.isNotEmpty()
    val relativeSeconds =
        remember(timestamps) {
            timestamps.filter { it.style == MarkdownTimestampStyleFfi.RELATIVE }.map { it.unixSeconds }
        }
    var revision by remember { mutableLongStateOf(0L) }
    var clockChanges by remember { mutableIntStateOf(0) }
    DisposableEffect(context, enabled) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    revision++
                    clockChanges++
                }
            }
        if (enabled) {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        onDispose { if (enabled) context.unregisterReceiver(receiver) }
    }
    // A clock change restarts the wait: the boundary it was sleeping towards may now be far off.
    LaunchedEffect(lifecycle, relativeSeconds, clockChanges) {
        if (relativeSeconds.isEmpty()) return@LaunchedEffect
        var restarted = false
        // A stopped screen does not tick, so a restart refreshes labels that aged meanwhile.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (restarted) revision++
            restarted = true
            while (true) {
                delay(timestampRelativeRefreshMillis(relativeSeconds, nowMillis()))
                revision++
            }
        }
    }
    return revision
}

/** Visible timestamps in [inlines], in reading order, for display invalidation. */
internal fun markdownInlineTimestamps(inlines: List<MarkdownInlineFfi>): List<MarkdownInlineFfi.Timestamp> =
    buildList {
        collectInlineTimestamps(inlines, 0)
    }

/** Visible timestamps across every block of [document], in reading order. */
internal fun markdownDocumentTimestamps(document: MarkdownDocumentFfi): List<MarkdownInlineFfi.Timestamp> =
    buildList { collectBlockTimestamps(document.blocks, 0) }

/** Adds visible timestamps from [inlines] and their nested emphasis, link and image children. */
private fun MutableList<MarkdownInlineFfi.Timestamp>.collectInlineTimestamps(
    inlines: List<MarkdownInlineFfi>,
    depth: Int,
) {
    if (markdownInlineDepthExceeded(depth)) return
    for (inline in markdownVisibleSiblings(inlines)) {
        when (inline) {
            is MarkdownInlineFfi.Timestamp -> add(inline)
            is MarkdownInlineFfi.Emph -> collectInlineTimestamps(inline.children, depth + 1)
            is MarkdownInlineFfi.Strong -> collectInlineTimestamps(inline.children, depth + 1)
            is MarkdownInlineFfi.Strikethrough -> collectInlineTimestamps(inline.children, depth + 1)
            is MarkdownInlineFfi.Link -> collectInlineTimestamps(inline.children, depth + 1)
            is MarkdownInlineFfi.Image -> collectInlineTimestamps(inline.alt, depth + 1)
            else -> Unit
        }
    }
}

/** Adds visible timestamps from [blocks], including quotes, lists, details and table cells. */
private fun MutableList<MarkdownInlineFfi.Timestamp>.collectBlockTimestamps(
    blocks: List<MarkdownBlockFfi>,
    depth: Int,
) {
    if (markdownDepthExceeded(depth)) return
    for (block in markdownVisibleSiblings(blocks)) {
        when (block) {
            is MarkdownBlockFfi.Paragraph -> collectInlineTimestamps(block.inlines, 0)
            is MarkdownBlockFfi.Heading -> collectInlineTimestamps(block.inlines, 0)
            is MarkdownBlockFfi.BlockQuote -> collectBlockTimestamps(block.blocks, depth + 1)
            is MarkdownBlockFfi.ListBlock ->
                markdownVisibleSiblings(block.items).forEach { collectBlockTimestamps(it.blocks, depth + 1) }
            is MarkdownBlockFfi.Details -> {
                collectInlineTimestamps(block.summary, 0)
                collectBlockTimestamps(block.body, depth + 1)
            }
            is MarkdownBlockFfi.Table -> {
                val table = markdownVisibleTable(block.header, block.rows)
                (listOf(table.header) + table.rows).forEach { row ->
                    row.cells.forEach { collectInlineTimestamps(it.inlines, 0) }
                }
            }
            else -> Unit
        }
    }
}

/** The full local date/time for [token], followed by the token itself. */
private fun timestampDisclosure(token: String): String {
    // This is our canonical typed-node metadata, not parsing user-authored Markdown.
    val seconds = token.substring(3, token.lastIndexOf(':')).toLong()
    return markdownTimestampAbsolute(seconds, 'F') + "\n" + token
}

/** A single selectable Text: ranges retain styles, links, wrapping and UTF-16 selection offsets. */
@Suppress("FunctionNaming")
@Composable
internal fun MarkdownTimestampText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontStyle: FontStyle? = null,
    inlineContent: Map<String, InlineTextContent> = EmojiShortcodes.content(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val ranges = remember(text) { text.getStringAnnotations(TIMESTAMP_TAG, 0, text.length) }
    val currentRanges by rememberUpdatedState(ranges)
    val state = remember { TimestampTextState() }
    LaunchedEffect(ranges) {
        // A refreshed relative label keeps its token: hover and disclosure end only when the token leaves.
        val tokens = ranges.mapTo(HashSet()) { it.item }
        if (state.hovered !in tokens) state.hovered = null
        if (state.tapped !in tokens) state.tapped = null
    }
    if (ranges.isEmpty()) {
        Text(
            text = text,
            modifier = modifier,
            inlineContent = inlineContent,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            fontStyle = fontStyle,
            onTextLayout = { onTextLayout?.invoke(it) },
        )
    } else {
        val clocks = rememberTimestampClocks(ranges, state)
        Box(modifier, propagateMinConstraints = true) {
            Text(
                text = text,
                modifier = Modifier.timestampPointers(state) { currentRanges },
                inlineContent = inlineContent + clocks,
                style = style,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = overflow,
                fontStyle = fontStyle,
                onTextLayout = {
                    state.layout = it
                    onTextLayout?.invoke(it)
                },
            )
            TimestampOverlays(state)
        }
    }
}

/** Layout, hover and tap state shared by a timestamp text's pointer handler and overlays. */
private class TimestampTextState {
    var layout: TextLayoutResult? = null
    var hovered by mutableStateOf<String?>(null)
    var tapped by mutableStateOf<String?>(null)
    var hoverPosition by mutableStateOf(Offset.Zero)

    /**
     * The character whose glyph box contains [position], or null past the line ends. The nearest
     * caret sits after the glyph on its trailing half, so both characters around it are checked.
     */
    fun glyphAt(position: Offset): Int? {
        val measured = layout ?: return null
        val caret = measured.getOffsetForPosition(position)
        return listOf(caret, caret - 1).firstOrNull { offset ->
            offset in 0 until measured.layoutInput.text.length && measured.getBoundingBox(offset).contains(position)
        }
    }

    /** The timestamp range whose glyphs lie under [position]. */
    fun hit(
        position: Offset,
        ranges: List<AnnotatedString.Range<String>>,
    ): AnnotatedString.Range<String>? {
        val offset = glyphAt(position) ?: return null
        return ranges.firstOrNull { offset >= it.start && offset < it.end }
    }

    /** Opens disclosure for a short, still tap on an unlinked timestamp glyph. */
    fun timestampTap(
        change: PointerInputChange,
        origin: Offset?,
        downAt: Long,
        ranges: List<AnnotatedString.Range<String>>,
        viewConfiguration: ViewConfiguration,
    ) {
        if (origin == null || change.isConsumed) return
        val offset = glyphAt(change.position)
        val range = offset?.let { glyph -> ranges.firstOrNull { glyph >= it.start && glyph < it.end } }
        val shortTap =
            (change.position - origin).getDistance() < viewConfiguration.touchSlop &&
                change.uptimeMillis - downAt < viewConfiguration.longPressTimeoutMillis
        // Linked labels navigate normally; the clock has its own disclosure target.
        val links = offset?.let { layout?.layoutInput?.text?.getLinkAnnotations(it, it + 1) }
        if (range != null && links?.isEmpty() == true && shortTap) {
            tapped = range.item
            change.consume()
        }
    }
}

/** One clickable native clock icon per timestamp token, keyed by its inline-content id. */
@Composable
private fun rememberTimestampClocks(
    ranges: List<AnnotatedString.Range<String>>,
    state: TimestampTextState,
): Map<String, InlineTextContent> =
    remember(ranges) {
        ranges.associate { range ->
            TIMESTAMP_CLOCK_PREFIX + range.item to
                InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = timestampDisclosure(range.item),
                        modifier = Modifier.fillMaxSize().clickable { state.tapped = range.item },
                    )
                }
        }
    }

/**
 * Hover and tap disclosure for timestamp labels. Keyed to [state] only, so a relative-label
 * refresh between press and release keeps the press; [ranges] reads the current annotations.
 */
private fun Modifier.timestampPointers(
    state: TimestampTextState,
    ranges: () -> List<AnnotatedString.Range<String>>,
): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            var down: Offset? = null
            var downAt = 0L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Exit -> state.hovered = null
                    PointerEventType.Move, PointerEventType.Enter -> {
                        if (!change.pressed) {
                            state.hovered = state.hit(change.position, ranges())?.item
                            state.hoverPosition = change.position
                        }
                    }
                    PointerEventType.Press -> {
                        down = change.position
                        downAt = change.uptimeMillis
                    }
                    PointerEventType.Release -> {
                        state.timestampTap(change, down, downAt, ranges(), viewConfiguration)
                        down = null
                    }
                }
            }
        }
    }

/** Shows the hover tooltip and the tap disclosure dialog for the current [state]. */
@Suppress("FunctionNaming")
@Composable
private fun TimestampOverlays(state: TimestampTextState) {
    state.hovered?.let { token ->
        Popup(
            alignment = Alignment.TopStart,
            offset =
                IntOffset(
                    state.hoverPosition.x.roundToInt(),
                    state.hoverPosition.y.roundToInt() + TIMESTAMP_HOVER_OFFSET_PX,
                ),
        ) {
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 4.dp) {
                Text(
                    timestampDisclosure(token),
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    state.tapped?.let { token ->
        AlertDialog(
            onDismissRequest = { state.tapped = null },
            text = { Text(timestampDisclosure(token)) },
            confirmButton = {
                TextButton(onClick = { state.tapped = null }) {
                    Text(stringResource(R.string.dismiss))
                }
            },
        )
    }
}
