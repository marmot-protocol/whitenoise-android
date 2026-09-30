package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance

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

internal fun android.content.ClipboardManager.primaryClipPlainText(context: android.content.Context): String? =
    primaryClip?.plainText(context)

internal fun ClipData.plainText(context: Context): String? =
    takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

/** Call only from an explicit Paste action; false requests the trusted system Paste fallback. */
internal fun android.content.ClipboardManager?.withPrimaryClipForPaste(onPaste: (ClipData) -> Unit): Boolean {
    val clip = try {
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
