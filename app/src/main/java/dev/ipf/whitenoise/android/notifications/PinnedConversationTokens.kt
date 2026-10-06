package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.state.AndroidKeystoreSecretKeyProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/** Platform routing capability; it carries no message content and is never a protocol identifier. */
class PinnedConversationCapability internal constructor(
    internal val accountRef: String,
    internal val groupIdHex: String,
    internal val accountToken: String,
    internal val groupToken: String,
) {
    /** A new account/group incarnation gets a different platform ID, so stale callbacks cannot disable a new pin. */
    internal val shortcutId: String
        get() =
            checkNotNull(conversationShortcutId(accountRef, groupIdHex)) +
                "-pin-" + sha256Hex("$accountToken\u0000$groupToken").take(24)

    /** Do not expose bearer capabilities through incidental logging or assertion diagnostics. */
    override fun toString(): String = "PinnedConversationCapability(redacted)"
}

/**
 * Revocable Android launcher credentials, separate from notification tokens' bounded recency cache.
 * Reusable tokens are sealed by the Android Keystore; separate one-way verifiers are the routing authority.
 * Hashed keys contain no shortcut-to-group mapping; the launcher owns each pin and its destination intent.
 * Credentials do not claim a pin exists: launcher inventory is authoritative after approval, denial or restart.
 */
internal class PinnedConversationTokens(
    private val secureStore: KeystoreSecureStore,
    private val verifiers: SharedPreferences,
    private val legacyPreferences: SharedPreferences,
) {
    /** Must run off the main thread; commit both credentials before exposing a launcher request. */
    fun issue(
        accountRef: String,
        groupIdHex: String,
        requestGeneration: Long = captureRequest(),
    ): PinnedConversationCapability? =
        synchronized(lock) {
            if (!revocations.isCurrent(requestGeneration)) return@synchronized null
            val account = accountRef.takeIf { it.isNotBlank() && it == it.trim() } ?: return@synchronized null
            val group = groupIdHex.lowercase(Locale.ROOT).takeIf(groupPattern::matches) ?: return@synchronized null
            val accountKey = accountKey(account)
            val groupKey = groupKey(account, group)
            if (removals.containsKey(accountKey) || removals.containsKey(groupKey)) return@synchronized null
            clearLegacyCredentials()
            val stored = secureStore.readAll()
            val accountToken = reusableToken(stored[accountKey], verifiers.getString(accountKey, null))
            val groupToken = reusableToken(stored[groupKey], verifiers.getString(groupKey, null))
            check(secureStore.replaceAllDurably(stored + mapOf(accountKey to accountToken, groupKey to groupToken))) {
                "Unable to encrypt launcher credentials"
            }
            check(
                verifiers
                    .edit()
                    .putString(accountKey, sha256Hex(accountToken))
                    .putString(groupKey, sha256Hex(groupToken))
                    .commit(),
            ) {
                "Unable to persist launcher credentials"
            }
            PinnedConversationCapability(account, group, accountToken, groupToken)
        }

    /** Validation never creates credentials; deletion stays revoked across process recreation and late callbacks. */
    fun isValid(capability: PinnedConversationCapability): Boolean =
        runCatching {
            // SharedPreferences gives one atomic snapshot; never wait behind a mutation's Keystore work.
            val current = verifiers.all
            val account = capability.accountRef
            val group = capability.groupIdHex
            account.isNotBlank() &&
                account == account.trim() &&
                groupPattern.matches(group) &&
                matches(current[accountKey(account)] as? String, capability.accountToken) &&
                matches(current[groupKey(account, group)] as? String, capability.groupToken)
        }.getOrDefault(false)

    /** Durably invalidates visible and still-pending pins for one removed conversation. */
    fun revokeGroup(
        accountRef: String,
        groupIdHex: String,
    ) = synchronized(lock) {
        revocations.advance()
        clearLegacyCredentials()
        val key = groupKey(accountRef, groupIdHex.lowercase(Locale.ROOT))
        check(verifiers.edit().remove(key).commit()) {
            "Unable to revoke launcher credentials"
        }
        removeEncryptedCredentials { it == key }
    }

    /** Prefix-safe account cleanup also revokes requests the launcher has not yet added to its inventory. */
    fun revokeAccount(accountRef: String) =
        synchronized(lock) {
            revocations.advance()
            clearLegacyCredentials()
            val accountKey = accountKey(accountRef)
            val groupPrefix = "group.${sha256Hex(accountRef)}."
            val editor = verifiers.edit().remove(accountKey)
            verifiers.all.keys
                .filter { it.startsWith(groupPrefix) }
                .forEach(editor::remove)
            check(editor.commit()) { "Unable to revoke launcher credentials" }
            removeEncryptedCredentials { it == accountKey || it.startsWith(groupPrefix) }
        }

    /** Preview credentials are invalid in the new verifier namespace and must never be imported or reauthorized. */
    private fun clearLegacyCredentials() {
        check(legacyPreferences.edit().clear().commit()) { "Unable to remove legacy launcher credentials" }
    }

    /** Verifier removal already revoked authority; a locked/corrupt Keystore must not block completed removal. */
    private fun removeEncryptedCredentials(removed: (String) -> Boolean) {
        runCatching {
            val stored = secureStore.readAll()
            val retained = stored.filterKeys { !removed(it) }
            if (retained != stored) secureStore.replaceAllDurably(retained)
        }
    }

    /**
     * Revoke durably before a native removal can commit, and block new credentials while it runs.
     * A failed removal conservatively invalidates old pins; it never restores their authority.
     */
    suspend fun <T> withRemovalRevoked(
        accountRef: String,
        groupIdHex: String? = null,
        remove: suspend () -> T,
    ): T {
        val key = groupIdHex?.let { groupKey(accountRef, it.lowercase(Locale.ROOT)) } ?: accountKey(accountRef)
        var started = false
        try {
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    revokeTarget(accountRef, groupIdHex)
                    removals[key] = (removals[key] ?: 0) + 1
                    started = true
                }
            }
            return remove()
        } finally {
            if (started) {
                withContext(NonCancellable + Dispatchers.IO) {
                    synchronized(lock) {
                        // Entry revoked durably; the fence prevented new authority throughout native work.
                        // Invalidate queued requests without adding a fallible write after native completion.
                        revocations.advance()
                        val remaining = checkNotNull(removals[key]) - 1
                        if (remaining == 0) removals.remove(key) else removals[key] = remaining
                    }
                }
            }
        }
    }

    /** Caller holds the shared credential lock, keeping issuance and account/group revocation serialized. */
    private fun revokeTarget(
        accountRef: String,
        groupIdHex: String?,
    ) {
        if (groupIdHex == null) revokeAccount(accountRef) else revokeGroup(accountRef, groupIdHex)
    }

    companion object {
        private val lock = Any()
        private val revocations = StalenessGuard()
        private val removals = mutableMapOf<String, Int>()
        private val random = SecureRandom()
        private val keyProvider = AndroidKeystoreSecretKeyProvider("whitenoise.pinned_conversation_tokens.aes_gcm.v1")
        private val groupPattern = Regex("[0-9a-f]{64}")
        private const val TOKEN_BYTES = 32
        private const val TOKEN_LENGTH = 43
        private const val SHA256_HEX_LENGTH = 64

        /** Capture before suspending so a queued credential write cannot outlive a removal or sign-out. */
        fun captureRequest(): Long = revocations.capture()

        /** Rejects queued Direct Share writes across account/group cleanup, including requests born during removal. */
        fun isPublicationCurrent(generation: Long): Boolean =
            synchronized(lock) { revocations.isCurrent(generation) && removals.isEmpty() }

        /** App-private, backup-excluded launcher authority; callers must never export these preferences. */
        fun create(context: Context): PinnedConversationTokens =
            PinnedConversationTokens(
                KeystoreSecureStore(context, "pinned_conversation_tokens.keystore", keyProvider),
                context.applicationContext.getSharedPreferences(
                    "pinned_conversation_token_verifiers",
                    Context.MODE_PRIVATE,
                ),
                context.applicationContext.getSharedPreferences("pinned_conversation_tokens", Context.MODE_PRIVATE),
            )

        /** Hashing avoids persisting raw account references or group IDs as a second routing table. */
        private fun accountKey(account: String): String = "account.${sha256Hex(account)}"

        /** Fixed-length account scope makes cleanup independent of user-controlled account-name prefixes. */
        private fun groupKey(
            account: String,
            group: String,
        ): String = "group.${sha256Hex(account)}.${sha256Hex(group)}"

        /** Random bearer material is retained only in Keystore ciphertext and the approved launcher intent. */
        private fun newToken(): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

        /**
         * A missing encrypted copy cannot silently rotate an existing pin; only absent authority permits a new
         * token.
         */
        private fun reusableToken(
            stored: String?,
            verifier: String?,
        ): String {
            if (verifier == null) return newToken()
            check(stored != null && matches(verifier, stored)) {
                "Active launcher credentials cannot be decrypted consistently"
            }
            return stored
        }

        /** Reject corrupt persisted values before constant-time comparison. */
        private fun validToken(value: String): Boolean =
            value.length == TOKEN_LENGTH &&
                value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

        /**
         * High-entropy token digests validate synchronously without decrypting reusable credentials on the UI
         * thread.
         */
        private fun matches(
            expected: String?,
            candidate: String,
        ): Boolean =
            expected != null &&
                expected.length == SHA256_HEX_LENGTH &&
                expected.all { it in '0'..'9' || it in 'a'..'f' } &&
                validToken(candidate) &&
                MessageDigest.isEqual(
                    expected.toByteArray(Charsets.UTF_8),
                    sha256Hex(candidate).toByteArray(Charsets.UTF_8),
                )
    }
}
