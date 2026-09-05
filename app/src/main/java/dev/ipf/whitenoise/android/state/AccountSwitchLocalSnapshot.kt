package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import java.util.Locale

internal data class AccountSwitchPresentationSeeds(
    val activeAccountIdHex: String?,
    val memberIds: List<AppGroupMemberIdsFfi>,
    val profiles: List<AccountSwitchProfileSeed>,
)

/**
 * Group ids whose first chat-list presentation cannot be reconstructed from
 * the row + group snapshots alone. Named groups already carry their title and
 * avatar identity in those two authoritative projections; awaiting their
 * rosters before account publication only makes the switch cost scale with
 * the entire account. Unnamed/direct rows still need member ids for their
 * peer title/avatar or "Group of N" presentation, so only those remain on the
 * first-frame critical path.
 */
internal fun accountSwitchFirstFrameMemberGroupIds(rows: Iterable<ChatListRowFfi>): List<String> =
    rows
        .asSequence()
        .filter { row ->
            ProfileSanitizer.displayName(row.groupName) == null ||
                row.conversationKind == ChatConversationKindFfi.DIRECT
        }.map(ChatListRowFfi::groupIdHex)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .toList()

internal fun accountSwitchMemberStage(
    rows: List<ChatListRowFfi>,
    memberIds: List<AppGroupMemberIdsFfi>,
): String {
    val requiredGroupIds = accountSwitchFirstFrameMemberGroupIds(rows)
    val projectedGroupIds = memberIds.mapTo(mutableSetOf()) { it.groupIdHex.lowercase(Locale.ROOT) }
    return if (requiredGroupIds.all { it.lowercase(Locale.ROOT) in projectedGroupIds }) {
        "member-derived-local-ready"
    } else {
        "member-derived-local-deferred"
    }
}

internal fun accountSwitchDirectPeerProfileIds(
    rows: List<ChatListRowFfi>,
    memberIds: List<AppGroupMemberIdsFfi>,
    activeAccountIdHex: String?,
): List<String> {
    val rowsByGroup = rows.associateBy { it.groupIdHex.lowercase(Locale.ROOT) }
    return initialDirectPeerProfileIds(memberIds, activeAccountIdHex) { groupIdHex, memberCount ->
        rowsByGroup[groupIdHex.lowercase(Locale.ROOT)]?.let { row ->
            GroupProjector.isDm(row.conversationKind, memberCount, row.groupName)
        } == true
    }
}

internal data class AccountSwitchIdentityStateCounts(
    val namedGroupTitleReady: Int,
    val namedGroupTitleMissing: Int,
    val directPeerPresentationReady: Int,
    val directPeerPresentationMissing: Int,
    val topBarProfileReady: Int,
    val topBarProfileMissing: Int,
    val memberDerivedPresentationReady: Int,
    val memberDerivedPresentationMissing: Int,
    val avatarIdentityKeyReady: Int,
    val avatarIdentityKeyMissing: Int,
) {
    /** Counts and fixed labels only: safe for debug logs on private accounts. */
    fun privacySafeTrace(): String =
        "named-title=$namedGroupTitleReady/$namedGroupTitleMissing " +
            "direct-peer=$directPeerPresentationReady/$directPeerPresentationMissing " +
            "top-bar=$topBarProfileReady/$topBarProfileMissing " +
            "member-derived=$memberDerivedPresentationReady/$memberDerivedPresentationMissing " +
            "avatar-key=$avatarIdentityKeyReady/$avatarIdentityKeyMissing"
}

private fun usefulAccountSwitchProfile(
    accountIdHex: String?,
    profilesById: Map<String, AccountSwitchProfileSeed>,
): AccountSwitchProfileSeed? =
    accountIdHex
        ?.let { profilesById[it.lowercase()] }
        ?.takeIf { it.displayName != null || it.avatarUrl != null }

private fun accountSwitchPeerId(
    row: ChatListRowFfi,
    membersByGroup: Map<String, AppGroupMemberIdsFfi>,
    activeAccountIdHex: String?,
): String? {
    val members =
        membersByGroup[row.groupIdHex.lowercase()]
            ?.memberIdsHex
            ?.filter(String::isNotBlank)
            ?.distinctBy(String::lowercase)
            .orEmpty()
    if (!GroupProjector.isDm(row.conversationKind, members.size, row.groupName)) return null
    return members.singleOrNull { member ->
        activeAccountIdHex != null && !member.equals(activeAccountIdHex, ignoreCase = true)
    }
}

private fun accountSwitchRowHasAvatarIdentity(
    row: ChatListRowFfi,
    group: AppGroupRecordFfi?,
    peerProfile: AccountSwitchProfileSeed?,
): Boolean =
    ProfileSanitizer.protocolImageUrl(row.avatarUrl) != null ||
        row.avatar?.imageHashHex?.isNotBlank() == true ||
        ProfileSanitizer.protocolImageUrl(group?.avatarUrl) != null ||
        group?.imageHashHex?.isNotBlank() == true ||
        peerProfile?.avatarUrl != null

/** Classify the local-ready snapshot without retaining or logging identity values. */
internal fun accountSwitchIdentityStateCounts(
    rows: List<ChatListRowFfi>,
    groups: List<AppGroupRecordFfi> = emptyList(),
    memberIds: List<AppGroupMemberIdsFfi>,
    profiles: List<AccountSwitchProfileSeed>,
    activeAccountIdHex: String?,
    topBarProfileIds: List<String>,
): AccountSwitchIdentityStateCounts {
    val membersByGroup = memberIds.associateBy { it.groupIdHex.lowercase() }
    val groupsById = groups.associateBy { it.groupIdHex.lowercase() }
    val profilesById = profiles.associateBy { it.accountIdHex.lowercase() }
    val identityGroupIds = accountSwitchFirstFrameMemberGroupIds(rows).mapTo(mutableSetOf()) { it.lowercase() }

    fun usefulProfile(accountIdHex: String?) = usefulAccountSwitchProfile(accountIdHex, profilesById)

    fun peerId(row: ChatListRowFfi): String? = accountSwitchPeerId(row, membersByGroup, activeAccountIdHex)

    val groupRows = rows.filter { it.conversationKind != ChatConversationKindFfi.DIRECT }
    val namedReady =
        groupRows.count { row ->
            ProfileSanitizer.displayName(row.groupName) != null ||
                ProfileSanitizer.displayName(groupsById[row.groupIdHex.lowercase()]?.name) != null
        }
    val directRows =
        rows.filter { row ->
            peerId(row) != null || row.conversationKind == ChatConversationKindFfi.DIRECT
        }
    val directReady = directRows.count { row -> usefulProfile(peerId(row)) != null }
    val memberReadyGroupIds =
        rows.mapNotNullTo(mutableSetOf()) { row ->
            val key = row.groupIdHex.lowercase()
            val isReady =
                key in identityGroupIds &&
                    membersByGroup[key]?.memberIdsHex?.any(String::isNotBlank) == true &&
                    (row.conversationKind != ChatConversationKindFfi.DIRECT || peerId(row) != null)
            key.takeIf { isReady }
        }
    val topBarReady = topBarProfileIds.count { id -> usefulProfile(id) != null }
    val avatarReady =
        rows.count { row ->
            val group = groupsById[row.groupIdHex.lowercase()]
            accountSwitchRowHasAvatarIdentity(row, group, usefulProfile(peerId(row)))
        }

    return AccountSwitchIdentityStateCounts(
        namedGroupTitleReady = namedReady,
        namedGroupTitleMissing = groupRows.size - namedReady,
        directPeerPresentationReady = directReady,
        directPeerPresentationMissing = directRows.size - directReady,
        topBarProfileReady = topBarReady,
        topBarProfileMissing = topBarProfileIds.size - topBarReady,
        memberDerivedPresentationReady = memberReadyGroupIds.size,
        memberDerivedPresentationMissing = identityGroupIds.size - memberReadyGroupIds.size,
        avatarIdentityKeyReady = avatarReady,
        avatarIdentityKeyMissing = rows.size - avatarReady,
    )
}

internal fun accountSwitchIdentityStateCounts(
    snapshot: AccountSwitchLocalSnapshot,
    topBarProfileIds: List<String>,
): AccountSwitchIdentityStateCounts =
    accountSwitchIdentityStateCounts(
        rows = snapshot.rows,
        groups = snapshot.groups,
        memberIds = snapshot.memberIds,
        profiles = snapshot.profiles,
        activeAccountIdHex = snapshot.activeAccountIdHex,
        topBarProfileIds = topBarProfileIds,
    )

enum class AccountSwitchPreloadPolicy {
    FULL_LOCAL_SNAPSHOT,
    INTERACTIVE_LOCAL_ROWS,
    TARGET_CONVERSATION_FIRST,
    STARTUP_RESTORATION,
}

internal data class AccountSwitchPreloadPlan(
    val loadLocalRows: Boolean,
    val includePresentationSeeds: Boolean,
)

internal fun accountSwitchPreloadPlan(
    switchingAccounts: Boolean,
    activationStillWanted: Boolean,
    preloadPolicy: AccountSwitchPreloadPolicy,
): AccountSwitchPreloadPlan {
    val loadLocalRows =
        activationStillWanted &&
            (
                preloadPolicy == AccountSwitchPreloadPolicy.STARTUP_RESTORATION ||
                    (
                        switchingAccounts &&
                            (
                                preloadPolicy == AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT ||
                                    preloadPolicy == AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS
                            )
                    )
            )
    return AccountSwitchPreloadPlan(
        loadLocalRows = loadLocalRows,
        includePresentationSeeds =
            loadLocalRows && preloadPolicy == AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT,
    )
}

internal fun shouldLoadAccountSwitchLocalSnapshot(
    switchingAccounts: Boolean,
    activationStillWanted: Boolean,
    preloadPolicy: AccountSwitchPreloadPolicy,
): Boolean = accountSwitchPreloadPlan(switchingAccounts, activationStillWanted, preloadPolicy).loadLocalRows

/**
 * One-shot handoff of MDK's authoritative local projection across the
 * active-account composition boundary. This is deliberately not a retained
 * Android cache: [WhiteNoiseAppState] owns at most one pending value and the
 * target [ChatsController] consumes it during construction.
 */
internal data class AccountSwitchLocalSnapshot(
    val accountRef: String,
    val activeAccountIdHex: String?,
    val rows: List<ChatListRowFfi>,
    val groups: List<AppGroupRecordFfi>,
    val memberIds: List<AppGroupMemberIdsFfi>,
    internal val profiles: List<AccountSwitchProfileSeed>,
)

internal data class AccountSwitchProfileSeed(
    val accountIdHex: String,
    val profile: UserProfileMetadataFfi?,
    val displayName: String?,
    val avatarUrl: String?,
)

internal fun accountSwitchProfileSeed(
    accountIdHex: String,
    profile: UserProfileMetadataFfi?,
    rawDisplayName: String?,
): AccountSwitchProfileSeed =
    AccountSwitchProfileSeed(
        accountIdHex = accountIdHex,
        profile = profile,
        displayName =
            if (profile != null) {
                ProfileSanitizer.displayName(profile.displayName)
                    ?: ProfileSanitizer.displayName(profile.name)
            } else {
                ProfileSanitizer.displayName(rawDisplayName)
            },
        avatarUrl = ProfileSanitizer.protocolImageUrl(profile?.picture),
    )

/** Main-confined latest-wins owner for the one-shot account-switch handoff. */
internal class AccountSwitchLocalSnapshotHandoff {
    private val requests = StalenessGuard()
    private var pending: AccountSwitchLocalSnapshot? = null

    /** Starts a switch request and discards any snapshot from its predecessor. */
    fun beginRequest(): Long = requests.advance { pending = null }

    /** Reports whether [requestGeneration] still owns the pending handoff. */
    fun isCurrent(requestGeneration: Long): Boolean = requests.isCurrent(requestGeneration)

    /** Publishes [snapshot] only while its originating switch remains current. */
    fun publish(
        requestGeneration: Long,
        snapshot: AccountSwitchLocalSnapshot?,
    ): Boolean = requests.runIfCurrent(requestGeneration) { pending = snapshot }

    /** Consumes the one-shot handoff only for its target account and always clears the slot. */
    fun consume(accountRef: String?): AccountSwitchLocalSnapshot? {
        val snapshot = pending
        pending = null
        return snapshot?.takeIf { accountRef != null && it.accountRef == accountRef }
    }
}
