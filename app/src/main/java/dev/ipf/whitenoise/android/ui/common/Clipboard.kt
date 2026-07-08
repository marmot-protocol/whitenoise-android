package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance

@Composable
internal fun rememberClipboardCanOfferPaste(clipboardManager: android.content.ClipboardManager?): Boolean {
    var canOfferPaste by remember(clipboardManager) {
        mutableStateOf(clipboardManager.canOfferTextPaste())
    }

    DisposableEffect(clipboardManager) {
        if (clipboardManager == null) {
            onDispose { }
        } else {
            val listener =
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                    canOfferPaste = clipboardManager.canOfferTextPaste()
                }
            clipboardManager.addPrimaryClipChangedListener(listener)
            canOfferPaste = clipboardManager.canOfferTextPaste()
            onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
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
