package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class GroupRosterLoadState {
    LOADING,
    READY,
    FAILED,
    INCONSISTENT,
}

internal enum class GroupRosterRefreshEvent {
    STARTED,
    SUCCEEDED,
    FAILED,
    INCONSISTENT,
}

internal fun reduceGroupRosterLoadState(
    current: GroupRosterLoadState,
    event: GroupRosterRefreshEvent,
): GroupRosterLoadState =
    when (event) {
        GroupRosterRefreshEvent.STARTED ->
            if (current == GroupRosterLoadState.READY) {
                current
            } else {
                GroupRosterLoadState.LOADING
            }
        GroupRosterRefreshEvent.SUCCEEDED -> GroupRosterLoadState.READY
        GroupRosterRefreshEvent.FAILED ->
            if (current == GroupRosterLoadState.READY) {
                current
            } else {
                GroupRosterLoadState.FAILED
            }
        GroupRosterRefreshEvent.INCONSISTENT -> GroupRosterLoadState.INCONSISTENT
    }

internal fun restoreGroupRosterLoadStateAfterCancellation(
    previous: GroupRosterLoadState,
    current: GroupRosterLoadState,
): GroupRosterLoadState =
    if (current != GroupRosterLoadState.LOADING) {
        current
    } else {
        previous.takeUnless { it == GroupRosterLoadState.LOADING } ?: GroupRosterLoadState.FAILED
    }

internal class GroupRosterLoadTracker(
    initial: GroupRosterLoadState,
) {
    var state by mutableStateOf(initial)
        private set

    private var lastSettledState =
        initial.takeUnless { it == GroupRosterLoadState.LOADING }
            ?: GroupRosterLoadState.FAILED

    fun transition(event: GroupRosterRefreshEvent) {
        state = reduceGroupRosterLoadState(state, event)
        if (state != GroupRosterLoadState.LOADING) {
            lastSettledState = state
        }
    }

    fun restoreAfterCancellation() {
        state =
            restoreGroupRosterLoadStateAfterCancellation(
                previous = lastSettledState,
                current = state,
            )
    }
}
