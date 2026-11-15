package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import java.util.Locale

internal data class ProfilePresentationRevision(
    val profiles: Int,
    val contactNicknames: Int,
)

internal data class ProfileAccountRevision(
    val epoch: Int,
    val account: Int,
)

internal fun contactNicknameAccountRefForAccess(
    accountRef: String?,
    accounts: List<AccountSummaryFfi>,
    contactPubkeyHex: String,
): String? {
    val account = accountRef ?: return null
    if (isLocalContactAccount(accounts, contactPubkeyHex)) return null
    return account
}

internal fun isLocalContactAccount(
    accounts: List<AccountSummaryFfi>,
    accountIdHex: String,
): Boolean = accounts.any { it.accountIdHex.equals(accountIdHex, ignoreCase = true) }

internal fun networkDisplayNameFallback(
    accountLabel: String?,
    accountIdHex: String,
    shortNpub: (String) -> String,
): String {
    val label = accountLabel?.takeIf { it.isNotBlank() }
    if (label != null && !IdentityFormatter.isNostrIdentityFallback(label)) {
        return label
    }
    return shortNpub(accountIdHex)
}

/** The cached or freshly encoded npub for [accountIdHex], or empty rather than a non-npub or the raw hex. */
internal fun npubPresentation(
    accountIdHex: String,
    cachedNpub: String?,
    encode: (String) -> String?,
): String {
    val candidate = cachedNpub ?: runCatching { encode(accountIdHex) }.getOrNull()
    return candidate
        ?.takeIf { it.startsWith("npub1") && !it.equals(accountIdHex, ignoreCase = true) }
        .orEmpty()
}

/**
 * Fail-closed npub for visible/copy profile and message surfaces. Keeps an
 * already-canonical [reference] when it is not the raw account hex, lowercasing an
 * all-uppercase key; otherwise derives through [npubForDisplay] from [resolvedAccountIdHex].
 */
internal fun presentationNpubFromReference(
    reference: String,
    resolvedAccountIdHex: String?,
    npubForDisplay: (String) -> String,
): String {
    if (
        reference.startsWith("npub1", ignoreCase = true) &&
        resolvedAccountIdHex?.equals(reference, ignoreCase = true) != true
    ) {
        // Bech32 allows one case; an all-uppercase key is the same key, so show and copy its canonical lowercase.
        return if (reference == reference.uppercase(Locale.ROOT)) reference.lowercase(Locale.ROOT) else reference
    }
    return resolvedAccountIdHex?.let(npubForDisplay).orEmpty()
}

internal fun operationalNpub(
    accountIdHex: String,
    cachedNpub: String?,
    encode: (String) -> String?,
): String = cachedNpub ?: runCatching { encode(accountIdHex) }.getOrNull() ?: accountIdHex

/** Owns bounded Compose invalidations for individual profile presentations. */
internal class ProfileAccountRevisions(
    private val maxEntries: Int,
) {
    // staleness-exempt: observable profile version, not an async publication guard.
    private var profileAccountRevisionEpoch by mutableIntStateOf(0)

    // staleness-exempt: bounded per-account ordering value, not an async publication guard.
    private var profileAccountRevisionSequence = 0
    private val profileAccountRevisions = mutableStateMapOf<String, Int>()
    private val profileAccountRevisionOrder = linkedSetOf<String>()

    val epoch: Int get() = profileAccountRevisionEpoch

    fun revision(accountIdHex: String): ProfileAccountRevision {
        val normalized = accountIdHex.trim().lowercase(Locale.ROOT)
        return ProfileAccountRevision(
            epoch = profileAccountRevisionEpoch,
            account = profileAccountRevisions[normalized] ?: 0,
        )
    }

    fun bump(accountIdHex: String) {
        val normalized = accountIdHex.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return
        profileAccountRevisionSequence += 1
        profileAccountRevisions[normalized] = profileAccountRevisionSequence
        profileAccountRevisionOrder.remove(normalized)
        profileAccountRevisionOrder.add(normalized)
        if (profileAccountRevisionOrder.size > maxEntries) {
            val evicted = profileAccountRevisionOrder.first()
            profileAccountRevisionOrder.remove(evicted)
            profileAccountRevisions.remove(evicted)
        }
    }

    fun reset() {
        profileAccountRevisionEpoch += 1
        profileAccountRevisionSequence = 0
        profileAccountRevisions.clear()
        profileAccountRevisionOrder.clear()
    }
}
