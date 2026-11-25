package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Writes public [text] to the clipboard without any feedback; false when the system refuses the clip. */
internal fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
): Boolean =
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }.isSuccess

/**
 * The one way a user action copies public text. It writes the clip and then confirms through the shared Copied
 * notice, which only older Android releases need. Secrets go through [copySensitiveToClipboard] instead.
 */
@Stable
internal class ClipboardCopier(
    private val context: Context,
    private val confirmCopied: () -> Unit,
) {
    /** Copies [text]; returns whether the clip was written. Only a written clip is confirmed. */
    fun copy(
        text: String,
        label: String = "",
    ): Boolean = copyToClipboard(context, label, text).also { if (it) confirmCopied() }
}

@Composable
internal fun rememberClipboardCopier(): ClipboardCopier {
    val context = LocalContext.current
    val confirmCopied = rememberCopyConfirmation()
    return remember(context, confirmCopied) { ClipboardCopier(context, confirmCopied) }
}
