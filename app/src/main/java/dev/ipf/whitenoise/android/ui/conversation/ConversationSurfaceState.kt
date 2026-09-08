package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * Controller-scoped, transient presentation shared by the shell and its conversation.
 * Retained outgoing content keeps its own instance; none of this enters saved or protocol state.
 */
internal class ConversationSurfaceState {
    val showDetails = mutableStateOf(false)
    val searchOpen = mutableStateOf(false)
    val initialTimelineBackfillNoProgress = mutableStateOf(false)
    val selectedMessages = mutableStateMapOf<String, BatchMessageSelection>()
}
