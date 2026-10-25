package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

/** Decorative content has no lifetime beyond the visible conversation or the system motion setting. */
@Composable
internal fun rememberConversationMotionEnabled(): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return rememberSystemAnimationsEnabled() && durationScale > 0f && lifecycle.isAtLeast(Lifecycle.State.STARTED)
}
