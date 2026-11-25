package dev.ipf.whitenoise.android.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import kotlinx.coroutines.CancellationException

/** Writes public [text] to the clipboard without any feedback; false when the system refuses the clip. */
private fun copyToClipboard(
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
    private val reportNotCopied: () -> Unit,
) {
    /** Copies [text]; true when the clip was written and confirmed, false when refused and Not copied was shown. */
    fun copy(
        text: String,
        label: String = "plain text",
    ): Boolean = reportResult(copyToClipboard(context, label, text))

    /** Keeps Compose's selection clip, including any formatting and its label. */
    suspend fun copySelection(
        clipboard: Clipboard,
        clipEntry: ClipEntry?,
    ): Boolean {
        if (clipEntry == null) {
            clipboard.setClipEntry(null)
            return false
        }
        runCatching { clipboard.setClipEntry(clipEntry) }.getOrElse {
            if (it is CancellationException) throw it
            return reportResult(false)
        }
        return reportResult(true)
    }

    private fun reportResult(written: Boolean): Boolean {
        if (written) confirmCopied() else reportNotCopied()
        return written
    }
}

/**
 * Returns the shared [ClipboardCopier] for public text, the composable way to copy anything that is not a secret.
 * Secrets use [copySensitiveToClipboard] instead.
 */
@Composable
internal fun rememberClipboardCopier(): ClipboardCopier {
    val context = LocalContext.current
    val confirmCopied = rememberCopyConfirmation()
    val reportNotCopied = rememberTransientNoticeOf(R.string.not_copied, TransientNoticeKind.Information)
    return remember(context, confirmCopied, reportNotCopied) {
        ClipboardCopier(context, confirmCopied, reportNotCopied)
    }
}
