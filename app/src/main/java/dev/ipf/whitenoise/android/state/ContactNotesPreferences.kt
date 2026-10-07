package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import java.util.Locale

/**
 * Private, local-only contact notes keyed by the viewing account and contact
 * pubkey. These are user-authored UI preferences, not cached protocol data.
 */
internal object ContactNotesPreferences {
    private const val KeyPrefix = "contact_notes:"

    /** Rejects incomplete ownership and combines a length-prefixed account scope with a normalized contact key. */
    fun preferenceKey(
        accountRef: String?,
        contactPubkeyHex: String,
    ): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        val contact = normalizedContactPubkey(contactPubkeyHex) ?: return null
        return accountKeyPrefix(account) + contact
    }

    /** Reads the account-private notes under the shared picture/details transaction lock. */
    fun readNotes(
        preferences: SharedPreferences,
        accountRef: String?,
        contactPubkeyHex: String,
    ): String? =
        synchronized(ContactPictureStore.lock) {
            val key = preferenceKey(accountRef, contactPubkeyHex) ?: return@synchronized null
            normalizedNotes(preferences.getString(key, null))
        }

    /** Stores only normalized changes and removes blank overrides so public defaults remain authoritative. */
    fun writeNotes(
        preferences: SharedPreferences,
        accountRef: String?,
        contactPubkeyHex: String,
        notes: String?,
    ): Boolean {
        val key = preferenceKey(accountRef, contactPubkeyHex) ?: return false
        val normalized = normalizedNotes(notes)
        val current = readNotes(preferences, accountRef, contactPubkeyHex)
        if (current == normalized && (normalized != null || !preferences.contains(key))) return false
        val edit = preferences.edit()
        if (normalized == null) {
            edit.remove(key)
        } else {
            edit.putString(key, normalized)
        }
        edit.apply()
        return true
    }

    /** Commits deletion of the exact account prefix on disk; similar account labels retain their private records. */
    fun clearAllForAccount(
        preferences: SharedPreferences,
        accountRef: String?,
    ): Boolean {
        val account = normalizedAccountRef(accountRef) ?: return false
        val prefix = accountKeyPrefix(account)
        val keys = preferences.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return false
        val edit = preferences.edit()
        keys.forEach { edit.remove(it) }
        // commit(), not apply(): a wipe must not leave contact data on disk if
        // the process dies before an async write lands. Callers hop to IO.
        return edit.commit()
    }

    /** Removes outer whitespace while preserving internal lines; empty notes remove the local override. */
    private fun normalizedNotes(notes: String?): String? =
        notes
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

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

    /** Length-prefixes the account label so prefix-based cleanup cannot match another account. */
    private fun accountKeyPrefix(accountRef: String): String = "$KeyPrefix${accountRef.length}:$accountRef:"
}
