package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.whitenoise.android.R

/**
 * The profile [event] says was unlinked from its account by another of the account's devices, or null for any
 * other event. This is the account-wide removal of this device, which MDK reports once after it has signed the
 * account out here. Being removed from a single chat is a different thing and is not reported this way.
 */
internal fun deviceUnlinkedAccount(event: MarmotEventFfi) = (event as? MarmotEventFfi.DeviceUnlinked)?.accountLabel

/**
 * Tells the user this device was unlinked from [accountRef] and brings the app in line with MDK, which has already
 * signed the account out. The account list is re-read so every per-account flow keyed to a signed-in profile lets
 * go of it, whether or not it was the active one. If it was the active profile and another is still signed in, the
 * app moves to that one, otherwise the unlinked-account prompt covers its chats. Nothing is wiped here: removing the
 * account from this device is the user's explicit choice.
 */
internal suspend fun WhiteNoiseAppState.presentDeviceUnlinked(accountRef: String) {
    val name = accounts.firstOrNull { it.label == accountRef }?.accountIdHex?.let(::unlinkedAccountName)
    runCatchingCancellable { refreshAccounts() }
    val active = activeAccountRef == accountRef
    val next = nextSignedInAccount(accounts.map { it.label to it.signedOut }, accountRef)
    // With nowhere to move to, the unlinked-account prompt says this itself, so a second notice would only repeat it.
    if (!active || next != null) {
        presentText(
            title = AppText.Resource(R.string.device_unlinked_title),
            detail = AppText.Resource(R.string.device_unlinked_detail, listOf(name ?: accountRef)),
        )
    }
    if (active && next != null) setActiveAccount(next)
}

/**
 * The profile to move to after [unlinked] stopped: the first one still signed in, or null when there is none and
 * the unlinked-account prompt takes over.
 */
internal fun nextSignedInAccount(
    accounts: List<Pair<String, Boolean>>,
    unlinked: String,
): String? = accounts.firstOrNull { (label, signedOut) -> label != unlinked && !signedOut }?.first

/** The name the unlinked notices use: the profile's display name, or its short public key when it has none. */
internal fun WhiteNoiseAppState.unlinkedAccountName(accountIdHex: String): String {
    val name = accountDisplayNameCached(accountIdHex)
    return if (name.equals(accountIdHex, ignoreCase = true)) shortNpub(accountIdHex) else name
}
