package dev.ipf.whitenoise.android.state

import androidx.annotation.VisibleForTesting
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.ui.chats.newchat.directChatPreferenceOrder

internal fun ChatsController.foldGroup(
    record: AppGroupRecordFfi,
    invalidateMembers: Boolean = true,
    fromSubscription: Boolean = true,
) {
    // A snapshot still carrying a name this device just renamed away from must not restore it
    // underneath the row; [chatListDisplayGroup] falls back to this record's name. A locally
    // committed record is the edit itself, so it neither needs the fence nor counts as the
    // record stream agreeing with it.
    val folded = if (fromSubscription) withRetainedName(record) else record
    groupRecordsById = groupRecordsById + (record.groupIdHex to folded)
    if (fromSubscription) {
        // Local projections can combine a roster's LEFT with an older leave-request flag.
        syncVoiceTranscriptionMembership(folded)
        chatRowsByGroup[chatRowKey(record.groupIdHex)]?.let { settleInvitationFromMdk(it) }
    }
    if (invalidateMembers) invalidateMemberCacheForGroup(record.groupIdHex)
    scheduleRecompute()
}

/** Reconnect snapshots and streaming updates both cover chats outside the retained windows. */
internal fun ChatsController.installGroupRecordsSnapshot(records: Map<String, AppGroupRecordFfi>) {
    groupRecordsById = records.mapValues { (_, group) -> withRetainedName(group) }
    groupRecordsById.values.forEach { syncVoiceTranscriptionMembership(it) }
}

/** Updates the account-wide transcript scope even when this group is outside the visible chat list. */
private fun ChatsController.syncVoiceTranscriptionMembership(record: AppGroupRecordFfi) {
    val account = accountRef ?: return
    val departed = record.endsVoiceTranscription()
    if (departed || record.selfMembership == SelfMembershipFfi.MEMBER) {
        appState.receivedVoiceTranscripts.updateGroupMembership(account, record.groupIdHex, departed)
    }
}

/**
 * [record] carrying the retained local name in place of one this device just renamed away from.
 * A newer name ends the fence.
 */
internal fun ChatsController.withRetainedName(record: AppGroupRecordFfi): AppGroupRecordFfi {
    val name = localGroupNames.reconcileRecord(record.groupIdHex, record.name)
    return if (name == record.name) record else record.copy(name = name)
}

/** Folds one record exactly as the account-wide chats subscription does, for chats outside the window. */
@VisibleForTesting
internal fun ChatsController.foldGroupRecordForTest(record: AppGroupRecordFfi) = foldGroup(record)

/** Applies a local group mutation and invalidates any older roster fetch. */
internal fun ChatsController.applyLocalGroupProjection(
    record: AppGroupRecordFfi,
    members: List<AppGroupMemberRecordFfi>?,
    clearedName: String? = null,
) {
    if (accountRef == null) return
    val rowKey = chatRowKey(record.groupIdHex)
    val retained = groupRecordsById[record.groupIdHex] != null || chatRowsByGroup.containsKey(rowKey)
    if (members != null) {
        memberCacheLifetime.advance()
        presentationMembersByGroup = presentationMembersByGroup - record.groupIdHex
        cancelMemberSnapshotRetry(record.groupIdHex)
        memberFetchRetryBackoffTierByGroup.remove(record.groupIdHex)
        failedMemberFetches.remove(record.groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(record.groupIdHex)
        members
            .map { it.memberIdHex }
            .filter { it.isNotBlank() }
            .forEach(appState::requestProfile)
        val updated =
            applyAuthoritativeChatListMembers(
                groupIdHex = record.groupIdHex,
                members = members,
                activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
                memberCacheByGroup = memberCacheByGroup,
                removedGroupIds = removedGroupIds,
            )
        memberCacheByGroup = updated.memberCacheByGroup
        removedGroupIds = updated.removedGroupIds
        memberSnapshotsRevision += 1L
    }
    // Off-window profile management uses the same authoritative roster cache. Updating it must
    // not insert a chat into the retained native window or create a second group projection.
    if (!retained) {
        if (members != null) scheduleRecompute()
        return
    }
    // chatListItemFromProjection reads row.archived / row.pendingConfirmation
    // (not just the group record), so patch both the chat row and the group
    // record to keep them consistent.
    (optimisticChatListPreviewByGroup[rowKey]?.baselineRow ?: chatRowsByGroup[rowKey])?.let { row ->
        val updated = locallyProjectedChatRow(rowKey, row, record, clearedName)
        optimisticChatListPreviewByGroup[rowKey]?.let { state ->
            state.baselineRow = updated
            materializeOptimisticChatListPreview(rowKey, state)
        } ?: run {
            chatRowsByGroup[rowKey] = updated
        }
    }
    foldGroup(record, invalidateMembers = members == null, fromSubscription = false)
}

/**
 * Patches [row] from a locally committed [record].
 *
 * A rename, including clearing the name, also moves the prepared row title and the selected
 * presentation the list displays, searches and sorts by, and is retained against a stale
 * pre-rename snapshot (#2696).
 */
internal fun ChatsController.locallyProjectedChatRow(
    rowKey: String,
    row: ChatListRowFfi,
    record: AppGroupRecordFfi,
    clearedName: String?,
): ChatListRowFfi {
    // Only an explicit clear empties the row; any other blank record simply carries no name yet.
    // The caller states the intent because the subscription may already have folded the cleared
    // record, so the prior name cannot be read back from live state.
    val projectedName = if (clearedName != null) "" else record.name.ifBlank { row.groupName }
    val renamed = projectedName != row.groupName
    // A row that already shows the clear still fences the cleared name against the group record,
    // which arrives on its own stream and is what a blank row falls back to.
    if (clearedName != null && clearedName != row.groupName) {
        localGroupNames.record(record.groupIdHex, clearedName, projectedName)
    }
    if (renamed) {
        localGroupNames.record(record.groupIdHex, row.groupName, projectedName)
        selectedPresentationsByGroup =
            selectedPresentationsByGroup[rowKey]?.let { presentation ->
                selectedPresentationsByGroup + (rowKey to presentation.withLocalGroupTitle(projectedName))
            } ?: selectedPresentationsByGroup
    }
    return row.copy(
        archived = record.archived,
        pendingConfirmation = record.pendingConfirmation,
        groupName = projectedName,
        title = if (renamed) projectedName else row.title,
    )
}

internal fun ChatsController.applyProfileGroupDetails(
    account: String,
    details: GroupDetailsFfi,
) {
    if (accountRef == null) return
    val applied = applyAuthoritativeGroupDetails(details)
    val groupIdHex = applied.group.groupIdHex
    if (groupRecordsById[groupIdHex] == null && !chatRowsByGroup.containsKey(chatRowKey(groupIdHex))) return
    cacheAppliedGroupMembers(appState, account, groupIdHex, applied.members)
    applyLocalGroupProjection(applied.group, applied.members)
}

// Project every current chat row into a ChatListItem. Reads chatRows (kept
// current by the bind loop even when recompute is deferred behind an open
// conversation, #6), so on-demand callers — shared groups, DM lookup,
// by-id resolution for navigation — see freshly created/updated groups
// instead of the stale `items` snapshot.
// Last-known roster presentation for a row whose live caches are cold: a
// fresh bind (cold start, account switch, controller recreation) starts
// with empty per-bind maps while the account-scoped snapshot cache may
// still hold the roster from the previous session's group-details or
// conversation reads. Falling back keeps a previously resolved title and
// avatar on screen instead of the Unknown placeholder until hydration
// catches up. No new state: this reads an existing cache.
internal fun ChatsController.lastKnownPresentation(
    groupIdHex: String,
    activeAccountIdHex: String?,
): ChatListMemberPresentation? {
    // An authoritative roster wins downstream — skip the fallback lookup
    // entirely so hydrated rows pay no per-recompute cache reads.
    if (memberCacheByGroup.containsKey(groupIdHex)) return null
    return presentationMembersByGroup[groupIdHex]
        ?: appState
            .cachedGroupMemberSnapshot(accountRef, groupIdHex)
            ?.members
            ?.let { chatListMemberPresentation(it, activeAccountIdHex) }
}

/** Resolves the bound account identity, falling back to the active account before binding completes. */
internal fun ChatsController.projectionAccountIdHex(): String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex

/** Projects the existing backing rows without retaining another presentation snapshot. */
internal fun ChatsController.currentProjectedItems(activeAccountIdHex: String? = projectionAccountIdHex()): List<ChatListItem> {
    val projector = rowProjector(activeAccountIdHex)
    return chatRows.map(projector::retained)
}

/** Creates a synchronous projector over the current controller-owned inputs for the selected account. */
internal fun ChatsController.rowProjector(activeAccountIdHex: String?): ChatListRowProjector =
    ChatListRowProjector(
        activeAccountIdHex = activeAccountIdHex,
        groups = groupRecordsById,
        members = memberCacheByGroup,
        presentationMembers = { lastKnownPresentation(it, activeAccountIdHex) },
        tokens = previewTokensByText,
        mediaFallbacks = mediaPreviewFallbackByMessageId,
        removed = removedGroupIds,
        activitySequences = activitySequenceByGroup,
        optimisticPreviews = optimisticChatListPreviewByGroup,
        archiveActions = archiveActions,
        localGroupNames = localGroupNames,
        presentations = selectedPresentationsByGroup,
        avatars = selectedAvatarAssetsByGroup,
        previews = selectedPreviewsByGroup,
        actions = selectedActionsByGroup,
    )

/** Projects an off-window MDK row without materializing it into the retained window. */
internal fun ChatsController.projectPresentedRow(
    presented: PresentedChatRowFfi,
    activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
): ChatListItem = rowProjector(activeAccountIdHex).presented(presented)

/** Groups [targetAccountIdHex] can be added to, including those [accountWide] holds beyond the window. */
internal fun ChatsController.profileAddableGroupsState(
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
    accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
): ProfileGroupPickerState =
    profileAddableGroupsState(
        items = mergeForwardTargets(currentProjectedItems(activeAccountIdHex), accountWide.items()),
        targetAccountIdHex = targetAccountIdHex,
        activeAccountIdHex = activeAccountIdHex,
        failedGroupIds = failedMemberFetches + accountWide.failedGroupIds,
    ).failedWhenAccountWideReadFailed(accountWide)

/** Groups [targetAccountIdHex] can be made an admin of, including those [accountWide] holds beyond the window. */
internal fun ChatsController.profilePromotableGroupsState(
    targetAccountIdHex: String,
    activeAccountIdHex: String?,
    accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
): ProfileGroupPickerState =
    profilePromotableGroupsState(
        items = mergeForwardTargets(currentProjectedItems(activeAccountIdHex), accountWide.items()),
        targetAccountIdHex = targetAccountIdHex,
        activeAccountIdHex = activeAccountIdHex,
        failedGroupIds = failedMemberFetches + accountWide.failedGroupIds,
    ).failedWhenAccountWideReadFailed(accountWide)

/**
 * Every current implicit DM with [reference], best first per
 * [directChatPreferenceOrder]. Ordering matters because duplicates are
 * reachable — both sides can each have created a DM — and the tap that opens
 * one must land on the same conversation every time.
 */
internal fun ChatsController.existingDirectChatCandidates(reference: String): List<ChatListItem> {
    val normalizedReference = reference.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
    return chatRows
        .asSequence()
        .filter { !it.pendingConfirmation }
        .mapNotNull { row ->
            val members = memberCacheByGroup[row.groupIdHex] ?: return@mapNotNull null
            val match =
                GroupProjector.isImplicitDmWith(
                    members = members,
                    name = row.groupName,
                    activeAccountIdHex = activeAccountIdHex,
                    targetIdHex = normalizedReference,
                    equivalentTarget = { other -> appState.npub(other).equals(normalizedReference, ignoreCase = true) },
                )
            if (match) projectChatRow(row) else null
        }.sortedWith(directChatPreferenceOrder)
        .toList()
}

/** Read-request dedupe reads the native flag without constructing a display item. */
internal fun ChatsController.hasManualUnreadReminder(groupIdHex: String): Boolean? {
    val row =
        chatRowsByGroup[chatRowKey(groupIdHex)]
            ?: chatRows.firstOrNull { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
    return row?.manuallyMarkedUnread
}

/** Lowercase IDs of every chat the bounded windows retain, as a read-only view of the raw rows. */
internal fun ChatsController.retainedGroupIds(): Set<String> = chatRowsByGroup.keys

/**
 * Warms the pictures a row's next notification may show, in this controller's own account's workers: a
 * replaced controller recomputing after an account switch never decodes its rows in the new account's worker.
 */
internal fun ChatsController.preWarmNotificationAvatars(item: ChatListItem) {
    val conversationAvatar =
        ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
            ?: ProfileSanitizer.protocolImageUrl(item.projection?.avatarUrl)
    val profiles =
        dev.ipf.whitenoise.android.media.MediaTrustDomain
            .publicProfiles(accountRef)
    if (item.selectedAvatarAsset == null) {
        // A group's own picture here is its URL, whose host chooses the bytes, so it decodes single-use, and a
        // direct chat's peer picture is a published one.
        val domain =
            if (GroupProjector.ownsGroupPicture(item)) {
                dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
            } else {
                profiles
            }
        AvatarImageLoader.preWarm(conversationAvatar, domain)
        item.avatarAccount
            ?.let { appState.preWarmProfileAvatar(it, profiles) }
    }
    item.latest?.sender?.let { appState.preWarmProfileAvatar(it, profiles) }
}

internal fun ChatsController.firstFrameAvatarSeed(item: ChatListItem): ChatListAvatarSeed? {
    val owner = accountRef
    return firstFrameGroupAvatarSeed(item, owner, appState::avatarUrl)
}
