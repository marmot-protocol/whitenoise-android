package dev.ipf.whitenoise.android.core.nostr

import dev.ipf.marmotkit.verifyPublicNostrEventJson

/** Stateless MDK verification; no account or database initialization is needed. */
internal object NostrEventVerifier {
    fun verifies(event: NostrEvent): Boolean = verifyPublicNostrEventJson(event.toJson())
}
