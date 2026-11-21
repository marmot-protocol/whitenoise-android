package dev.ipf.whitenoise.android.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

private const val PENDING_REVOCATIONS = "pending_share_revocations"
private val shareGrantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/** Revokes a staged file's grant, keeping its URI for a retry if Android rejects the revocation. */
internal fun revokeSharedPlaintextGrant(
    context: Context,
    file: File,
    area: String,
    onFailure: (String, Throwable) -> Unit,
) {
    val uri =
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }
            .onFailure { onFailure(area, it) }
            .getOrNull() ?: return
    runCatching { context.revokeUriPermission(uri, shareGrantFlags) }.onFailure { failure ->
        val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
        val pending = preferences.getStringSet(PENDING_REVOCATIONS, emptySet()).orEmpty() + uri.toString()
        if (!preferences.edit().putStringSet(PENDING_REVOCATIONS, pending).commit()) {
            onFailure(WIPE_PREFERENCES, IOException("could not persist share grant revocation"))
        }
        onFailure(area, failure)
    }
}

/** Retries grants even if their backing files were already removed. Caller holds the shared plaintext mutex. */
internal fun retrySharedPlaintextGrantRevocations(
    context: Context,
    onFailure: (String, Throwable) -> Unit,
) {
    val preferences = context.getSharedPreferences(WIPE_PREFERENCES, Context.MODE_PRIVATE)
    val pending = preferences.getStringSet(PENDING_REVOCATIONS, emptySet()).orEmpty()
    val remaining =
        pending.filterTo(mutableSetOf()) { path ->
            runCatching { context.revokeUriPermission(Uri.parse(path), shareGrantFlags) }
                .onFailure { onFailure(WIPE_PREFERENCES, it) }
                .isFailure
        }
    if (remaining != pending && !preferences.edit().putStringSet(PENDING_REVOCATIONS, remaining).commit()) {
        onFailure(WIPE_PREFERENCES, IOException("could not record share grant revocations"))
    }
}
