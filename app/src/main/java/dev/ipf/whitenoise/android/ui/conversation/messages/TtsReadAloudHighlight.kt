@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.speakableProjectionFromDocument
import dev.ipf.whitenoise.android.state.BLUE_FREE_LIGHT_TEXT_ARGB
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.OPAQUE_WHITE_ARGB
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.WCAG_NON_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import java.util.Locale
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
    val sentenceFill: Long,
    val sentenceMarker: Long,
    val wordMarker: Long,
)

/**
 * Resolves paint from the bubble's final background/content pair. Text remains
 * AA-readable on the sentence fill; sentence rails and the word underline are
 * independently non-text-readable. A true-black AMOLED bubble keeps its black
 * fill and uses only blue-free markers; colored bubbles retain normal paint.
 */
internal fun resolveTtsReadAloudHighlightStyle(
    background: Long,
    content: Long,
    sentenceAccent: Long,
    wordAccent: Long,
    amoled: Boolean,
): TtsReadAloudHighlightStyleArgb {
    val opaqueBackground = opaqueArgb(background)
    val opaqueContent = opaqueArgb(content)
    val trueBlackAmoled = amoled && opaqueBackground == OPAQUE_BLACK_ARGB
    val fill = visibleReadableSentenceFill(opaqueBackground, opaqueContent)
    val sentenceMarker =
        readableMarker(
            candidates = listOf(sentenceAccent, opaqueContent, wordAccent),
            adjacent = listOf(opaqueBackground, fill),
            blueFree = trueBlackAmoled,
        )
    val wordMarker =
        readableMarker(
            candidates = listOf(wordAccent, opaqueContent, sentenceMarker),
            adjacent = listOf(opaqueBackground, fill),
            blueFree = trueBlackAmoled,
        )
    return TtsReadAloudHighlightStyleArgb(fill, sentenceMarker, wordMarker)
}

/**
 * A sentence band has to satisfy two things at once: text stays readable on
 * it, and it is distinguishable from the bubble under it. Neither direction
 * alone manages both.
 *
 * Moving away from the content protects text contrast, which is what a bubble
 * with barely-readable text needs, but it degenerates when the background
 * already is the extreme it moves toward - a true black bubble returns black
 * at every strength and the band disappears. Moving toward the content always
 * separates from the background but eats the very contrast a low-contrast
 * bubble has none of.
 *
 * So try the safe direction first, weakest step upward, and fall through to
 * the content direction only when the safe one cannot separate at all. Black
 * with near-white text lands there and stays far above AA, because it starts
 * near 21:1.
 */
private fun visibleReadableSentenceFill(
    background: Long,
    content: Long,
): Long {
    val awayFromContent =
        if (contrastRatio(content, OPAQUE_WHITE_ARGB) >= contrastRatio(content, OPAQUE_BLACK_ARGB)) {
            OPAQUE_WHITE_ARGB
        } else {
            OPAQUE_BLACK_ARGB
        }
    val candidates =
        TTS_SENTENCE_FILL_STRENGTHS.map { strength -> blendOpaque(background, awayFromContent, strength) } +
            TTS_SENTENCE_FILL_STRENGTHS.map { strength -> blendOpaque(background, content, strength) }
    return candidates.firstOrNull { fill ->
        contrastRatio(fill, background) >= TTS_SENTENCE_FILL_MIN_SEPARATION &&
            contrastRatio(content, fill) >= WCAG_AA_NORMAL_TEXT_CONTRAST &&
            contrastRatio(
                content,
                blendOpaque(fill, content, TTS_INLINE_DECORATION_ALPHA),
            ) >= WCAG_AA_NORMAL_TEXT_CONTRAST
    } ?: background
}

private fun readableMarker(
    candidates: List<Long>,
    adjacent: List<Long>,
    blueFree: Boolean,
): Long {
    val fallbackCandidates =
        if (blueFree) {
            listOf(BLUE_FREE_LIGHT_TEXT_ARGB, OPAQUE_BLACK_ARGB)
        } else {
            listOf(OPAQUE_BLACK_ARGB, OPAQUE_WHITE_ARGB)
        }
    val safeCandidates =
        (candidates.map(::opaqueArgb) + fallbackCandidates)
            .distinct()
            .filter { !blueFree || it and 0xFFL == 0L }
    return checkNotNull(
        safeCandidates.firstOrNull { candidate ->
            adjacent.all { contrastRatio(candidate, it) >= WCAG_NON_TEXT_CONTRAST }
        },
    ) { "Bubble content must provide a fail-closed 3:1 marker candidate" }
}

private fun blendOpaque(
    background: Long,
    foreground: Long,
    alpha: Float,
): Long {
    fun channel(shift: Int): Long {
        val from = (background shr shift) and ARGB_CHANNEL_MASK
        val to = (foreground shr shift) and ARGB_CHANNEL_MASK
        return (from + (to - from) * alpha).roundToInt().coerceIn(ARGB_CHANNEL_MIN, ARGB_CHANNEL_MAX).toLong()
    }
    return OPAQUE_BLACK_ARGB or
        (channel(ARGB_RED_SHIFT) shl ARGB_RED_SHIFT) or
        (channel(ARGB_GREEN_SHIFT) shl ARGB_GREEN_SHIFT) or
        channel(ARGB_BLUE_SHIFT)
}

internal fun ttsInlineDecorationSurface(
    sentenceFill: Long,
    content: Long,
): Long = blendOpaque(sentenceFill, content, TTS_INLINE_DECORATION_ALPHA)

private fun opaqueArgb(argb: Long): Long = OPAQUE_BLACK_ARGB or (argb and ARGB_RGB_MASK)

@Composable
internal fun rememberTtsReadAloudHighlightStyle(
    background: Color,
    content: Color,
    sentenceAccent: Color,
    wordAccent: Color,
    amoled: Boolean,
): TtsReadAloudHighlightStyle =
    remember(background, content, sentenceAccent, wordAccent, amoled) {
        resolveTtsReadAloudHighlightStyle(
            background = background.toArgb().toLong(),
            content = content.toArgb().toLong(),
            sentenceAccent = sentenceAccent.toArgb().toLong(),
            wordAccent = wordAccent.toArgb().toLong(),
            amoled = amoled,
        ).let {
            TtsReadAloudHighlightStyle(
                sentenceFill = colorFromArgb(it.sentenceFill),
                sentenceMarker = colorFromArgb(it.sentenceMarker),
                wordMarker = colorFromArgb(it.wordMarker),
            )
        }
    }

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

/**
 * Paints sentence and word progress from projection ranges using cached visual
 * character boxes, while exposing the same ranges to placement instrumentation.
 */
@Suppress("ReturnCount")
internal fun Modifier.ttsReadAloudHighlight(
    layoutResult: TextLayoutResult?,
    highlight: TtsLeafHighlight?,
    style: TtsReadAloudHighlightStyle,
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
    if (layoutResult == null || highlight == null) return this.then(semanticsModifier)
    val textLength = layoutResult.layoutInput.text.length
    val sentenceRanges =
        highlight.sentenceRanges
            .map { range -> ttsHighlightTextRange(range, textLength) }
            .filterNot(TextRange::collapsed)
    val wordRange = highlight.word?.let { ttsHighlightTextRange(it, textLength) }
    if (sentenceRanges.isEmpty() && wordRange?.collapsed != false) return this.then(semanticsModifier)
    return this.then(semanticsModifier).drawWithCache {
        val sentenceBoxes = sentenceRanges.flatMap { range -> highlightBoundingBoxes(layoutResult, range) }
        val wordBoxes =
            wordRange
                ?.takeUnless { it.collapsed }
                ?.let { highlightBoundingBoxes(layoutResult, it) }
                .orEmpty()
        onDrawBehind {
            sentenceBoxes.forEach { highlightBox ->
                val box = highlightBox.bounds
                drawRect(color = style.sentenceFill, topLeft = Offset(box.left, box.top), size = box.size)
            }
            wordBoxes.forEach { highlightBox ->
                val box = highlightBox.bounds
                val thickness = TTS_WORD_MARKER_WIDTH_DP.dp.toPx().coerceAtMost(box.height / 2f)
                drawRect(
                    color = style.wordMarker,
                    topLeft = Offset(box.left, box.bottom - thickness),
                    size =
                        Size(box.width, thickness),
                )
            }
        }
    }
}

internal fun ttsSentenceMarkerLeft(
    box: Rect,
    markerWidth: Float,
    direction: ResolvedTextDirection,
): Float = if (direction == ResolvedTextDirection.Rtl) box.right - markerWidth else box.left

private val TTS_SENTENCE_FILL_STRENGTHS = listOf(0.10f, 0.16f, 0.22f, 0.30f, 0.40f)
private const val TTS_SENTENCE_FILL_MIN_SEPARATION = 1.2
private const val TTS_INLINE_DECORATION_ALPHA = 0.08f
private const val TTS_WORD_MARKER_WIDTH_DP = 2f
private const val ARGB_CHANNEL_MASK = 0xFFL
private const val ARGB_RGB_MASK = 0xFFFFFFL
private const val ARGB_CHANNEL_MIN = 0
private const val ARGB_CHANNEL_MAX = 255
private const val ARGB_RED_SHIFT = 16
private const val ARGB_GREEN_SHIFT = 8
private const val ARGB_BLUE_SHIFT = 0

internal data class TtsHighlightBox(
    val bounds: Rect,
    val paragraphDirection: ResolvedTextDirection,
)

internal fun ttsSentenceMarkerBoxes(boxes: List<TtsHighlightBox>): List<TtsHighlightBox> =
    boxes
        .groupBy { it.bounds.top to it.bounds.bottom }
        .values
        .map { lineBoxes ->
            if (lineBoxes.first().paragraphDirection == ResolvedTextDirection.Rtl) {
                lineBoxes.maxBy { it.bounds.right }
            } else {
                lineBoxes.minBy { it.bounds.left }
            }
        }

/**
 * Converts a half-open logical range to exact visual character cells.
 *
 * A soft-wrap offset has two caret affinities, so deriving the rectangle from
 * `getHorizontalPosition()` can select the previous visual line at the start
 * of a sentence. Character bounding boxes have no caret ambiguity: each box
 * belongs to the rendered character at that offset. Adjacent cells are merged
 * only after they are assigned to a visual line, preserving disjoint bidi runs
 * and omitted projection spans without allocating rectangles during drawing.
 */
internal fun highlightBoundingBoxes(
    layoutResult: TextLayoutResult,
    range: TextRange,
): List<TtsHighlightBox> {
    val text = layoutResult.layoutInput.text.text
    val start = range.start.coerceIn(0, text.length)
    val end = range.end.coerceIn(start, text.length)
    if (start >= end) return emptyList()
    val characterBoxes = ArrayList<TtsHighlightBox>(end - start)
    var offset = start
    while (offset < end) {
        val box = layoutResult.getBoundingBox(offset)
        if (box.width > 0f && box.height > 0f) {
            characterBoxes +=
                TtsHighlightBox(
                    bounds = box,
                    paragraphDirection = layoutResult.getParagraphDirection(offset),
                )
        }
        offset += Character.charCount(Character.codePointAt(text, offset))
    }
    return characterBoxes.mergeAdjacentHighlightBoxes()
}

/** Coalesces touching cells on one visual line without bridging visual gaps. */
internal fun List<TtsHighlightBox>.mergeAdjacentHighlightBoxes(): List<TtsHighlightBox> =
    groupBy { box -> Triple(box.bounds.top, box.bounds.bottom, box.paragraphDirection) }
        .values
        .flatMap { lineBoxes ->
            lineBoxes
                .sortedBy { it.bounds.left }
                .fold(mutableListOf()) { merged: MutableList<TtsHighlightBox>, next ->
                    val previous = merged.lastOrNull()
                    if (previous != null && next.bounds.left <= previous.bounds.right + TTS_BOX_JOIN_TOLERANCE_PX) {
                        merged[merged.lastIndex] =
                            previous.copy(
                                bounds =
                                    Rect(
                                        left = min(previous.bounds.left, next.bounds.left),
                                        top = previous.bounds.top,
                                        right = max(previous.bounds.right, next.bounds.right),
                                        bottom = previous.bounds.bottom,
                                    ),
                            )
                    } else {
                        merged += next
                    }
                    merged
                }
        }

private const val TTS_BOX_JOIN_TOLERANCE_PX = 0.5f
