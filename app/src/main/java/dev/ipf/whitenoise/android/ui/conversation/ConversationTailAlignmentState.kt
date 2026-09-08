package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

@Stable
internal class ConversationBottomInsetState(
    initialRoutePresentationFrozen: Boolean,
) {
    val measuredBottomChromeHeightPx = mutableStateOf<Int?>(null)
    val bottomInputRevision = mutableLongStateOf(0L)
    val routePresentationFrozen = mutableStateOf(initialRoutePresentationFrozen)
}

@Stable
internal class ConversationSeededTailState(
    initialTimelineAnchored: Boolean,
    initialCommitted: Boolean,
) {
    val initialTimelineAnchored = mutableStateOf(initialTimelineAnchored)
    val committed = mutableStateOf(initialCommitted)
    val recoveryVisible = mutableStateOf(false)
    val retryGeneration = mutableLongStateOf(0L)
}
