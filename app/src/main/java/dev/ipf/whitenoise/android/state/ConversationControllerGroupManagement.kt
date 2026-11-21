package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R

/**
 * Set the per-group disappearing-message retention. `0` disables it; any
 * positive value is the NIP-40 expiration the engine applies to outgoing
 * kind:445 events. Optimistically updates the local group so the row
 * reflects the new value without waiting for the group-state subscription.
 */
suspend fun ConversationController.updateMessageRetention(disappearingMessageSecs: ULong): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        // Stay bound to the conversation's account (like every other mutation
        // here); activeAccountRef could shift if the user switches accounts
        // before this completes, sending the retention change to the wrong store.
        val account = conversationAccountRef ?: return@withMutationLockResult false
        runCatchingCancellable {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                appState.runGroupChange(
                    account,
                    group.groupIdHex,
                    actionLabel = R.string.disappearing_messages,
                    retentionSeconds = disappearingMessageSecs,
                ) {
                    appState.marmotIo { updateMessageRetention(account, group.groupIdHex, disappearingMessageSecs) }
                }
            }
            val previousRetention = group.disappearingMessageSecs
            group = group.copy(disappearingMessageSecs = disappearingMessageSecs)
            // The engine prunes plaintext older than the new window during the
            // call above. Reload the open timeline so the admin who just set
            // the timer sees the pruned state immediately, instead of only
            // after leaving and re-entering the chat. The retention change has
            // already succeeded, so a refresh failure must NOT flip this to a
            // failure toast — log it and fall back to an in-memory re-filter.
            runCatchingCancellable { refreshCurrentTimeline(account) }
                .onFailure { refreshError ->
                    if (BuildConfig.DEBUG) Log.w("DMConversation", "retention refresh failed", refreshError)
                    publishTimelineFromIndexes()
                }
            // No success banner: the header timer and the timeline's system row already show the new
            // setting, and a banner here covered the chat header right after the sheet closed.
            true
        }.onFailure {
            recordMutationFailure(R.string.toast_couldnt_update_disappearing, "GROUP_RETENTION_UPDATE", it)
        }.getOrDefault(false)
    }

/** Cancel the durable leave for the bound account, then read membership from MDK. */
internal suspend fun ConversationController.cancelBlockedLeave(): Boolean =
    withMutationLockResult(false) {
        val account = conversationAccountRef ?: return@withMutationLockResult false
        val groupId = group.groupIdHex
        if (!ownsBlockedLeaveAction(account, groupId) || !canCancelBlockedLeave) return@withMutationLockResult false
        lastMutationError = null
        val result = runCatchingCancellable { cancelNativeBlockedLeave(account, groupId) }
        if (!ownsBlockedLeaveAction(account, groupId) || result.getOrNull() == false) {
            return@withMutationLockResult false
        }
        // MDK can durably cancel before its membership projection is updated.
        // Read back even after a native error or an already-cancelled request.
        refreshManagementState()
        if (!ownsBlockedLeaveAction(account, groupId)) return@withMutationLockResult false
        val stayed = isSelfMember && !showsBlockedLeave && !group.leaveRequestPending
        if (!stayed) {
            result.exceptionOrNull()?.let {
                recordMutationFailure(R.string.toast_couldnt_cancel_leave, "GROUP_CANCEL_LEAVE", it)
            }
        }
        stayed
    }

/** Details and capabilities share one native frontier; later group updates supersede this read. */
suspend fun ConversationController.refreshManagementState(): Boolean {
    val account = conversationAccountRef ?: return false
    var applied = false
    val requestToken = managementStateLifetime.advance()
    val authorityEpoch = mutationPresentation.groupAuthorityEpoch
    val rosterGeneration = memberRosterRefreshGeneration.capture()
    val runtimeGeneration = appState.runtimeGeneration
    val groupId = group.groupIdHex
    val activeAccount = appState.activeAccountRef
    val startingGroup = group
    runCatchingCancellable {
        groupSnapshotReader(account, groupId)
    }.onSuccess { refreshed ->
        managementStateLifetime.runIfCurrent(requestToken) {
            val staleRead =
                !ownsCurrentMemberRead(rosterGeneration, runtimeGeneration) ||
                    mutationPresentation.groupAuthorityEpoch != authorityEpoch
            val changedOwner = group != startingGroup || appState.activeAccountRef != activeAccount
            val wrongTarget =
                !refreshed.details.group.groupIdHex
                    .equals(groupId, ignoreCase = true) ||
                    !refreshed.managementState.myAccountIdHex.equals(conversationAccountIdHex, ignoreCase = true) ||
                    !matchesConversation(account, groupId)
            if (staleRead || changedOwner || wrongTarget) return@runIfCurrent
            applied = applyManagementSnapshot(account, refreshed.details, refreshed.managementState)
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMConversation", "management state refresh failed", it)
    }
    return applied
}

/** Install and require the lifecycle component in one admin commit. */
suspend fun ConversationController.enableGroupDisbanding(): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        runCatchingCancellable {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                val result =
                    appState.runGroupChange(
                        account,
                        group.groupIdHex,
                        actionLabel = R.string.group_disband_enable_action,
                    ) {
                        appState.marmotIo { enableGroupDisbanding(account, group.groupIdHex) }
                    }
                applyMutationDetails(account, result.details)
            }
            refreshManagementState()
            true
        }.onFailure {
            recordMutationFailure(R.string.toast_couldnt_enable_disbanding, "GROUP_DISBAND_ENABLE", it)
        }.getOrDefault(false)
    }

/**
 * Durably accept the irreversible disband. Completion converges through
 * normal group-state updates; the request itself survives restarts.
 */
suspend fun ConversationController.disbandGroup(): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        runCatchingCancellable {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                appState.runGroupChange(account, group.groupIdHex, actionLabel = R.string.group_disband_action) {
                    appState.marmotIo { disbandGroup(account, group.groupIdHex) }
                }
            }
            refreshManagementState()
            true
        }.onFailure {
            recordMutationFailure(R.string.toast_couldnt_disband, "GROUP_DISBAND", it)
        }.getOrDefault(false)
    }

/** Clear a failed disband request so the action can be retried. */
suspend fun ConversationController.acknowledgeDisbandFailure(): Boolean =
    withMutationLockResult(false) {
        val account = conversationAccountRef ?: return@withMutationLockResult false
        runCatchingCancellable {
            appState.marmotIo { acknowledgeDisbandFailure(account, group.groupIdHex) }
            refreshManagementState()
            true
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMConversation", "acknowledge disband failure failed", it)
        }.getOrDefault(false)
    }
