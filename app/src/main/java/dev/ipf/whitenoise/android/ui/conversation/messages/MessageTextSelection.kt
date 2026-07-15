package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import kotlin.math.max
import kotlin.math.min

internal data class SelectableTextLayout(
    val key: Any,
    val layoutResult: TextLayoutResult,
    val coordinates: LayoutCoordinates,
)

internal class SelectableTextLayoutTracker {
    var layoutResult: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
}

internal fun nearestNonWhitespaceOffset(
    text: String,
    offset: Int,
): Int? {
    if (text.isEmpty()) return null
    val origin = offset.coerceIn(0, text.lastIndex)
    if (!text[origin].isWhitespace()) return origin

    for (distance in 1..text.length) {
        val after = origin + distance
        if (after <= text.lastIndex && !text[after].isWhitespace()) return after
        val before = origin - distance
        if (before >= 0 && !text[before].isWhitespace()) return before
    }
    return null
}

/**
 * Maps a window-space press to a word in SelectionState's global text space.
 * The geometric ordering intentionally mirrors Compose SelectionRegistrarImpl:
 * side-by-side Text nodes sort by x, other nodes by y.
 */
internal fun textSelectionSeedRange(
    layouts: Collection<SelectableTextLayout>,
    pressInWindow: Offset?,
): TextRange? {
    val ordered =
        layouts
            .filter { it.coordinates.isAttached }
            .sortedWith { first, second -> compareSelectableTextLayouts(first, second) }
    if (ordered.isEmpty()) return null

    val target =
        if (pressInWindow == null) {
            ordered.firstOrNull {
                it.layoutResult.layoutInput.text
                    .isNotBlank()
            }
        } else {
            ordered
                .asSequence()
                .filter {
                    it.layoutResult.layoutInput.text
                        .isNotBlank()
                }.minByOrNull { squaredDistanceToRect(pressInWindow, it.coordinates.boundsInWindow()) }
        } ?: return null

    val text = target.layoutResult.layoutInput.text.text
    val rawOffset =
        if (pressInWindow == null) {
            0
        } else {
            val local = target.coordinates.windowToLocal(pressInWindow)
            target.layoutResult.getOffsetForPosition(
                Offset(
                    x =
                        local.x.coerceIn(
                            0f,
                            target.coordinates.size.width
                                .toFloat(),
                        ),
                    y =
                        local.y.coerceIn(
                            0f,
                            target.coordinates.size.height
                                .toFloat(),
                        ),
                ),
            )
        }
    val wordOffset = nearestNonWhitespaceOffset(text, rawOffset) ?: return null
    val localRange = target.layoutResult.getWordBoundary(wordOffset)
    val precedingLength =
        ordered
            .takeWhile { it.key !== target.key }
            .sumOf { it.layoutResult.layoutInput.text.length }
    return TextRange(
        start = precedingLength + localRange.start,
        end = precedingLength + localRange.end,
    )
}

private fun compareSelectableTextLayouts(
    first: SelectableTextLayout,
    second: SelectableTextLayout,
): Int {
    val firstBounds = first.coordinates.boundsInWindow()
    val secondBounds = second.coordinates.boundsInWindow()
    return if (selectionTextLayoutsAreInARow(firstBounds, secondBounds)) {
        compareValues(firstBounds.left, secondBounds.left)
    } else {
        compareValues(firstBounds.top, secondBounds.top)
    }
}

private fun selectionTextLayoutsAreInARow(
    first: Rect,
    second: Rect,
): Boolean {
    val verticalIntersection = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
    val horizontalIntersection = max(0f, min(first.right, second.right) - max(first.left, second.left))
    val verticallyAligned =
        verticalIntersection >= first.height * 0.5f || verticalIntersection >= second.height * 0.5f
    val horizontallyDistinct =
        horizontalIntersection < first.width * 0.5f && horizontalIntersection < second.width * 0.5f
    return verticallyAligned && horizontallyDistinct
}

private fun squaredDistanceToRect(
    point: Offset,
    rect: Rect,
): Float {
    val dx =
        when {
            point.x < rect.left -> rect.left - point.x
            point.x > rect.right -> point.x - rect.right
            else -> 0f
        }
    val dy =
        when {
            point.y < rect.top -> rect.top - point.y
            point.y > rect.bottom -> point.y - rect.bottom
            else -> 0f
        }
    return dx * dx + dy * dy
}

@Composable
internal fun rememberExitOnCopyClipboard(onCopyCompleted: () -> Unit): Clipboard {
    val delegate = LocalClipboard.current
    val latestOnCopyCompleted by rememberUpdatedState(onCopyCompleted)
    return remember(delegate) {
        ExitOnCopyClipboard(delegate) { latestOnCopyCompleted() }
    }
}

internal class ExitOnCopyClipboard(
    private val delegate: Clipboard,
    private val onCopyCompleted: () -> Unit,
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        delegate.setClipEntry(clipEntry)
        onCopyCompleted()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override val nativeClipboard: ClipboardManager
        get() = delegate.nativeClipboard
}
