package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import java.util.Locale

/**
 * Private, local-only contact notes keyed by the viewing account and contact
 * pubkey. These are user-authored UI preferences, not cached protocol data.
 */
internal object ContactNotesPreferences {
    private const val KeyPrefix = "contact_notes:"

    fun preferenceKey(
        accountRef: String?,
        contactPubkeyHex: String,
    ): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        val contact = normalizedContactPubkey(contactPubkeyHex) ?: return null
        return accountKeyPrefix(account) + contact
    }

    fun readNotes(
        preferences: SharedPreferences,
        accountRef: String?,
        contactPubkeyHex: String,
    ): String? {
        val key = preferenceKey(accountRef, contactPubkeyHex) ?: return null
        return normalizedNotes(preferences.getString(key, null))
    }

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
        return edit.commit()
    }

    private fun normalizedNotes(notes: String?): String? =
        notes
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun normalizedAccountRef(accountRef: String?): String? =
        accountRef
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun normalizedContactPubkey(contactPubkeyHex: String): String? =
        contactPubkeyHex
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

    private fun accountKeyPrefix(accountRef: String): String = "$KeyPrefix${accountRef.length}:$accountRef:"
}
