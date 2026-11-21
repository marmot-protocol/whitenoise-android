package dev.ipf.whitenoise.android.media

import android.content.Context
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val MAX_SHARE_EXTENSION_LENGTH = 12
private const val ACCOUNT_ONLY = "owner:"
private val sessionGeneration = AtomicLong()
private val accountGenerations = ConcurrentHashMap<String, AtomicLong>()
private val sharedPlaintextMutex = Mutex()
private val pendingLiveWipes = mutableSetOf<String>()
internal const val WIPE_PREFERENCES = "shared_plaintext_wipe"
private const val PENDING_WIPES = "pending_account_prefixes"

/** Keeps staging names below the filesystem limit while FileProvider supplies the original display name. */
internal fun sharedPlaintextSuffix(displayName: String): String {
    val extension = displayName.substringAfterLast('.', "")
    val safeExtension =
        extension.length in 1..MAX_SHARE_EXTENSION_LENGTH &&
            extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    return if (safeExtension) ".$extension" else ".bin"
}

/** Binds a pending export to its account and to the session that admitted it. */
internal data class SharedPlaintextWrite(
    val owner: String,
    val sessionGeneration: Long,
    val accountGeneration: Long,
)

/** Capture before decryption so removing one account fences its writers while other exports finish. */
internal fun captureSharedPlaintextWrite(accountRef: String): SharedPlaintextWrite {
    val owner = legacySharedPlaintextPrefix(accountRef)
    return SharedPlaintextWrite(owner, sessionGeneration.get(), accountGenerations[owner]?.get() ?: 0L)
}

/** Serializes publication with cleanup and rejects a writer retired by either its account wipe or session teardown. */
internal suspend fun <T> withSharedPlaintextWrite(
    permit: SharedPlaintextWrite,
    write: suspend () -> T,
): T =
    withContext(Dispatchers.IO) {
        sharedPlaintextMutex.withLock {
            if (permit.sessionGeneration != sessionGeneration.get() ||
                permit.accountGeneration != (accountGenerations[permit.owner]?.get() ?: 0L)
            ) {
                throw CancellationException("shared plaintext was wiped")
            }
            write()
        }
    }

/** Existing journal entries include unowned legacy exports. New owner-only entries preserve that choice on restart. */
private fun wipeEntries(
    context: Context,
    accountRef: String,
    includeUnscoped: Boolean,
): Set<String> {
    val prefixes = setOf(sharedPlaintextPrefix(context, accountRef), legacySharedPlaintextPrefix(accountRef))
    return if (includeUnscoped) prefixes else prefixes.mapTo(mutableSetOf()) { ACCOUNT_ONLY + it }
}

/** Persists the cleanup obligation before native wipe can irreversibly remove the account. */
internal suspend fun recordSharedPlaintextWipe(
    context: Context,
    accountRef: String,
    includeUnscoped: Boolean = true,
): Boolean =
    withContext(Dispatchers.IO) {
        sharedPlaintextMutex.withLock {
            val prefixes = wipeEntries(context, accountRef, includeUnscoped)
            val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
            val pending = preferences.getStringSet(PENDING_WIPES, emptySet()).orEmpty() + prefixes
            val saved = preferences.edit().putStringSet(PENDING_WIPES, pending).commit()
            if (saved) pendingLiveWipes += prefixes
            saved
        }
    }

/** Disarms recovery when native explicitly retains the account after a rejected wipe. */
internal suspend fun discardSharedPlaintextWipe(
    context: Context,
    accountRef: String,
    includeUnscoped: Boolean = true,
): Boolean =
    withContext(Dispatchers.IO) {
        sharedPlaintextMutex.withLock {
            val prefixes = wipeEntries(context, accountRef, includeUnscoped)
            val discarded = removePendingWipe(context, prefixes)
            if (discarded) pendingLiveWipes -= prefixes
            discarded
        }
    }

/** Retries unfinished cleanup in a new process, including a process started by a background worker. */
internal suspend fun recoverSharedPlaintextWipes(
    context: Context,
    onFailure: (String, Throwable) -> Unit,
) {
    sharedPlaintextMutex.withLock {
        val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
        val pending = preferences.getStringSet(PENDING_WIPES, emptySet()).orEmpty() - pendingLiveWipes
        retrySharedPlaintextGrantRevocations(context, onFailure)
        if (pending.isNotEmpty()) finishSharedPlaintextWipe(context, pending, onFailure)
    }
}

/** Revokes FileProvider access before deleting app-owned plaintext, including unscoped copies from older builds. */
internal suspend fun wipeSharedPlaintext(
    context: Context,
    accountRef: String,
    includeUnscoped: Boolean = true,
    onFailure: (String, Throwable) -> Unit,
) {
    sharedPlaintextMutex.withLock {
        val prefixes = wipeEntries(context, accountRef, includeUnscoped)
        try {
            finishSharedPlaintextWipe(context, prefixes, onFailure)
        } finally {
            pendingLiveWipes -= prefixes
        }
    }
}

/** Retains the durable cleanup record when a file cannot be inspected, revoked, or deleted. */
private fun finishSharedPlaintextWipe(
    context: Context,
    prefixes: Set<String>,
    onFailure: (String, Throwable) -> Unit,
) {
    val includeUnscoped = prefixes.any { !it.startsWith(ACCOUNT_ONLY) }
    val owners = prefixes.mapTo(mutableSetOf()) { it.removePrefix(ACCOUNT_ONLY) }
    if (includeUnscoped) {
        sessionGeneration.incrementAndGet()
        accountGenerations.clear()
    } else {
        owners.forEach { accountGenerations.getOrPut(it) { AtomicLong() }.incrementAndGet() }
    }
    var failed = false
    deleteSharedPlaintext(context, owners, includeUnscoped) { area, error ->
        failed = true
        onFailure(area, error)
    }
    if (!failed && !removePendingWipe(context, prefixes)) {
        onFailure(WIPE_PREFERENCES, IOException("could not record shared plaintext cleanup"))
    }
}

/** Updates the durable cleanup set while holding the share mutex. */
private fun removePendingWipe(
    context: Context,
    prefixes: Set<String>,
): Boolean {
    val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
    val pending = preferences.getStringSet(PENDING_WIPES, emptySet()).orEmpty()
    val remaining = pending - prefixes
    return remaining == pending || preferences.edit().putStringSet(PENDING_WIPES, remaining).commit()
}

/** Lists both private share roots and attempts every matching file even after an individual failure. */
private fun deleteSharedPlaintext(
    context: Context,
    prefixes: Set<String>,
    includeUnscoped: Boolean,
    onFailure: (String, Throwable) -> Unit,
) {
    for (name in listOf(MediaCacheDirs.SHARED, ConversationTranscriptExport.CacheDirName)) {
        val directory = File(context.cacheDir, name)
        val entries =
            runCatching {
                if (directory.exists()) {
                    directory.listFiles() ?: throw IOException("could not list shared plaintext")
                } else {
                    emptyArray()
                }
            }.onFailure { onFailure(name, it) }.getOrNull() ?: continue
        val matching =
            entries.filter { file ->
                // Legacy private shares have no prefix. Public profile cards also use the shared root.
                val unowned = isUnownedPrivateShare(file.name)
                prefixes.any { file.name.startsWith(it) } || (includeUnscoped && unowned)
            }
        matching.forEach { file ->
            revokeSharedPlaintextGrant(context, file, name, onFailure)
            runCatching {
                check(file.delete() || !file.exists()) { "could not delete shared plaintext" }
            }.onFailure { onFailure(name, it) }
        }
    }
}
