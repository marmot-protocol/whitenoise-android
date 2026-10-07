package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader

/** The viewer's private display handle for [accountIdHex], or null when only the public identity applies. */
internal fun WhiteNoiseAppState.privateContactAvatarSource(
    accountIdHex: String,
    accountRef: String? = activeAccountRef,
): String? = contactAvatarSource(accountIdHex, accountRef)?.takeIf(PrivateContactAvatarLoader::isPrivate)
