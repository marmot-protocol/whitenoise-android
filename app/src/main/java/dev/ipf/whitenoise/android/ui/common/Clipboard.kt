package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Composable
internal fun rememberClipboardCanOfferPaste(clipboardManager: android.content.ClipboardManager?): Boolean {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var canOfferPaste by remember(clipboardManager) {
        mutableStateOf(clipboardManager.canOfferTextPaste())
    }

    DisposableEffect(clipboardManager) {
        if (clipboardManager == null) {
            onDispose { }
        } else {
            val refresh = { canOfferPaste = clipboardManager.canOfferTextPaste() }
            val listener =
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                    refresh()
                }
            clipboardManager.addPrimaryClipChangedListener(listener)
            refresh()
            onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
        }
    }
    // Background clipboard callbacks can be missed; query again once Android gives this window focus.
    LaunchedEffect(clipboardManager, windowFocused) {
        if (windowFocused) canOfferPaste = clipboardManager.canOfferTextPaste()
    }

    return canOfferPaste
}

private fun android.content.ClipboardManager?.canOfferTextPaste(): Boolean =
    this?.primaryClipDescription?.hasMimeType(ClipboardPasteAffordance.TEXT_MIME_TYPE_PATTERN) ?: false

internal fun ClipboardManager.primaryClipPlainText(context: Context): String? = primaryClip?.plainText(context)

internal fun ClipData.plainText(context: Context): String? =
    takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

/** Call only from an explicit Paste action; false requests the trusted system Paste fallback. */
internal fun android.content.ClipboardManager?.withPrimaryClipForPaste(onPaste: (ClipData) -> Unit): Boolean {
    val clip =
        try {
            this?.primaryClip
        } catch (_: SecurityException) {
            null
        } ?: return false
    // Readability, not validation success, decides whether another Paste action is necessary.
    onPaste(clip)
    return true
}

internal fun clearSensitiveClipboard(context: Context) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        clipboard.clearPrimaryClip()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

/** How long a copied secret may stay on the clipboard before the app removes it. */
internal const val SENSITIVE_CLIP_LIFETIME_MS = 60_000L

/** Unique WorkManager name of the one pending sensitive-clip clear. */
internal const val SENSITIVE_CLIP_CLEAR_WORK = "sensitive_clipboard_clear"

private const val COPIED_DIGEST_KEY = "copied_sha256"

/**
 * Copies a secret marked sensitive and removes it about [SENSITIVE_CLIP_LIFETIME_MS] later.
 *
 * WorkManager owns the deadline, so leaving the screen, a cached or killed process and a reboot do
 * not cancel it; Android may run it somewhat late. A later secret copy replaces the pending clear.
 * Only a SHA-256 digest of the text is stored with the work; at the deadline a readable clip that no
 * longer matches belongs to someone else and stays, while a clipboard the app cannot read is cleared
 * anyway.
 */
internal fun copySensitiveToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val appContext = context.applicationContext
    val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
    val clear =
        OneTimeWorkRequestBuilder<SensitiveClipboardClearWorker>()
            .setInitialDelay(SENSITIVE_CLIP_LIFETIME_MS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(COPIED_DIGEST_KEY to sensitiveClipDigest(text)))
            .build()
    WorkManager.getInstance(appContext).enqueueUniqueWork(SENSITIVE_CLIP_CLEAR_WORK, ExistingWorkPolicy.REPLACE, clear)
}

/** Removes a copied secret at its deadline; see [copySensitiveToClipboard]. */
class SensitiveClipboardClearWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val copied = inputData.getByteArray(COPIED_DIGEST_KEY)
        // Without the digest the clip's owner is unknown; clearing is safer than leaving a secret behind.
        if (copied == null) {
            clearSensitiveClipboard(applicationContext)
        } else {
            clearSensitiveClipIfUnchanged(applicationContext, copied)
        }
        return Result.success()
    }
}

private fun clearSensitiveClipIfUnchanged(
    context: Context,
    copied: ByteArray,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val current =
        try {
            clipboard.primaryClip
        } catch (_: SecurityException) {
            null
        }
    // Without focus Android may hide the clip; clearing then is safer than leaving a secret behind.
    val text =
        current
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
    if (current != null && (text == null || !MessageDigest.isEqual(sensitiveClipDigest(text), copied))) return
    clearSensitiveClipboard(context)
}

private fun sensitiveClipDigest(text: String): ByteArray {
    val sha256 = MessageDigest.getInstance("SHA-256")
    return sha256.digest(text.toByteArray(Charsets.UTF_8))
}
