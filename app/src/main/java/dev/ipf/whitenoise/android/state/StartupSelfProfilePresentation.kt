package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.UserProfileMetadataFfi

/** Rejects a replaced runtime or an account removed while activation was suspended. */
internal fun WhiteNoiseAppState.canPublishAccountActivation(
    label: String,
    activationRuntimeGeneration: Int,
): Boolean =
    runtimeGeneration == activationRuntimeGeneration &&
        accounts.any { it.label == label && !it.signedOut }

/** Reads local metadata without mutation; a present profile is authoritative even when fields are cleared. */
internal suspend fun readLocalAccountProfileSeed(
    id: String,
    readProfile: suspend (String) -> UserProfileMetadataFfi?,
    readDisplayName: suspend (String) -> String?,
): AccountSwitchProfileSeed {
    val profile = runCatchingCancellable { readProfile(id) }.getOrNull()
    // A present profile already owns the name, so a second database read cannot improve it.
    val rawDisplayName = if (profile == null) runCatchingCancellable { readDisplayName(id) }.getOrNull() else null
    return accountSwitchProfileSeed(id, profile, rawDisplayName)
}

/** Carries a local read and its starting revision until the account activation fence has passed. */
internal data class StartupSelfProfilePresentation(
    val revision: ProfileAccountRevision?,
    val seeds: List<AccountSwitchProfileSeed>,
)

/** Captures the revision before suspension so a later authoritative edit can supersede this read. */
internal suspend fun WhiteNoiseAppState.preloadStartupSelfProfile(
    policy: AccountSwitchPreloadPolicy,
    accountIdHex: String?,
    activationWanted: Boolean,
    loadProfile: suspend (String) -> AccountSwitchProfileSeed,
): StartupSelfProfilePresentation {
    val revision = accountIdHex?.let(::profileAccountRevisionForCompose)
    val seeds = if (activationWanted) loadStartupSelfProfileSeeds(policy, accountIdHex, loadProfile) else emptyList()
    return StartupSelfProfilePresentation(revision, seeds)
}

/** Selects the latest authoritative value before an account switch clears the presentation cache. */
internal fun WhiteNoiseAppState.startupSeeds(read: StartupSelfProfilePresentation): List<AccountSwitchProfileSeed> =
    read.seeds.map { seed ->
        val newer =
            userProfileCached(seed.accountIdHex)
                ?.takeIf { profileAccountRevisionForCompose(seed.accountIdHex) != read.revision }
        newer?.let { accountSwitchProfileSeed(seed.accountIdHex, it, null) } ?: seed
    }

/**
 * Startup needs only its retained account's local identity, not every peer or
 * account-picker avatar. A missing read must not erase an already useful value;
 * a present profile with cleared fields is authoritative and must still apply.
 */
internal suspend fun loadStartupSelfProfileSeeds(
    preloadPolicy: AccountSwitchPreloadPolicy,
    activeAccountIdHex: String?,
    loadProfile: suspend (String) -> AccountSwitchProfileSeed,
): List<AccountSwitchProfileSeed> {
    val id = activeAccountIdHex?.takeIf(String::isNotBlank)
    if (preloadPolicy != AccountSwitchPreloadPolicy.STARTUP_RESTORATION || id == null) return emptyList()
    val seed = loadProfile(id)
    return listOfNotNull(seed.takeIf { it.profile != null })
}
