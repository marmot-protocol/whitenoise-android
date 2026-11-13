package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.os.HandlerCompat
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance
import java.security.MessageDigest

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

private val sensitiveClipHandler by lazy { Handler(Looper.getMainLooper()) }
private val sensitiveClipToken = Any()

/**
 * Copies a secret marked sensitive and removes it after [SENSITIVE_CLIP_LIFETIME_MS].
 *
 * The clear is posted on the main looper with the application context, so leaving the screen or
 * backgrounding the app does not cancel it. A later secret copy replaces the pending clear. Only a
 * digest of the text is kept; at the deadline a readable clip that no longer matches belongs to
 * someone else and stays, while a clipboard the app cannot read is cleared anyway.
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
    val copied = sensitiveClipDigest(text)
    sensitiveClipHandler.removeCallbacksAndMessages(sensitiveClipToken)
    HandlerCompat.postDelayed(
        sensitiveClipHandler,
        { clearSensitiveClipIfUnchanged(appContext, copied) },
        sensitiveClipToken,
        SENSITIVE_CLIP_LIFETIME_MS,
    )
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
