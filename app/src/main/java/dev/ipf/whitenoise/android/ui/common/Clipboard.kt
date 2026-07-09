package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance

@Composable
internal fun rememberClipboardCanOfferPaste(clipboardManager: ClipboardManager?): Boolean {
    var canOfferPaste by remember(clipboardManager) {
        mutableStateOf(clipboardManager.canOfferTextPaste())
    }

    DisposableEffect(clipboardManager) {
        if (clipboardManager == null) {
            onDispose { }
        } else {
            val listener =
                ClipboardManager.OnPrimaryClipChangedListener {
                    canOfferPaste = clipboardManager.canOfferTextPaste()
                }
            clipboardManager.addPrimaryClipChangedListener(listener)
            canOfferPaste = clipboardManager.canOfferTextPaste()
            onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
        }
    }

    return canOfferPaste
}

private fun ClipboardManager?.canOfferTextPaste(): Boolean = this?.primaryClipDescription?.hasMimeType(ClipboardPasteAffordance.TEXT_MIME_TYPE_PATTERN) ?: false

internal fun ClipboardManager.primaryClipPlainText(context: Context): String? =
    primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

internal fun ClipboardManager.clearSensitivePrimaryClip() {
    clearSensitivePrimaryClipForSdk(
        sdkInt = Build.VERSION.SDK_INT,
        clearPrimaryClip = { clearPrimaryClip() },
        replaceWithEmptyClip = { setPrimaryClip(ClipData.newPlainText("", "")) },
    )
}

internal fun clearSensitivePrimaryClipForSdk(
    sdkInt: Int,
    clearPrimaryClip: () -> Unit,
    replaceWithEmptyClip: () -> Unit,
) {
    if (sdkInt >= Build.VERSION_CODES.P) {
        clearPrimaryClip()
    } else {
        // Pre-P devices lack clearPrimaryClip(); replace with an empty clip so
        // the imported nsec no longer remains in clipboard history.
        replaceWithEmptyClip()
    }
}
