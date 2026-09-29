package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance

@Composable
internal fun rememberClipboardCanOfferPaste(clipboardManager: android.content.ClipboardManager?): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var canOfferPaste by remember(clipboardManager) {
        mutableStateOf(clipboardManager.canOfferTextPaste())
    }

    DisposableEffect(clipboardManager, lifecycleOwner) {
        if (clipboardManager == null) {
            onDispose { }
        } else {
            val refresh = { canOfferPaste = clipboardManager.canOfferTextPaste() }
            val listener =
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                    refresh()
                }
            // Clipboard callbacks can be missed while this app is in the background.
            val lifecycleObserver =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) refresh()
                }
            clipboardManager.addPrimaryClipChangedListener(listener)
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
            refresh()
            onDispose {
                clipboardManager.removePrimaryClipChangedListener(listener)
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            }
        }
    }

    return canOfferPaste
}

private fun android.content.ClipboardManager?.canOfferTextPaste(): Boolean =
    this?.primaryClipDescription?.hasMimeType(ClipboardPasteAffordance.TEXT_MIME_TYPE_PATTERN) ?: false

internal fun android.content.ClipboardManager.primaryClipPlainText(context: android.content.Context): String? =
    primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

internal fun clearSensitiveClipboard(context: Context) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        clipboard.clearPrimaryClip()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
