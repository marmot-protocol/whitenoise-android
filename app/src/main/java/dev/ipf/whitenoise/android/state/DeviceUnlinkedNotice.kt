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
 * app moves to that one. Nothing is wiped here: clearing what is left is the user's explicit Sign out and wipe.
 */
internal suspend fun WhiteNoiseAppState.presentDeviceUnlinked(accountRef: String) {
    val name = accounts.firstOrNull { it.label == accountRef }?.accountIdHex?.let(::accountDisplayNameCached)
    runCatchingCancellable { refreshAccounts() }
    presentText(
        title = AppText.Resource(R.string.device_unlinked_title),
        detail = AppText.Resource(R.string.device_unlinked_detail, listOf(name ?: accountRef)),
    )
    if (activeAccountRef == accountRef) {
        nextSignedInAccount(accounts.map { it.label to it.signedOut }, accountRef)?.let { setActiveAccount(it) }
    }
}

/**
 * The profile to move to after [unlinked] stopped: the first one still signed in, or null when there is none and
 * the unlinked profile stays in view as a signed-out one.
 */
internal fun nextSignedInAccount(
    accounts: List<Pair<String, Boolean>>,
    unlinked: String,
): String? = accounts.firstOrNull { (label, signedOut) -> label != unlinked && !signedOut }?.first
