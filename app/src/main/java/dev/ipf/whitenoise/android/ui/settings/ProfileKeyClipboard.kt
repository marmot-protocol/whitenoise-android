package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import dev.ipf.whitenoise.android.ui.common.copySensitiveToClipboard

/** Restores the deliberate raw-key share transport, marking both intent and clipboard payload sensitive. */
internal fun sharePrivateKey(
    context: Context,
    title: String,
    content: String,
): Boolean =
    runCatching {
        val clip =
            ClipData.newPlainText(title, content).apply {
                description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(ClipDescription.EXTRA_IS_SENSITIVE, true)
                clipData = clip
            }
        context.startActivity(Intent.createChooser(intent, title))
    }.isSuccess

/** Copies public [text]; secrets use [copySensitiveToClipboard], which marks them and clears them later. */
internal fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
