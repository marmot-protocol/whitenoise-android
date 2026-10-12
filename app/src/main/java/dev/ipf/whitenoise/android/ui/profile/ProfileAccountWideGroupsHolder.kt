package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ProfileAccountWideGroups
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.requestProfileGroupMembers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The chats beyond the retained chat-list window behind one open profile surface (#2983). It reads MDK's
 * account-wide snapshot once, sends roster requests for chats outside the window through that snapshot, and
 * records which of those rosters stayed unresolved, so a picker offers Retry instead of loading forever.
 */
@Stable
internal class ProfileAccountWideGroupsHolder(
    private val appState: WhiteNoiseAppState,
    private val scope: CoroutineScope,
) {
    /** The current snapshot and failures, replaced rather than mutated so Compose observes every change. */
    var value by mutableStateOf(ProfileAccountWideGroups())
        private set

    /** Reads the account-wide snapshot. A failed read leaves the lookups on the retained rows alone. */
    suspend fun load() {
        value = ProfileAccountWideGroups(snapshot = appState.loadAccountWideForwardTargets())
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
 * Keeps one [ProfileAccountWideGroupsHolder] per active account and reads its snapshot once [enabled], so a
 * surface that is not showing group lookups never pays for the account-wide read.
 */
@Composable
internal fun rememberProfileAccountWideGroups(
    appState: WhiteNoiseAppState,
    enabled: Boolean,
): ProfileAccountWideGroupsHolder {
    val scope = rememberCoroutineScope()
    val accountRef = appState.activeAccountRef
    val holder = remember(appState, accountRef) { ProfileAccountWideGroupsHolder(appState, scope) }
    LaunchedEffect(holder, enabled) {
        if (enabled && holder.value.snapshot == null) holder.load()
    }
    return holder
}
