package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

internal const val CHAT_FOLDER_TRANSITION_MILLIS = 160

/** A bounded destination-only arrival: old folders never survive as interactive or private visual copies. */
@Composable
internal fun rememberChatFolderArrival(
    owner: Any,
    folderId: String?,
): Modifier {
    val enabled = rememberSystemAnimationsEnabled()
    val offset = remember(owner) { Animatable(0f) }
    var previousFolder by remember(owner) { mutableStateOf(folderId) }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(owner, folderId, enabled) {
        if (!enabled) {
            previousFolder = folderId
            offset.snapTo(0f)
        } else if (previousFolder != folderId || offset.value != 0f) {
            previousFolder = folderId
            // A reversal continues the rendered displacement instead of restarting the entrance.
            if (offset.value == 0f) offset.snapTo(distance)
            offset.animateTo(0f, tween(CHAT_FOLDER_TRANSITION_MILLIS))
        }
    }
    return Modifier.graphicsLayer { translationY = if (enabled) offset.value else 0f }
}
