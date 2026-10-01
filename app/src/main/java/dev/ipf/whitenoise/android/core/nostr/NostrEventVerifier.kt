package dev.ipf.whitenoise.android.core.nostr

import dev.ipf.marmotkit.verifyPublicNostrEventJson

/** Stateless MDK verification; no account or database initialization is needed. */
internal object NostrEventVerifier {
    fun verifies(event: NostrEvent): Boolean =
        try {
            verifyPublicNostrEventJson(event.toJson())
        } catch (_: Exception) {
            // Reject malformed events and binding exceptions. Linkage/runtime
            // Errors still surface rather than hiding a missing native library.
            false
        }
}
