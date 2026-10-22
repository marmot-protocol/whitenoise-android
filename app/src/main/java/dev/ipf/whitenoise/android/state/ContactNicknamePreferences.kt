package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import java.util.Locale

/**
 * Private, local-only contact labels keyed by the viewing account and contact
 * pubkey. These are user-authored UI preferences, not cached protocol data: the
 * profile directory remains the source of truth when no nickname is set.
 */
internal object ContactNicknamePreferences {
    private const val KeyPrefix = "contact_nickname:"

    /** Rejects incomplete ownership and combines a length-prefixed account scope with a normalized contact key. */
    fun preferenceKey(
        accountRef: String?,
        contactPubkeyHex: String,
    ): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        val contact = normalizedContactPubkey(contactPubkeyHex) ?: return null
        return accountKeyPrefix(account) + contact
    }

    /** Reads the account-private nickname under the shared picture/details transaction lock. */
    fun readNickname(
        preferences: SharedPreferences,
        accountRef: String?,
        contactPubkeyHex: String,
    ): String? =
        synchronized(ContactPictureStore.lock) {
            val key = preferenceKey(accountRef, contactPubkeyHex) ?: return@synchronized null
            ProfileSanitizer.displayName(preferences.getString(key, null))
        }

    /** Stores only normalized changes and removes blank overrides so public defaults remain authoritative. */
    fun writeNickname(
        preferences: SharedPreferences,
        accountRef: String?,
        contactPubkeyHex: String,
        nickname: String?,
    ): Boolean =
        synchronized(ContactPictureStore.lock) {
            val key = preferenceKey(accountRef, contactPubkeyHex) ?: return@synchronized false
            val normalized = ProfileSanitizer.displayName(nickname)
            val current = readNickname(preferences, accountRef, contactPubkeyHex)
            if (current == normalized && (normalized != null || !preferences.contains(key))) return@synchronized false
            val edit = preferences.edit()
            if (normalized == null) {
                edit.remove(key)
            } else {
                edit.putString(key, normalized)
            }
            edit.apply()
            true
        }

    /** Commits deletion of the exact account prefix on disk; similar account labels retain their private records. */
    fun clearAllForAccount(
        preferences: SharedPreferences,
        accountRef: String?,
    ): Boolean =
        synchronized(ContactPictureStore.lock) {
            val account = normalizedAccountRef(accountRef) ?: return@synchronized false
            val prefix = accountKeyPrefix(account)
            val keys = preferences.all.keys.filter { it.startsWith(prefix) }
            if (keys.isEmpty()) return@synchronized false
            val edit = preferences.edit()
            keys.forEach { edit.remove(it) }
            // commit(), not apply(): a wipe must not leave contact data on disk if
            // the process dies before an async write lands. Callers hop to IO.
            edit.commit()
        }

    /** Immutable private values from one account; never a protocol or profile cache. */
    class Snapshot internal constructor(
        internal val accountRef: String,
        val nicknames: Map<String, String>,
    ) {
        override fun toString(): String = "NicknameSnapshot(size=${nicknames.size})"
    }

    /** Reads only canonical keys written by this store, under the editor/teardown lock. */
    fun snapshotForAccount(
        preferences: SharedPreferences,
        accountRef: String?,
    ): Snapshot? =
        synchronized(ContactPictureStore.lock) {
            val account = normalizedAccountRef(accountRef) ?: return@synchronized null
            val prefix = accountKeyPrefix(account)
            val values = linkedMapOf<String, String>()
            preferences.all.keys.forEach { key ->
                if (key.startsWith(prefix)) {
                    val contact = key.removePrefix(prefix)
                    if (normalizedContactPubkey(contact) == contact) {
                        ProfileSanitizer.displayName(preferences.getString(key, null))?.let { values[contact] = it }
                    }
                }
            }
            Snapshot(account, java.util.Collections.unmodifiableMap(values))
        }

    /** Compares exact values, including IO commits that precede Compose revision notifications. */
    fun isSnapshotCurrent(
        preferences: SharedPreferences,
        accountRef: String?,
        snapshot: Snapshot,
    ): Boolean =
        synchronized(ContactPictureStore.lock) {
            val current = snapshotForAccount(preferences, accountRef) ?: return@synchronized false
            current.accountRef == snapshot.accountRef && current.nicknames == snapshot.nicknames
        }

    /** Trims local account labels without changing their case-sensitive storage identity. */
    private fun normalizedAccountRef(accountRef: String?): String? =
        accountRef
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Canonicalizes hex contact identity independently of the viewing account label. */
    private fun normalizedContactPubkey(contactPubkeyHex: String): String? =
        contactPubkeyHex
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

    // Key by the Marmot account ref/label rather than account hex: writes use
    // WhiteNoiseAppState.activeAccountRef, notification updates carry
    // update.accountRef, and sign-out/wipe cleanup receives the same ref.

    /** Length-prefixes the account label so prefix-based cleanup cannot match another account. */
    private fun accountKeyPrefix(accountRef: String): String = "$KeyPrefix${accountRef.length}:$accountRef:"
}
