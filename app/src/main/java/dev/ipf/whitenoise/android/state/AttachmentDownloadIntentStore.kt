package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import android.util.Log

internal enum class AttachmentOpenIntentClaim {
    Fresh,
    InstallPermissionRecovery,
}

/**
 * Persists Android-owned scheduling intent only. MDK remains the authority for
 * attachment references, cryptographic material, transfer state, and bytes.
 */
@Suppress("TooManyFunctions") // Cohesive persistence boundary for one attachment-intent record type.
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

    fun markOpenIntent(request: AttachmentTransferRequest) {
        synchronized(LOCK) {
            val token = requestToken(request)
            // A repeated tap/recomposition must not create a second launch
            // behind an already durable permission-screen handoff.
            if (token in readSet(INSTALL_PERMISSION_IDENTITIES)) return
            preferences.edit().putStringSet(OPEN_IDENTITIES, readSet(OPEN_IDENTITIES) + token).apply()
        }
    }

    fun hasOpenIntent(request: AttachmentTransferRequest): Boolean = requestToken(request) in readSet(OPEN_IDENTITIES)

    /**
     * A permission handoff remains durable while Settings owns the foreground.
     * The in-process owner suppresses sibling Compose effects; after process
     * recreation that volatile owner is gone and the persisted recovery becomes
     * dispatchable again.
     */
    fun hasDispatchableOpenIntent(request: AttachmentTransferRequest): Boolean =
        synchronized(LOCK) {
            val token = requestToken(request)
            token in readSet(OPEN_IDENTITIES) ||
                (token in readSet(INSTALL_PERMISSION_IDENTITIES) && token !in ACTIVE_INSTALL_PERMISSION_IDENTITIES)
        }

    /** Atomically claims either a fresh tap or a permission-screen recovery. */
    fun claimOpenIntent(request: AttachmentTransferRequest): AttachmentOpenIntentClaim? =
        synchronized(LOCK) {
            val token = requestToken(request)
            val permissionIdentities = readSet(INSTALL_PERMISSION_IDENTITIES)
            if (token in permissionIdentities && token !in ACTIVE_INSTALL_PERMISSION_IDENTITIES) {
                return@synchronized if (commitRemoval(INSTALL_PERMISSION_IDENTITIES, permissionIdentities, token)) {
                    AttachmentOpenIntentClaim.InstallPermissionRecovery
                } else {
                    null
                }
            }

            val openIdentities = readSet(OPEN_IDENTITIES)
            if (token !in openIdentities) return@synchronized null
            if (commitRemoval(OPEN_IDENTITIES, openIdentities, token)) {
                AttachmentOpenIntentClaim.Fresh
            } else {
                null
            }
        }

    /**
     * Durably hands a consumed fresh tap to the unknown-sources Settings flow.
     * Returns false when the recovery marker could not be committed to disk.
     */
    fun beginInstallPermissionRequest(request: AttachmentTransferRequest): Boolean =
        synchronized(LOCK) {
            val token = requestToken(request)
            val current = readSet(INSTALL_PERMISSION_IDENTITIES)
            val committed =
                preferences
                    .edit()
                    .putStringSet(INSTALL_PERMISSION_IDENTITIES, current + token)
                    .commit()
            if (committed) {
                ACTIVE_INSTALL_PERMISSION_IDENTITIES += token
            } else {
                preferences.edit().putStringSet(INSTALL_PERMISSION_IDENTITIES, current).apply()
                Log.w(TAG, "Install-permission recovery commit failed; attachment open was not handed off")
            }
            committed
        }

    /** Clears the durable handoff immediately before the final installer dispatch. */
    fun finishInstallPermissionRequest(request: AttachmentTransferRequest): Boolean =
        synchronized(LOCK) {
            val token = requestToken(request)
            val current = readSet(INSTALL_PERMISSION_IDENTITIES)
            val committed =
                if (token in current) {
                    commitRemoval(INSTALL_PERMISSION_IDENTITIES, current, token)
                } else {
                    true
                }
            ACTIVE_INSTALL_PERMISSION_IDENTITIES -= token
            committed
        }

    /** Composition was replaced while Settings was open; keep the durable marker for the next owner. */
    fun abandonInstallPermissionRequest(request: AttachmentTransferRequest) {
        synchronized(LOCK) {
            ACTIVE_INSTALL_PERMISSION_IDENTITIES -= requestToken(request)
        }
    }

    /** A failed final launch becomes a normal retry unless a permission handoff is already durable. */
    fun restoreOpenIntent(request: AttachmentTransferRequest) {
        synchronized(LOCK) {
            val token = requestToken(request)
            if (token in readSet(INSTALL_PERMISSION_IDENTITIES)) return
            preferences.edit().putStringSet(OPEN_IDENTITIES, readSet(OPEN_IDENTITIES) + token).apply()
        }
    }

    /** Atomically fences repeated/late viewer dispatch before the external launch. */
    fun consumeOpenIntent(request: AttachmentTransferRequest): Boolean =
        synchronized(LOCK) {
            val current = readSet(OPEN_IDENTITIES)
            val token = requestToken(request)
            if (token !in current) return@synchronized false
            commitRemoval(OPEN_IDENTITIES, current, token)
        }

    private fun commitRemoval(
        key: String,
        current: Set<String>,
        token: String,
    ): Boolean {
        val committed = preferences.edit().putStringSet(key, current - token).commit()
        if (!committed) {
            // SharedPreferences may expose the staged removal in memory even
            // when its durable write fails. Fail closed and restore the token.
            preferences.edit().putStringSet(key, current).apply()
            Log.w(TAG, "$key disk commit failed; attachment dispatch remains pending")
        }
        return committed
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
        const val INSTALL_PERMISSION_IDENTITIES = "attachment_install_permission_identities"
        val ACTIVE_INSTALL_PERMISSION_IDENTITIES = mutableSetOf<String>()
    }
}

private fun accountToken(accountRef: String): String = attachmentIdentityDigest(accountRef)

private fun requestToken(request: AttachmentTransferRequest): String = attachmentIdentityTag(request)
