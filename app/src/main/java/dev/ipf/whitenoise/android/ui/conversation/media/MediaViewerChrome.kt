package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

internal const val MEDIA_VIEWER_TOP_CHROME_TAG = "conversation.media.viewer.topChrome"
internal const val MEDIA_VIEWER_BOTTOM_CHROME_TAG = "conversation.media.viewer.bottomChrome"
internal const val MEDIA_VIEWER_PAGE_GESTURE_TAG = "conversation.media.viewer.pageGesture"
internal const val MEDIA_VIEWER_CONTENT_TAG = "conversation.media.viewer.content"

/** Arbitrates image single-tap chrome toggles against double-tap zoom with its viewport position. */
internal fun Modifier.viewerTapGestureModifier(
    gestureKey: Any?,
    onSingleTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
): Modifier =
    pointerInput(gestureKey) {
        detectTapGestures(
            onTap = { onSingleTap() },
            onDoubleTap = onDoubleTap,
        )
    }

/**
 * Keeps an image-session choice across images while ensuring video controls cannot be stranded. A video page
 * always opens with its chrome, then its playback controls and the chrome reveal and hide together.
 */
internal fun mediaViewerChromeVisibilityAfterPageChange(
    currentlyVisible: Boolean,
    pageIsVideo: Boolean,
): Boolean = currentlyVisible || pageIsVideo

/** Safe-area sides the media body always pads itself away from, whether or not the chrome shows (#3801). */
internal val MEDIA_VIEWER_CONTENT_INSET_SIDES = WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical

/** One short fade for the chrome and the video controls, so a toggle never flickers. */
internal const val MEDIA_VIEWER_CHROME_FADE_MILLIS = 200

/** Height of the bottom action row above the bottom safe inset: 48 dp buttons and 8 dp above and below. */
internal val MEDIA_VIEWER_BOTTOM_ACTIONS_HEIGHT = 64.dp

/**
 * How far the bottom actions overlap the bottom of the media body when they show. Video controls pad themselves by
 * this much so the seek bar sits just above the actions instead of under them.
 */
internal val LocalMediaViewerBottomActionsClearance = staticCompositionLocalOf { 0.dp }
