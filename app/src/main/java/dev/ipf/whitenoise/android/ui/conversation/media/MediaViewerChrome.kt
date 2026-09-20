package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

internal const val MEDIA_VIEWER_TOP_CHROME_TAG = "conversation.media.viewer.topChrome"
internal const val MEDIA_VIEWER_BOTTOM_CHROME_TAG = "conversation.media.viewer.bottomChrome"

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

/** Keeps an image-session choice across images while ensuring video controls cannot be stranded. */
internal fun mediaViewerChromeVisibilityAfterPageChange(
    currentlyVisible: Boolean,
    pageIsVideo: Boolean,
): Boolean = currentlyVisible || pageIsVideo
