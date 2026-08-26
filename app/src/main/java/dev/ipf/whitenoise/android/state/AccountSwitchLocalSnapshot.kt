package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi

internal data class AccountSwitchPresentationSeeds(
    val activeAccountIdHex: String?,
    val memberIds: List<AppGroupMemberIdsFfi>,
    val profiles: List<AccountSwitchProfileSeed>,
)

enum class AccountSwitchPreloadPolicy {
    FULL_LOCAL_SNAPSHOT,
    TARGET_CONVERSATION_FIRST,
    STARTUP_RESTORATION,
}

internal fun shouldLoadAccountSwitchLocalSnapshot(
    switchingAccounts: Boolean,
    activationStillWanted: Boolean,
    preloadPolicy: AccountSwitchPreloadPolicy,
): Boolean =
    activationStillWanted &&
        (
            preloadPolicy == AccountSwitchPreloadPolicy.STARTUP_RESTORATION ||
                (switchingAccounts && preloadPolicy == AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT)
        )

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

/** Main-confined latest-wins owner for the one-shot account-switch handoff. */
internal class AccountSwitchLocalSnapshotHandoff {
    private var generation = 0L
    private var pending: AccountSwitchLocalSnapshot? = null

    fun beginRequest(): Long {
        generation += 1L
        pending = null
        return generation
    }

    fun isCurrent(requestGeneration: Long): Boolean = generation == requestGeneration

    fun publish(
        requestGeneration: Long,
        snapshot: AccountSwitchLocalSnapshot?,
    ): Boolean {
        if (!isCurrent(requestGeneration)) return false
        pending = snapshot
        return true
    }

    fun consume(accountRef: String?): AccountSwitchLocalSnapshot? {
        val snapshot = pending
        pending = null
        return snapshot?.takeIf { accountRef != null && it.accountRef == accountRef }
    }
}
