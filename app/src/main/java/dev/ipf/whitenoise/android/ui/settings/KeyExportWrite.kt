package dev.ipf.whitenoise.android.ui.settings

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

internal const val PRIVATE_KEY_EXPIRY_MILLIS = 30_000L

/** A confirmed native result held temporarily for preview or document delivery, with one original expiry. */
internal data class PendingKeyExport(
    val encrypted: Boolean,
    val content: String,
    val createdAtMillis: Long,
)

/** Whether a pending export has outlived the prototype's 30 s window. */
internal fun PendingKeyExport.isExpired(): Boolean {
    val age = SystemClock.elapsedRealtime() - createdAtMillis
    return age >= PRIVATE_KEY_EXPIRY_MILLIS
}

/** Remaining lifetime follows the native result when a preview is handed to the document picker. */
internal fun PendingKeyExport.remainingMillis(): Long {
    val age = SystemClock.elapsedRealtime() - createdAtMillis
    return (PRIVATE_KEY_EXPIRY_MILLIS - age).coerceAtLeast(0L)
}

/**
 * Writes a key export into the document the user picked; false when the provider cannot open or accept it.
 *
 * Opening, writing, flushing and closing a document can block on a slow or remote provider, so all of it runs on IO
 * and the main thread only receives the result. Cancellation before the provider is opened writes nothing.
 */
internal suspend fun writeKeyExport(
    content: String,
    openOutput: () -> OutputStream?,
): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            checkNotNull(openOutput()).bufferedWriter().use { it.write(content) }
        }.isSuccess
    }
