package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.unit.Constraints

internal const val MEDIA_VIEWER_TOP_CHROME_TAG = "conversation.media.viewer.topChrome"
internal const val MEDIA_VIEWER_BOTTOM_CHROME_TAG = "conversation.media.viewer.bottomChrome"
internal const val MEDIA_VIEWER_PAGE_GESTURE_TAG = "conversation.media.viewer.pageGesture"
internal const val MEDIA_VIEWER_CONTENT_TAG = "conversation.media.viewer.content"

/** Arbitrates image single-tap chrome toggles against double-tap transform resets. */
internal fun Modifier.viewerTapGestureModifier(
    gestureKey: Any?,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
): Modifier =
    pointerInput(gestureKey) {
        detectTapGestures(
            onTap = { onSingleTap() },
            onDoubleTap = { onDoubleTap() },
        )
    }

/**
 * Keeps an image-session choice across images while ensuring video controls cannot be stranded. A video page
 * always opens with its chrome, then follows its native controller: both reveal and hide together.
 */
internal fun mediaViewerChromeVisibilityAfterPageChange(
    currentlyVisible: Boolean,
    pageIsVideo: Boolean,
): Boolean = currentlyVisible || pageIsVideo

/**
 * Safe-area sides the media body pads itself away from. Visible bars already pad the top and bottom insets, so
 * the body only avoids side cutouts, while hidden chrome leaves the body to avoid every system bar and cutout.
 */
internal fun mediaViewerContentInsetSides(chromeVisible: Boolean): WindowInsetsSides =
    if (chromeVisible) {
        WindowInsetsSides.Horizontal
    } else {
        WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical
    }

/**
 * Lays out the viewer's three slots — top chrome, media body and bottom chrome — so the body receives only the
 * rectangle between whichever bars are present. Opaque chrome can then never cover the unzoomed media frame or a
 * player's own time bar, and hidden chrome hands its space back to the media. Each chrome slot emits at most one
 * bar, the body is placed first so the bars draw over anything it overdraws, and the frame is always bounded.
 */
internal val mediaViewerChromeMeasurePolicy =
    MultiContentMeasurePolicy { (topBars, bodies, bottomBars), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val barConstraints = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
        val top = topBars.map { it.measure(barConstraints) }
        val bottom = bottomBars.map { it.measure(barConstraints) }
        val topHeight = top.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
        val bodyHeight = (height - topHeight - bottomHeight).coerceAtLeast(0)
        val body = bodies.map { it.measure(Constraints.fixed(width, bodyHeight)) }
        layout(width, height) {
            body.forEach { it.place(0, topHeight) }
            top.forEach { it.place(0, 0) }
            bottom.forEach { it.place(0, height - it.height) }
        }
    }
