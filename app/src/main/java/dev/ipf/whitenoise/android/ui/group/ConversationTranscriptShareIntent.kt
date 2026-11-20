package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/** Shares the exported transcript under its readable filename with a temporary read grant. */
internal fun conversationTranscriptShareIntent(
    context: Context,
    file: java.io.File,
): Intent {
    val name = file.name.replaceFirst(Regex("^account_[0-9a-f]{64}_"), "")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, name)
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TITLE, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
