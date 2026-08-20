@file:Suppress("TooManyFunctions")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal val TtsReadAloudHighlightRangeKey = SemanticsPropertyKey<IntRange>("TtsReadAloudHighlightRange")

internal val TtsReadAloudSentenceHighlightRangesKey =
    SemanticsPropertyKey<List<IntRange>>("TtsReadAloudSentenceHighlightRanges")

private var SemanticsPropertyReceiver.ttsReadAloudHighlightRange by TtsReadAloudHighlightRangeKey

private var SemanticsPropertyReceiver.ttsReadAloudSentenceHighlightRanges by TtsReadAloudSentenceHighlightRangesKey

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
@Composable
internal fun Modifier.ttsReadAloudHighlight(
    layoutResult: TextLayoutResult?,
    highlight: TtsLeafHighlight?,
): Modifier {
    val sentenceColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f)
    val wordColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.52f)
    val semanticsModifier =
        if (highlight == null || highlight.sentenceRanges.isEmpty()) {
            Modifier
        } else {
            Modifier.semantics {
                ttsReadAloudSentenceHighlightRanges = highlight.sentenceRanges
                ttsReadAloudHighlightRange = highlight.wordRange ?: highlight.sentenceRanges.first()
            }
        }
    if (layoutResult == null || highlight == null) return this.then(semanticsModifier)
    val textLength = layoutResult.layoutInput.text.length
    val sentenceRanges =
        highlight.sentenceRanges
            .map { ttsHighlightTextRange(it, textLength) }
            .filterNot(TextRange::collapsed)
    val wordRange =
        highlight.wordRange
            ?.let { ttsHighlightTextRange(it, textLength) }
            ?.takeUnless(TextRange::collapsed)
    if (sentenceRanges.isEmpty()) return this.then(semanticsModifier)
    return this.then(semanticsModifier).drawBehind {
        sentenceRanges.forEach { range ->
            highlightBoundingBoxes(layoutResult, range).forEach { box ->
                drawRect(color = sentenceColor, topLeft = Offset(box.left, box.top), size = box.size)
            }
        }
        wordRange?.let { range ->
            highlightBoundingBoxes(layoutResult, range).forEach { box ->
                drawRect(color = wordColor, topLeft = Offset(box.left, box.top), size = box.size)
            }
        }
    }
}

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
    return boxes
}
