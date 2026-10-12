package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.AccountWideRead
import dev.ipf.whitenoise.android.state.ProfileAccountWideGroups
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.readAccountWideForwardTargets
import dev.ipf.whitenoise.android.state.requestProfileGroupMembers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The chats beyond the retained chat-list window behind one open profile surface (#2983). It reads MDK's
 * account-wide snapshot, sends roster requests for chats outside the window through that snapshot, and records
 * which of those rosters stayed unresolved, so a picker offers Retry instead of loading forever.
 *
 * A snapshot is served only while the controller's chat rows are at the [rowsRevision] it was read under: a
 * chat deleted from this device since then would still be in it, and the window no longer holds its row to
 * shadow it.
 */
@Stable
internal class ProfileAccountWideGroupsHolder(
    private val appState: WhiteNoiseAppState,
    private val scope: CoroutineScope,
    private val rowsRevision: () -> Long = { appState.chatRowsRevision() },
) {
    /** The latest snapshot and failures, replaced rather than mutated so Compose observes every change. */
    var value by mutableStateOf(ProfileAccountWideGroups())
        private set

    /** The chat-row revision [value]'s snapshot was read under, or null before the first read. */
    private var loadedRevision by mutableStateOf<Long?>(null)

    /** Identifies the newest read, so a slower older read can never replace its result. */
    private var latestRead = 0L

    /** The lookups to serve now: the latest snapshot if it matches the current chat rows, else none. */
    fun current(): ProfileAccountWideGroups =
        if (loadedRevision == rowsRevision()) {
            value
        } else {
            ProfileAccountWideGroups()
        }

    /**
     * Reads the account-wide snapshot. The result applies only if this is still the newest read and the chat
     * rows have not changed since it started. A failed read is recorded so the lookups offer Retry, while a
     * read that could not run leaves them on the retained rows alone.
     */
    suspend fun load() {
        val token = ++latestRead
        val revision = rowsRevision()
        val read = appState.readAccountWideForwardTargets()
        if (token == latestRead && revision == rowsRevision()) {
            value =
                ProfileAccountWideGroups(
                    snapshot = (read as? AccountWideRead.Loaded)?.snapshot,
                    readFailed = read is AccountWideRead.Failed,
                )
            loadedRevision = revision
        }
    }

    /**
     * Requests the rosters of [groupIds]: retained chats through the controller as before, chats beyond the
     * window through the snapshot. [retry] clears their earlier failures first, and rereads a snapshot whose
     * first read failed.
     */
    fun requestMembers(
        groupIds: Set<String>,
        retry: Boolean = false,
    ) {
        appState.requestProfileGroupMembers(groupIds, retry)
        val snapshot = value.snapshot
        if (snapshot == null) {
            if (retry) scope.launch { load() }
            return
        }
        if (retry) value = value.copy(failedGroupIds = value.failedGroupIds - groupIds)
        scope.launch {
            val missing = snapshot.resolveMembersBeyondWindow(groupIds)
            if (value.snapshot === snapshot) {
                value = value.copy(failedGroupIds = value.failedGroupIds - groupIds + missing)
            }
        }
    }
}

/**
 * Keeps one [ProfileAccountWideGroupsHolder] per active account and, once [enabled], reads its snapshot again
 * whenever the controller's chat rows change, so a surface that is not showing group lookups never pays for the
 * account-wide read and a showing one never keeps a chat deleted since its last read.
 */
@Composable
internal fun rememberProfileAccountWideGroups(
    appState: WhiteNoiseAppState,
    enabled: Boolean,
): ProfileAccountWideGroupsHolder {
    val scope = rememberCoroutineScope()
    val accountRef = appState.activeAccountRef
    val holder = remember(appState, accountRef) { ProfileAccountWideGroupsHolder(appState, scope) }
    val rowsRevision = appState.chatRowsRevision()
    LaunchedEffect(holder, enabled, rowsRevision) {
        if (enabled) holder.load()
    }
    return holder
}

/** Projects one profile group picker's state for a target identity from the live controller caches. */
internal typealias ProfileGroupPickerProjection =
    WhiteNoiseAppState.(String, ProfileAccountWideGroups) -> ProfileGroupPickerState

/**
 * Remembers one profile group picker's state for [targetAccountIdHex] through [project]. It recomputes on
 * [WhiteNoiseAppState.forwardTargetsRevision] and the roster revision rather than on the visible chat-list
 * items, because those stay frozen while the chat list is hidden behind a conversation, and group changes
 * there must still reach a picker opened from a profile or a direct chat's settings.
 */
@Composable
internal fun rememberProfileGroupPickerState(
    appState: WhiteNoiseAppState,
    targetAccountIdHex: String?,
    accountWide: ProfileAccountWideGroups,
    project: ProfileGroupPickerProjection,
): ProfileGroupPickerState {
    val liveGroupsRevision = appState.forwardTargetsRevision
    val rosterRevision = appState.profileGroupPickerRevision
    return remember(targetAccountIdHex, liveGroupsRevision, rosterRevision, accountWide) {
        targetAccountIdHex?.let { appState.project(it, accountWide) } ?: ProfileGroupPickerState.empty()
    }
}

/** The controller's chat-row membership revision, or 0 before a controller is attached. */
private fun WhiteNoiseAppState.chatRowsRevision(): Long {
    val controller = groupPresentationChatsController ?: return 0L
    return controller.materializedGroupsRevision
}
