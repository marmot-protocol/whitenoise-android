package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import android.util.Log

/**
 * Persists Android-owned scheduling intent only. MDK remains the authority for
 * attachment references, cryptographic material, transfer state, and bytes.
 */
internal class AttachmentDownloadIntentStore(
    private val preferences: SharedPreferences,
) {
    fun pauseAutomatic(accountRef: String) = updateSet(PAUSED_ACCOUNTS) { it + accountToken(accountRef) }

    fun restartAutomatic(accountRef: String) = updateSet(PAUSED_ACCOUNTS) { it - accountToken(accountRef) }

    fun isAutomaticPaused(accountRef: String): Boolean = accountToken(accountRef) in readSet(PAUSED_ACCOUNTS)

    fun setInteractive(
        request: AttachmentTransferRequest,
        interactive: Boolean,
    ) = updateSet(INTERACTIVE_IDENTITIES) { identities ->
        val token = requestToken(request)
        if (interactive) identities + token else identities - token
    }

    fun isInteractive(request: AttachmentTransferRequest): Boolean {
        val interactiveIdentities = readSet(INTERACTIVE_IDENTITIES)
        return requestToken(request) in interactiveIdentities
    }

    fun containsInteractiveTag(tags: Set<String>): Boolean = tags.any(readSet(INTERACTIVE_IDENTITIES)::contains)

    fun markOpenIntent(request: AttachmentTransferRequest) = updateSet(OPEN_IDENTITIES) { it + requestToken(request) }

    fun hasOpenIntent(request: AttachmentTransferRequest): Boolean = requestToken(request) in readSet(OPEN_IDENTITIES)

    /** Atomically fences repeated/late viewer dispatch before the external launch. */
    fun consumeOpenIntent(request: AttachmentTransferRequest): Boolean =
        synchronized(LOCK) {
            val current = readSet(OPEN_IDENTITIES)
            val token = requestToken(request)
            if (token !in current) return@synchronized false
            val committed = preferences.edit().putStringSet(OPEN_IDENTITIES, current - token).commit()
            if (!committed) {
                // SharedPreferences may expose the staged removal in memory
                // even when its durable write fails. Fail closed and restore
                // the token so this process cannot dispatch ahead of disk.
                preferences.edit().putStringSet(OPEN_IDENTITIES, current).apply()
                Log.w(TAG, "Open-intent disk commit failed; viewer dispatch remains pending")
            }
            committed
        }

    private fun readSet(key: String): Set<String> = preferences.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun updateSet(
        key: String,
        transform: (Set<String>) -> Set<String>,
    ) {
        synchronized(LOCK) {
            preferences.edit().putStringSet(key, transform(readSet(key))).apply()
        }
    }

    private companion object {
        const val TAG = "AttachmentIntentStore"
        val LOCK = Any()
        const val PAUSED_ACCOUNTS = "attachment_download_paused_accounts"
        const val INTERACTIVE_IDENTITIES = "attachment_download_interactive_identities"
        const val OPEN_IDENTITIES = "attachment_download_open_identities"
    }
}

private fun accountToken(accountRef: String): String = attachmentIdentityDigest(accountRef)

private fun requestToken(request: AttachmentTransferRequest): String = attachmentIdentityTag(request)
