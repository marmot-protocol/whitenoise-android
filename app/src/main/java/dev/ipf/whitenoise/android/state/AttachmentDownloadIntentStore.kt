package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import android.util.Log

internal enum class AttachmentOpenIntentClaim {
    Fresh,
    InstallPermissionRecovery,
}

private const val OPEN_TOKEN_SEPARATOR = ":"
private const val INSTALLER_HANDOFF_ACCOUNT = "attachment_installer_handoff_account"
private const val INSTALLER_HANDOFF_GROUP = "attachment_installer_handoff_group"
private const val INSTALLER_HANDOFF_MESSAGE = "attachment_installer_handoff_message"
private const val INSTALLER_HANDOFF_INDEX = "attachment_installer_handoff_index"
private const val INSTALLER_HANDOFF_SOURCE_EPOCH = "attachment_installer_handoff_source_epoch"
private const val INSTALLER_HANDOFF_PHASE = "attachment_installer_handoff_phase"

private enum class InstallerHandoffPhase {
    Fresh,
    InstallPermissionRecovery,
}

private data class PersistedInstallerHandoff(
    val request: AttachmentInstallerHandoffRequest,
    val phase: InstallerHandoffPhase,
)

/**
 * Persists Android-owned scheduling intent only. MDK remains the authority for
 * attachment references, cryptographic material, transfer state, and bytes.
 */
@Suppress("TooManyFunctions") // Cohesive persistence boundary for one attachment-intent record type.
internal class AttachmentDownloadIntentStore(
    private val preferences: SharedPreferences,
    private val installerHandoffRecords: AttachmentInstallerHandoffRecordStore =
        VolatileAttachmentInstallerHandoffRecordStore(),
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

    /**
     * Durably records the one app-owned APK installer handoff before its
     * interactive transfer is admitted. A later APK tap supersedes an older
     * fresh handoff; a Settings-owned permission round trip remains exclusive.
     */
    @Suppress("MaxLineLength")
    fun markInstallerHandoff(request: AttachmentInstallerHandoffRequest): Boolean = markInstallerHandoffUnlessSuperseded(request) { false }

    /** Rejects an async persistence attempt after a newer request or cancellation wins. */
    fun markInstallerHandoffUnlessSuperseded(
        request: AttachmentInstallerHandoffRequest,
        superseded: () -> Boolean,
    ): Boolean =
        synchronized(LOCK) {
            if (superseded()) return@synchronized false
            val current = readInstallerHandoff()
            if (
                current?.phase == InstallerHandoffPhase.InstallPermissionRecovery &&
                requestToken(current.request.transfer) in ACTIVE_INSTALLER_PERMISSION_HANDOFFS
            ) {
                return@synchronized current.request == request
            }
            commitInstallerHandoff(PersistedInstallerHandoff(request, InstallerHandoffPhase.Fresh)).also { committed ->
                if (committed) {
                    current?.request?.let {
                        ACTIVE_INSTALLER_PERMISSION_HANDOFFS -= requestToken(it.transfer)
                    }
                }
            }
        }

    /** Returns the app-owned handoff that a process owner may currently resume. */
    fun pendingInstallerHandoff(): AttachmentInstallerHandoffRequest? =
        synchronized(LOCK) {
            readInstallerHandoff()
                ?.takeUnless { handoff ->
                    handoff.phase == InstallerHandoffPhase.InstallPermissionRecovery &&
                        requestToken(handoff.request.transfer) in ACTIVE_INSTALLER_PERMISSION_HANDOFFS
                }?.request
        }

    /** True while this exact APK request still owns the one-shot handoff. */
    fun hasInstallerHandoff(request: AttachmentInstallerHandoffRequest): Boolean = pendingInstallerHandoff() == request

    /** Atomically consumes a fresh launch or process-restored permission recovery. */
    fun claimInstallerHandoff(request: AttachmentInstallerHandoffRequest): AttachmentOpenIntentClaim? =
        synchronized(LOCK) {
            val current = readInstallerHandoff()?.takeIf { it.request == request } ?: return@synchronized null
            val token = requestToken(request.transfer)
            if (
                current.phase == InstallerHandoffPhase.InstallPermissionRecovery &&
                token in ACTIVE_INSTALLER_PERMISSION_HANDOFFS
            ) {
                return@synchronized null
            }
            if (!commitInstallerHandoff(null)) return@synchronized null
            ACTIVE_INSTALLER_PERMISSION_HANDOFFS -= token
            when (current.phase) {
                InstallerHandoffPhase.Fresh -> AttachmentOpenIntentClaim.Fresh
                InstallerHandoffPhase.InstallPermissionRecovery -> {
                    AttachmentOpenIntentClaim.InstallPermissionRecovery
                }
            }
        }

    /** Persists ownership before the unknown-sources Settings activity starts. */
    fun beginInstallerPermissionHandoff(request: AttachmentInstallerHandoffRequest): Boolean =
        synchronized(LOCK) {
            if (readInstallerHandoff() != null) return@synchronized false
            val committed =
                commitInstallerHandoff(
                    PersistedInstallerHandoff(request, InstallerHandoffPhase.InstallPermissionRecovery),
                )
            if (committed) ACTIVE_INSTALLER_PERMISSION_HANDOFFS += requestToken(request.transfer)
            committed
        }

    /** Clears permission recovery immediately before the final installer launch. */
    fun finishInstallerPermissionHandoff(request: AttachmentInstallerHandoffRequest): Boolean =
        synchronized(LOCK) {
            val current = readInstallerHandoff()
            val committed =
                if (
                    current?.request == request &&
                    current.phase == InstallerHandoffPhase.InstallPermissionRecovery
                ) {
                    commitInstallerHandoff(null)
                } else {
                    true
                }
            if (committed) ACTIVE_INSTALLER_PERMISSION_HANDOFFS -= requestToken(request.transfer)
            committed
        }

    /** Releases only the in-process Settings owner so recreation may recover it. */
    fun abandonInstallerPermissionHandoff(request: AttachmentInstallerHandoffRequest) {
        synchronized(LOCK) {
            ACTIVE_INSTALLER_PERMISSION_HANDOFFS -= requestToken(request.transfer)
        }
    }

    /** Restores a launch that failed before Android accepted the external activity. */
    fun restoreInstallerHandoff(request: AttachmentInstallerHandoffRequest): Boolean =
        synchronized(LOCK) {
            val current = readInstallerHandoff()
            if (current != null) return@synchronized current.request == request
            commitInstallerHandoff(PersistedInstallerHandoff(request, InstallerHandoffPhase.Fresh))
        }

    /** Consumes cancellation or a deterministic terminal transfer/launch result. */
    fun consumeInstallerHandoff(request: AttachmentInstallerHandoffRequest): Boolean =
        synchronized(LOCK) {
            val current = readInstallerHandoff()?.takeIf { it.request == request } ?: return@synchronized false
            commitInstallerHandoff(null).also { committed ->
                if (committed) ACTIVE_INSTALLER_PERMISSION_HANDOFFS -= requestToken(current.request.transfer)
            }
        }

    /** Keeps a same-identity re-tap that superseded an asynchronous cancellation. */
    fun consumeInstallerHandoffUnlessSuperseded(
        transfer: AttachmentTransferRequest,
        superseded: () -> Boolean,
    ): Boolean =
        synchronized(LOCK) {
            if (superseded()) {
                false
            } else {
                val request = readInstallerHandoff()?.request?.takeIf { it.transfer == transfer }
                request != null && consumeInstallerHandoff(request)
            }
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

    /** Commits the whole installer record as one SharedPreferences transaction. */
    private fun commitInstallerHandoff(next: PersistedInstallerHandoff?): Boolean {
        val previous = readInstallerHandoff()
        val committed = installerHandoffRecords.replaceAllDurably(next?.toPersistedValues().orEmpty())
        if (!committed) {
            restoreInstallerHandoffRecord(previous)
            Log.w(TAG, "installer handoff commit failed; previous scheduling state restored")
        }
        return committed
    }

    /** Restores only a structurally complete installer identity from disk. */
    private fun readInstallerHandoff(): PersistedInstallerHandoff? {
        val values = installerHandoffRecords.readAll()
        val account = values[INSTALLER_HANDOFF_ACCOUNT].orEmpty()
        val group = values[INSTALLER_HANDOFF_GROUP].orEmpty()
        val message = values[INSTALLER_HANDOFF_MESSAGE].orEmpty()
        val index = values[INSTALLER_HANDOFF_INDEX]?.toIntOrNull() ?: -1
        val sourceEpoch = values[INSTALLER_HANDOFF_SOURCE_EPOCH]?.toULongOrNull()
        val phase =
            values[INSTALLER_HANDOFF_PHASE]?.let {
                runCatching { InstallerHandoffPhase.valueOf(it) }.getOrNull()
            }
        val validIdentity =
            account.isNotBlank() &&
                ATTACHMENT_GROUP_ID_HEX.matches(group) &&
                ATTACHMENT_MESSAGE_ID_HEX.matches(message)
        val validRecord = validIdentity && index >= 0 && sourceEpoch != null && phase != null
        if (!validRecord) {
            return null
        }
        return PersistedInstallerHandoff(
            request =
                AttachmentInstallerHandoffRequest(
                    transfer = AttachmentTransferRequest(account, group, message, index),
                    sourceEpoch = checkNotNull(sourceEpoch),
                ),
            phase = phase,
        )
    }

    /** Repairs SharedPreferences' in-memory view after a failed synchronous commit. */
    private fun restoreInstallerHandoffRecord(handoff: PersistedInstallerHandoff?) {
        if (!installerHandoffRecords.replaceAllDurably(handoff?.toPersistedValues().orEmpty())) {
            Log.w(TAG, "installer handoff rollback failed; scheduling state remains unavailable")
        }
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
        val ACTIVE_INSTALLER_PERMISSION_HANDOFFS = mutableSetOf<String>()
    }
}

/** Encodes one handoff for the encrypted record store without protocol attachment data. */
private fun PersistedInstallerHandoff.toPersistedValues(): Map<String, String> =
    mapOf(
        INSTALLER_HANDOFF_ACCOUNT to request.transfer.accountRef,
        INSTALLER_HANDOFF_GROUP to request.transfer.groupIdHex,
        INSTALLER_HANDOFF_MESSAGE to request.transfer.messageIdHex,
        INSTALLER_HANDOFF_INDEX to request.transfer.attachmentIndex.toString(),
        INSTALLER_HANDOFF_SOURCE_EPOCH to request.sourceEpoch.toString(),
        INSTALLER_HANDOFF_PHASE to phase.name,
    )

/** Hides a raw account reference before it enters ordinary preferences. */
private fun accountToken(accountRef: String): String = attachmentIdentityDigest(accountRef)

/** Produces the opaque token shared by transfer-intent sets. */
private fun requestToken(request: AttachmentTransferRequest): String = attachmentIdentityTag(request)

/** Scopes a viewer handoff to one account, group, and navigation session. */
private fun destinationToken(destination: AttachmentOpenDestination): String =
    attachmentIdentityDigest(
        listOf(
            destination.accountRef,
            destination.groupIdHex.lowercase(),
            destination.navigationGeneration.toString(),
        ).joinToString("\u0000"),
    )

/** Combines route and attachment identity so stale destinations cannot claim a launch. */
private fun openRequestToken(request: AttachmentOpenRequest): String =
    buildString {
        append(destinationToken(request.destination))
        append(OPEN_TOKEN_SEPARATOR)
        append(requestToken(request.transferRequest))
    }
