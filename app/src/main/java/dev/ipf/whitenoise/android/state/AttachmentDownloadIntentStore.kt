package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import android.util.Log

internal enum class AttachmentOpenIntentClaim {
    Fresh,
    InstallPermissionRecovery,
}

private const val OPEN_TOKEN_SEPARATOR = ":"

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

    /**
     * Records that the user cancelled this attachment, so the automatic path
     * cannot restart it. Cancelling only the in-memory state would be undone the
     * next time the card is recreated: its transfer state is retired with the
     * composable, and a fresh cold cache probe resolves to a plain remote
     * attachment that the auto-download policy immediately re-enqueues.
     */
    fun suppressAutomatic(request: AttachmentTransferRequest) = updateSet(CANCELLED) { it + requestToken(request) }

    /** Explicit user intent outranks an earlier cancel and reopens the automatic path. */
    fun restoreAutomatic(request: AttachmentTransferRequest) = updateSet(CANCELLED) { it - requestToken(request) }

    fun isAutomaticSuppressed(request: AttachmentTransferRequest): Boolean = requestToken(request) in readSet(CANCELLED)

    /** Restarting the whole automatic backlog is a resume-everything signal. */
    fun clearSuppressedAutomatic() {
        updateSet(CANCELLED) { emptySet() }
    }

    fun markOpenIntent(request: AttachmentOpenRequest) {
        synchronized(LOCK) {
            val token = openRequestToken(request)
            // A repeated tap/recomposition must not create a second launch
            // behind an already durable permission-screen handoff.
            if (token in readSet(INSTALL_PERMISSION_IDENTITIES)) return
            preferences.edit().putStringSet(OPEN_IDENTITIES, readSet(OPEN_IDENTITIES) + token).apply()
        }
    }

    fun hasOpenIntent(request: AttachmentOpenRequest): Boolean = openRequestToken(request) in readSet(OPEN_IDENTITIES)

    /**
     * A permission handoff remains durable while Settings owns the foreground.
     * The in-process owner suppresses sibling Compose effects; after process
     * recreation that volatile owner is gone and the persisted recovery becomes
     * dispatchable again.
     */
    fun hasDispatchableOpenIntent(request: AttachmentOpenRequest): Boolean =
        synchronized(LOCK) {
            val token = openRequestToken(request)
            token in readSet(OPEN_IDENTITIES) ||
                (token in readSet(INSTALL_PERMISSION_IDENTITIES) && token !in ACTIVE_INSTALL_PERMISSION_IDENTITIES)
        }

    /** Atomically claims either a fresh tap or a permission-screen recovery. */
    fun claimOpenIntent(request: AttachmentOpenRequest): AttachmentOpenIntentClaim? =
        synchronized(LOCK) {
            val token = openRequestToken(request)
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
    fun beginInstallPermissionRequest(request: AttachmentOpenRequest): Boolean =
        synchronized(LOCK) {
            val token = openRequestToken(request)
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
    fun finishInstallPermissionRequest(request: AttachmentOpenRequest): Boolean =
        synchronized(LOCK) {
            val token = openRequestToken(request)
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
    fun abandonInstallPermissionRequest(request: AttachmentOpenRequest) {
        synchronized(LOCK) {
            ACTIVE_INSTALL_PERMISSION_IDENTITIES -= openRequestToken(request)
        }
    }

    /** A failed final launch becomes a normal retry unless a permission handoff is already durable. */
    fun restoreOpenIntent(request: AttachmentOpenRequest) {
        synchronized(LOCK) {
            val token = openRequestToken(request)
            if (token in readSet(INSTALL_PERMISSION_IDENTITIES)) return
            preferences.edit().putStringSet(OPEN_IDENTITIES, readSet(OPEN_IDENTITIES) + token).apply()
        }
    }

    /**
     * Drops a cancelled viewer handoff unless [superseded] reports that a newer
     * tap re-armed the same identity while this revocation was still reaching
     * disk. Both halves run under the store lock, so a tap cannot slip between
     * the check and the removal and lose its own fresh intent.
     */
    fun consumeOpenIntentUnlessSuperseded(
        request: AttachmentOpenRequest,
        superseded: () -> Boolean,
    ): Boolean =
        synchronized(LOCK) {
            if (superseded()) false else consumeOpenIntent(request)
        }

    /** Atomically fences repeated/late viewer dispatch before the external launch. */
    fun consumeOpenIntent(request: AttachmentOpenRequest): Boolean =
        synchronized(LOCK) {
            val current = readSet(OPEN_IDENTITIES)
            val token = openRequestToken(request)
            if (token !in current) return@synchronized false
            commitRemoval(OPEN_IDENTITIES, current, token)
        }

    /**
     * Cancels viewer/installer handoffs outside the selected navigation
     * session without touching the independently durable transfer identity.
     */
    fun retainOpenIntentsForDestination(destination: AttachmentOpenDestination?) {
        retainOpenIntentsForCurrentDestination { destination }
    }

    /** Reads the latest route only after owning the store lock, fencing rapid Back/open races. */
    fun retainOpenIntentsForCurrentDestination(currentDestination: () -> AttachmentOpenDestination?) {
        synchronized(LOCK) {
            val destination = currentDestination()
            val prefix = destination?.let { "${destinationToken(it)}$OPEN_TOKEN_SEPARATOR" }
            val open = readSet(OPEN_IDENTITIES)
            val permission = readSet(INSTALL_PERMISSION_IDENTITIES)
            val retainedOpen = open.filterTo(mutableSetOf()) { prefix != null && it.startsWith(prefix) }
            val retainedPermission =
                permission.filterTo(mutableSetOf()) { prefix != null && it.startsWith(prefix) }
            if (retainedOpen == open && retainedPermission == permission) return

            val committed =
                preferences
                    .edit()
                    .putStringSet(OPEN_IDENTITIES, retainedOpen)
                    .putStringSet(INSTALL_PERMISSION_IDENTITIES, retainedPermission)
                    .commit()
            if (committed) {
                ACTIVE_INSTALL_PERMISSION_IDENTITIES.retainAll(retainedPermission)
            } else {
                preferences
                    .edit()
                    .putStringSet(OPEN_IDENTITIES, open)
                    .putStringSet(INSTALL_PERMISSION_IDENTITIES, permission)
                    .apply()
                Log.w(TAG, "navigation-session cleanup failed; stale attachment opens remain fenced")
            }
        }
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
        const val CANCELLED = "attachment_download_cancelled_identities"
        const val OPEN_IDENTITIES = "attachment_download_open_identities"
        const val INSTALL_PERMISSION_IDENTITIES = "attachment_install_permission_identities"
        val ACTIVE_INSTALL_PERMISSION_IDENTITIES = mutableSetOf<String>()
    }
}

private fun accountToken(accountRef: String): String = attachmentIdentityDigest(accountRef)

private fun requestToken(request: AttachmentTransferRequest): String = attachmentIdentityTag(request)

private fun destinationToken(destination: AttachmentOpenDestination): String =
    attachmentIdentityDigest(
        listOf(
            destination.accountRef,
            destination.groupIdHex.lowercase(),
            destination.navigationGeneration.toString(),
        ).joinToString("\u0000"),
    )

private fun openRequestToken(request: AttachmentOpenRequest): String =
    buildString {
        append(destinationToken(request.destination))
        append(OPEN_TOKEN_SEPARATOR)
        append(requestToken(request.transferRequest))
    }
