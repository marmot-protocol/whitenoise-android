package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle

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
