package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import kotlinx.coroutines.Job

internal data class ConversationSearchScrollAnchor(
    val bookmark: ConversationScrollBookmark,
    val match: ConversationSearchMatch?,
)

/**
 * UI-only navigation state kept behind one remembered reference so the already
 * large [ConversationScreen] method does not keep every independent snapshot
 * state and job in its verifier register set for the full composition.
 */
internal class ConversationNavigationState(
    initialFollowedLatestId: String?,
    initialSeedTailAwaitingAuthoritative: Boolean,
    surfaceState: ConversationSurfaceState,
) {
    /**
     * Id of the newest row the tail follow has reacted to. Plain bookkeeping on purpose: composition reads it only
     * to arm the tail entrance in the frame that inserts a row, which a timeline change always recomposes, so the
     * follow advancing it afterwards must not recompose the whole conversation again.
     */
    var lastFollowedLatestId: String? = initialFollowedLatestId
    var seedTailAwaitingAuthoritative by mutableStateOf(initialSeedTailAwaitingAuthoritative)
    var initialTimelineLoadStarted by mutableStateOf(false)
    var initialTimelineBackfillNoProgress by surfaceState.initialTimelineBackfillNoProgress
    var initialTimelineBackfillRetryGeneration by mutableLongStateOf(0L)
    val targetHighlight = MessageTargetHighlightLifecycle()
    val focusTargetRetry = SearchTargetRetryState()

    // Any newer navigation or a reader scroll supersedes a failed search landing, so its Retry leaves with it.
    val targetNavigation = MessageTargetNavigationOwner(onOwnershipChanged = focusTargetRetry::clear)
    var navigateReplyJob by mutableStateOf<Job?>(null)
    var searchOpen by surfaceState.searchOpen
    val searchState = surfaceState.searchState
    var searchQuery: String
        get() = searchState.value.query
        set(value) {
            searchState.value = searchState.value.copy(query = value)
        }
    var searchPinnedMatchId by mutableStateOf<String?>(null)
    var searchJob by mutableStateOf<Job?>(null)
    var preSearchScrollAnchor by mutableStateOf<ConversationSearchScrollAnchor?>(null)
    var historySearchRetryGeneration by mutableStateOf(0)
    val timelineItemHeightsPx = mutableStateMapOf<String, Int>()
    val searchFocusRequester = FocusRequester()

    /** Cancels navigation work whose lifetime is owned by this remembered route state. */
    fun cancelJobs() {
        searchJob?.cancel()
        navigateReplyJob?.cancel()
        targetNavigation.cancel()
        targetHighlight.clear()
    }
}
