package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileSanitizer

/**
 * A sanitized label only when it reads as a name. An npub or a raw account key is an identity, not a
 * human label, so it is refused here and the caller falls through to the abbreviated identity it chooses
 * deliberately. Without this a sender with no published profile is announced by their hexadecimal key,
 * which is what a device report on MarmotKit 0.10.0 showed.
 */
internal fun humanNotificationLabel(raw: String?): String? {
    val sanitized = ProfileSanitizer.displayName(raw)
    return sanitized?.takeUnless(IdentityFormatter::isNostrIdentityFallback)
}
