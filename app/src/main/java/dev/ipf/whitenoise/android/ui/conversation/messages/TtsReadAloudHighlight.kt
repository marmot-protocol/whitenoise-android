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
import androidx.compose.ui.graphics.Color
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
import dev.ipf.whitenoise.android.ui.TtsLeafHighlightResolver
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal val TtsReadAloudHighlightRangeKey = SemanticsPropertyKey<IntRange>("TtsReadAloudHighlightRange")

private var SemanticsPropertyReceiver.ttsReadAloudHighlightRange by TtsReadAloudHighlightRangeKey

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
internal fun rememberTtsLeafHighlightResolver(
    passage: TtsPassage?,
    messageIdHex: String,
    projection: SpeakableTextProjection?,
    locale: Locale,
): TtsLeafHighlightResolver? =
    remember(passage, messageIdHex, projection, locale) {
        buildTtsLeafHighlightResolver(
            passage = passage,
            messageIdHex = messageIdHex,
            projection = projection,
            locale = locale,
        )
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
internal fun ttsReadAloudHighlightColor(): Color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)

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
    highlightRange: IntRange?,
    color: Color,
): Modifier {
    val semanticsModifier =
        if (highlightRange == null) {
            Modifier
        } else {
            Modifier.semantics { ttsReadAloudHighlightRange = highlightRange }
        }
    if (layoutResult == null || highlightRange == null) return this.then(semanticsModifier)
    val range = ttsHighlightTextRange(highlightRange, layoutResult.layoutInput.text.length)
    if (range.collapsed) return this.then(semanticsModifier)
    return this.then(semanticsModifier).drawBehind {
        highlightBoundingBoxes(layoutResult, range).forEach { box ->
            drawRect(color = color, topLeft = Offset(box.left, box.top), size = box.size)
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
