@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.speakableProjectionFromDocument
import dev.ipf.whitenoise.android.state.BLUE_FREE_LIGHT_TEXT_ARGB
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.OPAQUE_WHITE_ARGB
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val TtsReadAloudHighlightRangeKey = SemanticsPropertyKey<IntRange>("TtsReadAloudHighlightRange")

internal val TtsReadAloudSentenceHighlightRangeKey =
    SemanticsPropertyKey<IntRange>("TtsReadAloudSentenceHighlightRange")

private var SemanticsPropertyReceiver.ttsReadAloudHighlightRange by TtsReadAloudHighlightRangeKey

private var SemanticsPropertyReceiver.ttsReadAloudSentenceHighlightRange by
    TtsReadAloudSentenceHighlightRangeKey

internal data class TtsReadAloudProgress(
    val sentenceIndex: Int,
    val sentenceCount: Int,
    val messageIndex: Int,
    val messageCount: Int,
)

internal fun messageSpeakableProjection(
    bodyText: String,
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)?,
    isGroupMember: ((String) -> Boolean)?,
): SpeakableTextProjection? =
    speakableProjectionFromDocument(
        source = bodyText,
        document = document,
        mentionDisplayName = mentionDisplayName,
        isGroupMember = isGroupMember,
    )

internal fun effectiveTtsHighlightPassage(
    ttsHighlightPassage: TtsPassage?,
    messageIdHex: String,
    projectionId: String?,
    textSelectionMode: Boolean,
): TtsPassage? =
    ttsHighlightPassage?.takeIf {
        !textSelectionMode &&
            !projectionId.isNullOrEmpty() &&
            it.messageIdHex == messageIdHex &&
            it.projectionId == projectionId
    }

internal fun effectiveTtsReadAloudProgress(
    progress: TtsReadAloudProgress?,
    effectivePassage: TtsPassage?,
): TtsReadAloudProgress? = progress.takeIf { effectivePassage != null }

@Composable
internal fun rememberTtsHighlightProjectionResolver(
    projection: SpeakableTextProjection?,
    locale: Locale,
): TtsHighlightProjectionResolver? =
    remember(projection, locale) {
        projection?.let { TtsHighlightProjectionResolver(it, locale) }
    }

@Composable
internal fun rememberTtsLeafHighlightResolver(
    passage: TtsPassage?,
    messageIdHex: String,
    projection: SpeakableTextProjection?,
    locale: Locale,
): TtsLeafHighlightResolver? {
    val projectionResolver = rememberTtsHighlightProjectionResolver(projection, locale)
    return remember(passage, messageIdHex, projectionResolver) {
        if (passage == null || passage.messageIdHex != messageIdHex) {
            null
        } else {
            projectionResolver?.resolverFor(passage, messageIdHex)
        }
    }
}

internal fun ttsSentenceBoundsInWindow(
    layoutResult: TextLayoutResult,
    coordinates: LayoutCoordinates,
    renderedRanges: List<IntRange>,
): Rect? {
    if (!coordinates.isAttached) return null
    val textLength = layoutResult.layoutInput.text.length
    return renderedRanges
        .mapNotNull { range ->
            val start = range.first.coerceIn(0, textLength)
            val end = (range.last + 1).coerceIn(start, textLength)
            if (start >= end) return@mapNotNull null
            val localBounds = layoutResult.getPathForRange(start, end).getBounds()
            val topLeft = coordinates.localToWindow(Offset(localBounds.left, localBounds.top))
            val bottomRight = coordinates.localToWindow(Offset(localBounds.right, localBounds.bottom))
            Rect(
                left = min(topLeft.x, bottomRight.x),
                top = min(topLeft.y, bottomRight.y),
                right = max(topLeft.x, bottomRight.x),
                bottom = max(topLeft.y, bottomRight.y),
            )
        }.reduceOrNull { first, second ->
            Rect(
                left = min(first.left, second.left),
                top = min(first.top, second.top),
                right = max(first.right, second.right),
                bottom = max(first.bottom, second.bottom),
            )
        }
}

internal fun buildTtsLeafHighlightResolver(
    passage: TtsPassage?,
    messageIdHex: String,
    projection: SpeakableTextProjection?,
    locale: Locale,
): TtsLeafHighlightResolver? {
    if (passage == null || projection == null || passage.messageIdHex != messageIdHex) return null
    return createTtsLeafHighlightResolver(
        passage = passage,
        messageIdHex = messageIdHex,
        projection = projection,
        locale = locale,
    )
}

internal fun activeTtsLeafHighlightResolver(
    resolver: TtsLeafHighlightResolver?,
    textSelectionMode: Boolean,
    suppressForCollapsed: Boolean,
): TtsLeafHighlightResolver? =
    if (textSelectionMode || suppressForCollapsed) {
        null
    } else {
        resolver
    }

internal data class TtsReadAloudHighlightStyle(
    val sentenceFill: Color,
    val sentenceMarker: Color,
    val wordMarker: Color,
)

internal data class TtsReadAloudHighlightStyleArgb(
    val sentenceFillArgb: Long,
    val sentenceMarkerArgb: Long,
    val wordMarkerArgb: Long,
)

/**
 * Resolves read-aloud paint from the final bubble roles rather than assuming a
 * theme accent remains readable after alpha compositing. The fill moves away
 * from the text luminance, while the two opaque geometry cues independently
 * satisfy non-text contrast. Content-color fallbacks keep custom and dynamic
 * palettes safe without hard-coding a finite palette matrix.
 */
internal fun resolveTtsReadAloudHighlightStyleArgb(
    backgroundArgb: Long,
    contentArgb: Long,
    sentenceAccentArgb: Long,
    wordAccentArgb: Long,
    amoled: Boolean,
): TtsReadAloudHighlightStyleArgb {
    val background = opaqueTtsArgb(backgroundArgb)
    val content = opaqueTtsArgb(contentArgb)
    val sentenceAccent = opaqueTtsArgb(sentenceAccentArgb)
    val wordAccent = opaqueTtsArgb(wordAccentArgb)
    val fill =
        if (amoled) {
            background
        } else {
            resolveTtsSentenceFillArgb(background, content)
        }
    val sentenceMarker =
        resolveTtsMarkerArgb(
            candidates = listOf(sentenceAccent, content, wordAccent),
            adjacentColors = listOf(background, fill),
            blueFree = amoled,
        )
    val wordMarker =
        resolveTtsMarkerArgb(
            candidates = listOf(wordAccent, content, sentenceMarker),
            adjacentColors = listOf(fill),
            blueFree = amoled,
        )
    return TtsReadAloudHighlightStyleArgb(
        sentenceFillArgb = fill,
        sentenceMarkerArgb = sentenceMarker,
        wordMarkerArgb = wordMarker,
    )
}

private fun resolveTtsSentenceFillArgb(
    backgroundArgb: Long,
    contentArgb: Long,
): Long {
    val contrastExtreme =
        if (contrastRatio(contentArgb, OPAQUE_WHITE_ARGB) >= contrastRatio(contentArgb, OPAQUE_BLACK_ARGB)) {
            OPAQUE_WHITE_ARGB
        } else {
            OPAQUE_BLACK_ARGB
        }
    return TTS_SENTENCE_FILL_STRENGTHS
        .asSequence()
        .map { strength -> blendOpaqueArgb(backgroundArgb, contrastExtreme, strength) }
        .firstOrNull { fill -> ttsTextRemainsReadable(contentArgb, fill) }
        ?: contrastExtreme
}

private fun ttsTextRemainsReadable(
    contentArgb: Long,
    fillArgb: Long,
): Boolean =
    contrastRatio(contentArgb, fillArgb) >= WCAG_AA_NORMAL_TEXT_CONTRAST &&
        contrastRatio(
            contentArgb,
            compositeOpaqueArgb(
                foregroundArgb = contentArgb,
                backgroundArgb = fillArgb,
                foregroundAlpha = TTS_MAX_INLINE_DECORATION_ALPHA,
            ),
        ) >= WCAG_AA_NORMAL_TEXT_CONTRAST

private fun resolveTtsMarkerArgb(
    candidates: List<Long>,
    adjacentColors: List<Long>,
    blueFree: Boolean,
): Long {
    val eligibleCandidates =
        if (blueFree) {
            candidates.filter { candidate -> candidate and TTS_COLOR_CHANNEL_MASK == 0L }
        } else {
            candidates
        }
    val fallbacks =
        if (blueFree) {
            listOf(OPAQUE_BLACK_ARGB, BLUE_FREE_LIGHT_TEXT_ARGB)
        } else {
            listOf(OPAQUE_BLACK_ARGB, OPAQUE_WHITE_ARGB)
        }
    return (eligibleCandidates + fallbacks)
        .distinct()
        .firstOrNull { candidate ->
            adjacentColors.all { adjacent ->
                contrastRatio(candidate, adjacent) >= TTS_MIN_NON_TEXT_CONTRAST
            }
        } ?: fallbacks.maxBy { candidate ->
        adjacentColors.minOf { adjacent -> contrastRatio(candidate, adjacent) }
    }
}

internal fun blendOpaqueArgb(
    backgroundArgb: Long,
    foregroundArgb: Long,
    foregroundAlpha: Float,
): Long {
    val background = opaqueTtsArgb(backgroundArgb)
    val foreground = opaqueTtsArgb(foregroundArgb)
    val alpha = foregroundAlpha.coerceIn(0f, 1f)

    fun channel(shift: Int): Long {
        val backgroundChannel = (background shr shift) and TTS_COLOR_CHANNEL_MASK
        val foregroundChannel = (foreground shr shift) and TTS_COLOR_CHANNEL_MASK
        return (backgroundChannel + (foregroundChannel - backgroundChannel) * alpha)
            .roundToInt()
            .coerceIn(0, TTS_COLOR_CHANNEL_MAX)
            .toLong()
    }
    return OPAQUE_BLACK_ARGB or
        (channel(TTS_RED_CHANNEL_SHIFT) shl TTS_RED_CHANNEL_SHIFT) or
        (channel(TTS_GREEN_CHANNEL_SHIFT) shl TTS_GREEN_CHANNEL_SHIFT) or
        channel(TTS_BLUE_CHANNEL_SHIFT)
}

internal fun compositeOpaqueArgb(
    foregroundArgb: Long,
    backgroundArgb: Long,
    foregroundAlpha: Float,
): Long = blendOpaqueArgb(backgroundArgb, foregroundArgb, foregroundAlpha)

private fun opaqueTtsArgb(argb: Long): Long = OPAQUE_BLACK_ARGB or (argb and TTS_RGB_MASK)

@Composable
internal fun rememberTtsReadAloudHighlightStyle(
    backgroundColor: Color,
    contentColor: Color,
): TtsReadAloudHighlightStyle {
    val colorScheme = MaterialTheme.colorScheme
    val sentenceAccent = colorScheme.outlineVariant
    val wordAccent = colorScheme.tertiary
    val amoled = isAmoledSurfaceTheme()
    return remember(backgroundColor, contentColor, sentenceAccent, wordAccent, amoled) {
        resolveTtsReadAloudHighlightStyleArgb(
            backgroundArgb = backgroundColor.toOpaqueArgb(),
            contentArgb = contentColor.toOpaqueArgb(),
            sentenceAccentArgb = sentenceAccent.toOpaqueArgb(),
            wordAccentArgb = wordAccent.toOpaqueArgb(),
            amoled = amoled,
        ).let { resolved ->
            TtsReadAloudHighlightStyle(
                sentenceFill = Color(resolved.sentenceFillArgb),
                sentenceMarker = Color(resolved.sentenceMarkerArgb),
                wordMarker = Color(resolved.wordMarkerArgb),
            )
        }
    }
}

private fun Color.toOpaqueArgb(): Long = toArgb().toLong() and TTS_ARGB_MASK

@Composable
internal fun readAloudMessageSemantics(
    progress: TtsReadAloudProgress?,
    modifier: Modifier = Modifier,
    messageContent: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        messageContent()
        progress?.let { readAloudProgress ->
            val progressLabel =
                stringResource(
                    R.string.tts_bar_progress,
                    readAloudProgress.sentenceIndex + 1,
                    readAloudProgress.sentenceCount,
                    readAloudProgress.messageIndex + 1,
                    readAloudProgress.messageCount,
                )
            Text(
                text = progressLabel,
                modifier =
                    Modifier
                        .size(0.dp)
                        .testTag("tts-read-aloud-progress")
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = progressLabel
                        },
            )
        }
    }
}

internal fun ttsHighlightTextRange(
    highlightRange: IntRange,
    textLength: Int,
): TextRange {
    val start = highlightRange.first.coerceIn(0, textLength)
    val end = (highlightRange.last + 1).coerceIn(start, textLength)
    return TextRange(start, end)
}

@Suppress("ReturnCount")
internal fun Modifier.ttsReadAloudHighlight(
    layoutResult: TextLayoutResult?,
    highlight: TtsLeafHighlight?,
    style: TtsReadAloudHighlightStyle?,
): Modifier {
    val semanticsModifier =
        if (highlight == null) {
            Modifier
        } else {
            Modifier.semantics {
                highlight.primaryRange?.let { ttsReadAloudHighlightRange = it }
                highlight.sentence?.let { ttsReadAloudSentenceHighlightRange = it }
            }
        }
    if (layoutResult == null || highlight == null || style == null) return this.then(semanticsModifier)
    val textLength = layoutResult.layoutInput.text.length
    val sentenceRanges =
        highlight.sentenceRanges
            .map { range -> ttsHighlightTextRange(range, textLength) }
            .filterNot(TextRange::collapsed)
    val wordRange = highlight.word?.let { ttsHighlightTextRange(it, textLength) }
    if (sentenceRanges.isEmpty() && wordRange?.collapsed != false) return this.then(semanticsModifier)
    return this.then(semanticsModifier).drawWithCache {
        val sentenceMarkerWidth = TTS_SENTENCE_MARKER_WIDTH.toPx()
        val wordMarkerWidth = TTS_WORD_MARKER_WIDTH.toPx()
        val sentenceBoxes = sentenceRanges.flatMap { range -> highlightBoundingBoxes(layoutResult, range) }
        val wordBoxes =
            wordRange
                ?.takeUnless { it.collapsed }
                ?.let { range -> highlightBoundingBoxes(layoutResult, range) }
                .orEmpty()
        onDrawWithContent {
            sentenceBoxes.forEach { box ->
                drawRect(color = style.sentenceFill, topLeft = Offset(box.left, box.top), size = box.size)
            }
            drawContent()
            drawTtsSentenceMarkers(sentenceBoxes, style.sentenceMarker, sentenceMarkerWidth)
            drawTtsWordMarkers(wordBoxes, style.wordMarker, wordMarkerWidth)
        }
    }
}

private fun DrawScope.drawTtsSentenceMarkers(
    boxes: List<Rect>,
    color: Color,
    markerWidth: Float,
) {
    boxes.forEach { box ->
        val inset = min(markerWidth / 2f, box.width / 2f)
        val top = min(box.top + markerWidth / 2f, box.bottom)
        val bottom = max(box.bottom - markerWidth / 2f, top)
        drawLine(
            color = color,
            start = Offset(box.left + inset, top),
            end = Offset(box.left + inset, bottom),
            strokeWidth = markerWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(box.right - inset, top),
            end = Offset(box.right - inset, bottom),
            strokeWidth = markerWidth,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawTtsWordMarkers(
    boxes: List<Rect>,
    color: Color,
    markerWidth: Float,
) {
    boxes.forEach { box ->
        val underlineY = (box.bottom - markerWidth / 2f).coerceAtLeast(box.top)
        val left = min(box.left + markerWidth / 2f, box.right)
        val right = max(box.right - markerWidth / 2f, left)
        drawLine(
            color = color,
            start = Offset(left, underlineY),
            end = Offset(right, underlineY),
            strokeWidth = markerWidth,
            cap = StrokeCap.Round,
        )
    }
}

private const val TTS_MIN_NON_TEXT_CONTRAST = 3.0
private const val TTS_MAX_INLINE_DECORATION_ALPHA = 0.12f
private const val TTS_BOX_MERGE_TOLERANCE_PX = 1f
private const val TTS_COLOR_CHANNEL_MASK = 0xFFL
private const val TTS_COLOR_CHANNEL_MAX = 255
private const val TTS_RED_CHANNEL_SHIFT = 16
private const val TTS_GREEN_CHANNEL_SHIFT = 8
private const val TTS_BLUE_CHANNEL_SHIFT = 0
private const val TTS_RGB_MASK = 0x00FFFFFFL
private const val TTS_ARGB_MASK = 0xFFFFFFFFL
private val TTS_SENTENCE_FILL_STRENGTHS = floatArrayOf(0.18f, 0.22f, 0.26f, 0.30f, 0.36f, 0.44f)
private val TTS_SENTENCE_MARKER_WIDTH = 1.5.dp
private val TTS_WORD_MARKER_WIDTH = 2.dp

private fun highlightBoundingBoxes(
    layoutResult: TextLayoutResult,
    range: TextRange,
): List<Rect> {
    val start = range.start.coerceIn(0, layoutResult.layoutInput.text.length)
    val end = range.end.coerceIn(start, layoutResult.layoutInput.text.length)
    if (start >= end) return emptyList()
    val startLine = layoutResult.getLineForOffset(start)
    val endLine = layoutResult.getLineForOffset(max(end - 1, start))
    val boxes = ArrayList<Rect>()
    for (line in startLine..endLine) {
        val lineStart = if (line == startLine) start else layoutResult.getLineStart(line)
        val lineEnd = if (line == endLine) end else layoutResult.getLineEnd(line)
        if (lineStart >= lineEnd) continue
        val top = layoutResult.getLineTop(line)
        val bottom = layoutResult.getLineBottom(line)
        var cursor = lineStart
        while (cursor < lineEnd) {
            val box = layoutResult.getBoundingBox(cursor)
            val next = layoutResult.getOffsetForPosition(Offset(box.right + 1f, box.top))
            val segmentEnd = if (next <= cursor) lineEnd else min(next, lineEnd)
            val left = layoutResult.getHorizontalPosition(cursor, usePrimaryDirection = true)
            val right = layoutResult.getHorizontalPosition(segmentEnd, usePrimaryDirection = false)
            boxes +=
                Rect(
                    left = min(left, right),
                    top = top,
                    right = max(left, right),
                    bottom = bottom,
                )
            cursor = segmentEnd
        }
    }
    return mergeAdjacentHighlightBoxes(boxes)
}

private fun mergeAdjacentHighlightBoxes(boxes: List<Rect>): List<Rect> {
    if (boxes.size < 2) return boxes
    val sorted = boxes.sortedWith(compareBy(Rect::top, Rect::left))
    val merged = ArrayList<Rect>(sorted.size)
    sorted.forEach { box ->
        val previous = merged.lastOrNull()
        val sameLine =
            previous != null &&
                abs(previous.top - box.top) <= TTS_BOX_MERGE_TOLERANCE_PX &&
                abs(previous.bottom - box.bottom) <= TTS_BOX_MERGE_TOLERANCE_PX
        val adjacent = previous != null && box.left <= previous.right + TTS_BOX_MERGE_TOLERANCE_PX
        if (sameLine && adjacent) {
            merged[merged.lastIndex] =
                Rect(
                    left = min(previous.left, box.left),
                    top = min(previous.top, box.top),
                    right = max(previous.right, box.right),
                    bottom = max(previous.bottom, box.bottom),
                )
        } else {
            merged += box
        }
    }
    return merged
}
