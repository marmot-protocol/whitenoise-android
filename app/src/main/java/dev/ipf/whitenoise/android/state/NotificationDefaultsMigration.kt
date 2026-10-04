package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface

/**
 * Snapshot all existing local conversations before runtime start can admit new groups. The native
 * account-wide read includes archived rows; it is independent of the active account and UI window.
 * Read failures abort the barrier without committing any marker or partly changing defaults.
 */
internal suspend fun MarmotInterface.preserveExistingNotificationModes(preferences: ChatMutePreferences) {
    if (!preferences.needsDefaultsMigration) return
    val existing =
        listAccounts().associate { account ->
            account.label to chatList(account.label, true).map { it.groupIdHex }
        }
    preferences.preserveExistingModes(existing)
}
