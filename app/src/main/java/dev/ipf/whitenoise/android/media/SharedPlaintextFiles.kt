package dev.ipf.whitenoise.android.media

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal const val ACCOUNT_SHARE_PREFIX = "account_"
private const val MAX_SHARE_EXTENSION_LENGTH = 12
private const val UNSIGNED_BYTE_MASK = 0xff
private val sharedPlaintextGeneration = AtomicLong()
private val sharedPlaintextMutex = Mutex()
private val pendingLiveWipes = mutableSetOf<String>()
private const val WIPE_PREFERENCES = "shared_plaintext_wipe"
private const val PENDING_WIPES = "pending_account_prefixes"

/** Keeps shared files in one bounded cache while omitting raw account identifiers from the paths. */
internal fun sharedPlaintextPrefix(accountRef: String): String {
    require(accountRef.isNotBlank())
    val digest = MessageDigest.getInstance("SHA-256").digest(accountRef.toByteArray())
    val owner = digest.joinToString("") { "%02x".format(it.toInt() and UNSIGNED_BYTE_MASK) }
    return "$ACCOUNT_SHARE_PREFIX${owner}_"
}

/** Keeps staging names below the filesystem limit while FileProvider supplies the original display name. */
internal fun sharedPlaintextSuffix(displayName: String): String {
    val extension = displayName.substringAfterLast('.', "")
    val safeExtension =
        extension.length in 1..MAX_SHARE_EXTENSION_LENGTH &&
            extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    return if (safeExtension) ".$extension" else ".bin"
}

/** Capture before loading share bytes, so a wipe can retire writes waiting on decryption or downloads. */
internal fun captureSharedPlaintextWrite(): Long = sharedPlaintextGeneration.get()

/** Serialize active staging with cleanup and reject work that started before cleanup. */
internal suspend fun <T> withSharedPlaintextWrite(
    generation: Long,
    write: suspend () -> T,
): T =
    withContext(Dispatchers.IO) {
        sharedPlaintextMutex.withLock {
            if (generation != sharedPlaintextGeneration.get()) throw CancellationException("shared plaintext was wiped")
            write()
        }
    }

/** Persists the cleanup obligation before native wipe can irreversibly remove the account. */
internal suspend fun recordSharedPlaintextWipe(
    context: Context,
    accountRef: String,
): Boolean =
    withContext(Dispatchers.IO) {
        sharedPlaintextMutex.withLock {
            val prefix = sharedPlaintextPrefix(accountRef)
            val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
            val pending = preferences.getStringSet(PENDING_WIPES, emptySet()).orEmpty() + prefix
            val saved = preferences.edit().putStringSet(PENDING_WIPES, pending).commit()
            if (saved) pendingLiveWipes += prefix
            saved
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
        pending.forEach { prefix -> finishSharedPlaintextWipe(context, prefix, onFailure) }
    }
}

/** Revokes FileProvider access before deleting app-owned plaintext, including unscoped copies from older builds. */
internal suspend fun wipeSharedPlaintext(
    context: Context,
    accountRef: String,
    onFailure: (String, Throwable) -> Unit,
) {
    sharedPlaintextMutex.withLock {
        val prefix = sharedPlaintextPrefix(accountRef)
        try {
            finishSharedPlaintextWipe(context, prefix, onFailure)
        } finally {
            pendingLiveWipes -= prefix
        }
    }
}

/** Retains the durable cleanup record when a file cannot be inspected, revoked, or deleted. */
private fun finishSharedPlaintextWipe(
    context: Context,
    prefix: String,
    onFailure: (String, Throwable) -> Unit,
) {
    sharedPlaintextGeneration.incrementAndGet()
    var failed = false
    deleteSharedPlaintext(context, prefix) { area, error ->
        failed = true
        onFailure(area, error)
    }
    if (!failed) {
        val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
        val pending = preferences.getStringSet(PENDING_WIPES, emptySet()).orEmpty()
        if (prefix in pending && !preferences.edit().putStringSet(PENDING_WIPES, pending - prefix).commit()) {
            onFailure(WIPE_PREFERENCES, IOException("could not record shared plaintext cleanup"))
        }
    }
}

/** Lists both private share roots and attempts every matching file even after an individual failure. */
private fun deleteSharedPlaintext(
    context: Context,
    prefix: String,
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
        entries
            .filter { belongsToWipedAccount(it.name, prefix) }
            .forEach { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.revokeUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure { onFailure(name, it) }
                runCatching {
                    check(file.delete() || !file.exists()) { "could not delete shared plaintext" }
                }.onFailure { onFailure(name, it) }
            }
    }
}

/** Old releases stored private shares without ownership. Public profile cards share the same cache. */
private fun belongsToWipedAccount(
    name: String,
    accountPrefix: String,
): Boolean =
    name.startsWith(accountPrefix) ||
        (!name.startsWith(ACCOUNT_SHARE_PREFIX) && !name.startsWith("profile_card_"))
