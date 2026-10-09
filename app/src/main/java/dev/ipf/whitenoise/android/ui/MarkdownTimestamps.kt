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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val TIMESTAMP_TAG = "markdown-timestamp"
private const val TIMESTAMP_CLOCK_PREFIX = "markdown-timestamp-clock:"
internal val markdownTimestampInlineContentTag =
    buildAnnotatedString { appendInlineContent("clock", "◷") }
        .getStringAnnotations(0, 1)
        .single()
        .tag

internal fun MarkdownTimestampStyleFfi.code(): Char =
    when (this) {
        MarkdownTimestampStyleFfi.SHORT_TIME -> 't'
        MarkdownTimestampStyleFfi.LONG_TIME -> 'T'
        MarkdownTimestampStyleFfi.SHORT_DATE -> 'd'
        MarkdownTimestampStyleFfi.LONG_DATE -> 'D'
        MarkdownTimestampStyleFfi.SHORT_DATE_TIME -> 'f'
        MarkdownTimestampStyleFfi.LONG_DATE_TIME -> 'F'
        MarkdownTimestampStyleFfi.COMPACT_DATE_TIME -> 's'
        MarkdownTimestampStyleFfi.COMPACT_DATE_TIME_SECONDS -> 'S'
        MarkdownTimestampStyleFfi.RELATIVE -> 'R'
    }

internal fun AnnotatedString.Builder.appendMarkdownTimestamp(inline: MarkdownInlineFfi.Timestamp) {
    val style = inline.style.code()
    val token = markdownTimestampToken(inline.unixSeconds, style)
    val start = length
    appendInlineContent(TIMESTAMP_CLOCK_PREFIX + token, "◷")
    append(' ')
    append(markdownTimestampLabel(inline.unixSeconds, style))
    addStringAnnotation(TIMESTAMP_TAG, token, start, length)
}

/** Invalidate only display projections. Never rewrite the owned MDK document. */
@Composable
internal fun rememberTimestampRevision(
    enabled: Boolean,
    relative: Boolean,
): Long {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0L) }
    DisposableEffect(context, enabled) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    revision++
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
    LaunchedEffect(relative) {
        if (relative) {
            while (true) {
                delay(1000)
                revision++
            }
        }
    }
    return revision
}

internal fun markdownInlinesHaveTimestamp(
    inlines: List<MarkdownInlineFfi>,
    relativeOnly: Boolean = false,
    depth: Int = 0,
): Boolean =
    !markdownInlineDepthExceeded(depth) &&
        markdownVisibleSiblings(inlines).any { inline ->
            when (inline) {
                is MarkdownInlineFfi.Timestamp -> !relativeOnly || inline.style == MarkdownTimestampStyleFfi.RELATIVE
                is MarkdownInlineFfi.Emph -> markdownInlinesHaveTimestamp(inline.children, relativeOnly, depth + 1)
                is MarkdownInlineFfi.Strong -> markdownInlinesHaveTimestamp(inline.children, relativeOnly, depth + 1)
                is MarkdownInlineFfi.Strikethrough -> markdownInlinesHaveTimestamp(inline.children, relativeOnly, depth + 1)
                is MarkdownInlineFfi.Link -> markdownInlinesHaveTimestamp(inline.children, relativeOnly, depth + 1)
                is MarkdownInlineFfi.Image -> markdownInlinesHaveTimestamp(inline.alt, relativeOnly, depth + 1)
                else -> false
            }
        }

internal fun markdownDocumentHasTimestamp(
    document: MarkdownDocumentFfi,
    relativeOnly: Boolean = false,
): Boolean = markdownBlocksHaveTimestamp(document.blocks, relativeOnly, 0)

private fun markdownBlocksHaveTimestamp(
    blocks: List<MarkdownBlockFfi>,
    relativeOnly: Boolean,
    depth: Int,
): Boolean =
    !markdownDepthExceeded(depth) &&
        markdownVisibleSiblings(blocks).any { block ->
            when (block) {
                is MarkdownBlockFfi.Paragraph -> markdownInlinesHaveTimestamp(block.inlines, relativeOnly)
                is MarkdownBlockFfi.Heading -> markdownInlinesHaveTimestamp(block.inlines, relativeOnly)
                is MarkdownBlockFfi.BlockQuote -> markdownBlocksHaveTimestamp(block.blocks, relativeOnly, depth + 1)
                is MarkdownBlockFfi.ListBlock -> markdownVisibleSiblings(block.items).any { markdownBlocksHaveTimestamp(it.blocks, relativeOnly, depth + 1) }
                is MarkdownBlockFfi.Details ->
                    markdownInlinesHaveTimestamp(block.summary, relativeOnly) ||
                        markdownBlocksHaveTimestamp(block.body, relativeOnly, depth + 1)
                is MarkdownBlockFfi.Table -> {
                    val table = markdownVisibleTable(block.header, block.rows)
                    (listOf(table.header) + table.rows).any { row -> row.cells.any { markdownInlinesHaveTimestamp(it.inlines, relativeOnly) } }
                }
                else -> false
            }
        }

private fun timestampDisclosure(token: String): String {
    // This is our canonical typed-node metadata, not parsing user-authored Markdown.
    val seconds = token.substring(3, token.lastIndexOf(':')).toLong()
    return markdownTimestampAbsolute(seconds, 'F') + "\n" + token
}

/** A single selectable Text: ranges retain styles, links, wrapping and UTF-16 selection offsets. */
@Composable
internal fun MarkdownTimestampText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontStyle: FontStyle? = null,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val ranges = remember(text) { text.getStringAnnotations(TIMESTAMP_TAG, 0, text.length) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var hovered by remember(text) { mutableStateOf<String?>(null) }
    var tapped by remember { mutableStateOf<String?>(null) }
    var hoverPosition by remember(text) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(ranges) {
        if (ranges.none { it.item == tapped }) {
            tapped = null
        }
    }
    val clockContent =
        remember(ranges) {
            ranges.associate { range ->
                TIMESTAMP_CLOCK_PREFIX + range.item to
                    InlineTextContent(
                        Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter),
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = timestampDisclosure(range.item),
                            modifier = Modifier.fillMaxSize().clickable { tapped = range.item },
                        )
                    }
            }
        }

    fun hit(position: Offset): AnnotatedString.Range<String>? {
        val measured = layout ?: return null
        val offset = measured.getOffsetForPosition(position)
        // getOffsetForPosition clamps whitespace to a nearby glyph; do not show a false tooltip.
        if (offset !in 0 until text.length || !measured.getBoundingBox(offset).contains(position)) return null
        return ranges.firstOrNull { offset >= it.start && offset < it.end }
    }
    Box {
        Text(
            text = text,
            inlineContent = EmojiShortcodes.content() + inlineContent + clockContent,
            modifier =
                modifier.then(
                    if (ranges.isEmpty()) {
                        Modifier
                    } else {
                        Modifier.pointerInput(text) {
                            awaitPointerEventScope {
                                var down: Offset? = null
                                var downAt = 0L
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull() ?: continue
                                    when (event.type) {
                                        PointerEventType.Exit -> hovered = null
                                        PointerEventType.Move, PointerEventType.Enter -> {
                                            if (!change.pressed) {
                                                hovered = hit(change.position)?.item
                                                hoverPosition = change.position
                                            }
                                        }
                                        PointerEventType.Press -> {
                                            down = change.position
                                            downAt = change.uptimeMillis
                                        }
                                        PointerEventType.Release -> {
                                            val origin = down
                                            val range = hit(change.position)
                                            if (origin != null &&
                                                range != null &&
                                                !change.isConsumed &&
                                                (change.position - origin).getDistance() < viewConfiguration.touchSlop &&
                                                change.uptimeMillis - downAt < viewConfiguration.longPressTimeoutMillis
                                            ) {
                                                val offset = layout?.getOffsetForPosition(change.position) ?: -1
                                                // Linked labels navigate normally; their clock remains a separate disclosure target.
                                                if (text.getLinkAnnotations(offset, offset + 1).isEmpty()) {
                                                    tapped = range.item
                                                    change.consume()
                                                }
                                            }
                                            down = null
                                        }
                                    }
                                }
                            }
                        }
                    },
                ),
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            fontStyle = fontStyle,
            onTextLayout = {
                layout = it
                onTextLayout?.invoke(it)
            },
        )
        hovered?.let { token ->
            Popup(alignment = Alignment.TopStart, offset = IntOffset(hoverPosition.x.roundToInt(), hoverPosition.y.roundToInt() + 24)) {
                Surface(shape = MaterialTheme.shapes.small, tonalElevation = 4.dp) {
                    Text(timestampDisclosure(token), modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    tapped?.let { token ->
        AlertDialog(
            onDismissRequest = { tapped = null },
            text = { Text(timestampDisclosure(token)) },
            confirmButton = {
                TextButton(
                    onClick = { tapped = null },
                ) {
                    Text(
                        androidx.compose.ui.res
                            .stringResource(dev.ipf.whitenoise.android.R.string.dismiss),
                    )
                }
            },
        )
    }
}
