package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentControlFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.ChatStorageCursor
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RetainedAttachmentsCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Rows per page for both largest-first lists, MarmotKit accepts 1 to 256. */
internal const val STORAGE_PAGE_SIZE = 50

/**
 * Manage Storage's view of one account and native runtime. MarmotKit owns every figure and removal, Android only
 * measures and clears its own temporary decrypted copies.
 */
internal interface ManageStorageAccess {
    /** True while the captured account and runtime are still the active ones. */
    fun isCurrent(): Boolean

    /** The account's storage figures, including Android's temporary copies. */
    suspend fun usage(): StorageUsage

    /** The account's Keep media window. */
    suspend fun keepMedia(): KeepMediaPeriod

    /** Chats by retained attachment bytes, largest first. */
    suspend fun chats(cursor: StorageCursor?): StoragePage<ChatStorageEntry>

    /** Retained attachments, largest first, across the account or within [groupIdHex]. */
    suspend fun attachments(
        groupIdHex: String?,
        cursor: StorageCursor?,
    ): StoragePage<StoredAttachment>

    /** Saves [period] and removes what it no longer keeps, returns how many attachments were removed. */
    suspend fun setKeepMedia(period: KeepMediaPeriod): Long

    /** Removes attachments received before [receivedBeforeSeconds], returns how many were removed. */
    suspend fun removeOlderThan(
        receivedBeforeSeconds: Long,
        groupIdHex: String?,
    ): Long

    /** Removes every retained attachment in one chat, returns how many were removed. */
    suspend fun removeChat(groupIdHex: String): Long

    /** Removes the given attachments, returns how many were removed. */
    suspend fun removeAttachments(items: List<StoredAttachment>): Long

    /** Clears the picture cache and Android's temporary decrypted copies. */
    suspend fun clearCaches()
}

/** True while no sign-out or wipe is running for the active account. */
internal fun WhiteNoiseAppState.manageStorageEligible(account: String? = activeAccountRef): Boolean {
    val signingOut = signOutInProgress || wipeInProgress
    return account != null && !signingOut
}

/** Captures the active account and runtime, or null when storage cannot be read right now. */
internal fun WhiteNoiseAppState.manageStorageAccess(): ManageStorageAccess? {
    val account = activeAccountRef ?: return null
    val runtime = captureHostPerformanceRuntimeOwner()?.runtime
    val generation = runtimeGeneration
    return if (runtime == null || !manageStorageEligible(account)) {
        null
    } else {
        NativeManageStorageAccess(
            account = account,
            runtime = runtime,
            appState = this,
            owns = { activeAccountRef == account && runtimeGeneration == generation && manageStorageEligible(account) },
        )
    }
}

/** MarmotKit-backed access, every native call runs on [dispatcher] and is admitted only while still current. */
internal class NativeManageStorageAccess(
    private val account: String,
    private val runtime: AppMarmotRuntime,
    private val appState: WhiteNoiseAppState,
    private val owns: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ManageStorageAccess {
    private val native = NativeStorageCalls(runtime, dispatcher, owns)

    /** True while the captured account and runtime are still active. */
    override fun isCurrent(): Boolean = owns()

    /** MarmotKit's figures plus Android's temporary copies, measured first off the main thread. */
    override suspend fun usage(): StorageUsage {
        val temporary = appState.temporaryAttachmentCopyBytes()
        return native.read { StorageUsage.from(attachmentStorageUsage(account), temporary) }
    }

    /** The account's stored Keep media window. */
    override suspend fun keepMedia(): KeepMediaPeriod {
        val keep = native.read { attachmentKeepMedia(account) }
        return KeepMediaPeriod.from(keep)
    }

    /** One page of chats by retained bytes, largest first. */
    override suspend fun chats(cursor: StorageCursor?): StoragePage<ChatStorageEntry> =
        native.read {
            attachmentStorageByChat(account, STORAGE_PAGE_SIZE.toUInt(), cursor?.handle as ChatStorageCursor?).let {
                StoragePage(
                    items =
                        it.chats.map { chat ->
                            ChatStorageEntry(
                                chat.groupIdHex,
                                chat.bytes.toSaturatedLong(),
                                chat.attachmentCount.toSaturatedLong(),
                            )
                        },
                    next = it.nextCursor?.let(::StorageCursor),
                )
            }
        }

    /** One page of retained attachments, largest first. */
    override suspend fun attachments(
        groupIdHex: String?,
        cursor: StorageCursor?,
    ): StoragePage<StoredAttachment> =
        native.read {
            val handle = cursor?.handle as RetainedAttachmentsCursor?
            largestRetainedAttachments(account, groupIdHex, STORAGE_PAGE_SIZE.toUInt(), handle).let { page ->
                StoragePage(
                    items =
                        page.attachments.map {
                            StoredAttachment(
                                groupIdHex = it.groupIdHex,
                                messageIdHex = it.target.messageIdHex,
                                sourceMessageIdHex = it.target.sourceMessageIdHex,
                                attachmentIndex = it.target.attachmentIndex.toInt(),
                                bytes = it.byteCount.toSaturatedLong(),
                                mediaType = it.mediaType,
                                receivedAtSeconds = it.receivedAt.toSaturatedLong(),
                            )
                        },
                    next = page.nextCursor?.let(::StorageCursor),
                )
            }
        }

    /** Saves the window and drops every decrypted copy when anything was removed. */
    override suspend fun setKeepMedia(period: KeepMediaPeriod): Long =
        admitted(removal = true) {
            // A bulk removal that returns only a count names no attachments, so every decrypted copy goes.
            native { setAttachmentKeepMedia(account, period.ffi).toSaturatedLong() }
                .also { if (it > 0) appState.clearDecryptedAttachmentCopies() }
        }

    /** Removes by age and drops every decrypted copy when anything was removed. */
    override suspend fun removeOlderThan(
        receivedBeforeSeconds: Long,
        groupIdHex: String?,
    ): Long =
        admitted(removal = true) {
            val before = receivedBeforeSeconds.coerceAtLeast(0).toULong()
            native { removeAttachmentsOlderThan(account, before, groupIdHex).toSaturatedLong() }
                .also { if (it > 0) appState.clearDecryptedAttachmentCopies() }
        }

    /** Removes one chat's attachments and evicts that chat's decrypted copies. */
    override suspend fun removeChat(groupIdHex: String): Long =
        admitted(removal = true) {
            native { removeChatAttachments(account, groupIdHex).toSaturatedLong() }.also {
                if (it > 0) {
                    appState.evictGroupMediaCaches(account, groupIdHex)
                    appState.clearTemporaryAttachmentFiles()
                }
            }
        }

    /**
     * Removes every selected attachment that is still held, then evicts exactly those decrypted copies. A failure
     * part way still evicts what was already removed, and fails the action only when nothing was.
     */
    override suspend fun removeAttachments(items: List<StoredAttachment>): Long =
        admitted(removal = true) {
            val removed = mutableListOf<StoredAttachment>()
            val failure =
                runCatching {
                    items.forEach { item -> if (native { removeLocalAttachment(account, item) }) removed += item }
                }.exceptionOrNull()
            if (removed.isNotEmpty()) {
                appState.evictAttachmentCopies(account, removed)
                appState.clearTemporaryAttachmentFiles()
            }
            if (failure != null && removed.isEmpty()) throw failure
            removed.size.toLong()
        }

    /** Clears the account's picture cache and every decrypted attachment copy. */
    override suspend fun clearCaches() {
        admitted(removal = true) {
            native { clearAvatarCache(account) }
            appState.clearDecryptedAttachmentCopies()
        }
    }

    /**
     * Admits [block] once the owner is current. A [removal] then runs its native call and cache eviction as one unit
     * that leaving the screen or switching accounts cannot interrupt, so no removed attachment keeps a decrypted copy.
     */
    private suspend fun <T> admitted(
        removal: Boolean = false,
        block: suspend () -> T,
    ): T {
        if (!isCurrent()) throw CancellationException("Manage Storage owner retired")
        return if (removal) withContext(NonCancellable) { block() } else block()
    }
}

/** Runs MarmotKit calls for one runtime on [dispatcher]. */
private class NativeStorageCalls(
    private val runtime: AppMarmotRuntime,
    private val dispatcher: CoroutineDispatcher,
    private val isCurrent: () -> Boolean,
) {
    /** Runs [block] against the runtime off the main thread, without an ownership check. */
    suspend operator fun <T> invoke(block: suspend MarmotInterface.() -> T): T {
        val marmot = runtime.marmot
        return withContext(dispatcher) { marmot.block() }
    }

    /** Runs one read once the owner is confirmed current. */
    suspend fun <T> read(block: suspend MarmotInterface.() -> T): T {
        if (!isCurrent()) throw CancellationException("Manage Storage owner retired")
        return invoke(block)
    }
}

/** Resolves the attachment's local copy and removes it, false when nothing is held locally any more. */
private suspend fun MarmotInterface.removeLocalAttachment(
    account: String,
    item: StoredAttachment,
): Boolean {
    val target = AttachmentLocalTargetFfi(item.messageIdHex, item.sourceMessageIdHex, item.attachmentIndex.toUInt())
    val reference = attachmentLocalAssets(account, item.groupIdHex, listOf(target)).singleOrNull()?.reference
    return reference != null && controlAttachment(account, reference, AttachmentControlFfi.REMOVE)
}
