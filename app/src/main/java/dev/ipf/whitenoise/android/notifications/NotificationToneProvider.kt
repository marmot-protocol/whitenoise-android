package dev.ipf.whitenoise.android.notifications

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Public, immutable audio only; no files, account data, grants or caller-controlled paths. */
class NotificationToneProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = tone(uri)?.let { NotificationTone.mimeType }

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only notification tone")
        val tone = tone(uri) ?: throw FileNotFoundException("Unknown notification tone")
        return requireNotNull(context).resources.openRawResourceFd(tone.audio)
            ?: throw FileNotFoundException("Unavailable notification tone")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val tone = tone(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = openAssetFile(uri, "r").use { it.length }
        return MatrixCursor(columns).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> tone.filename
                        android.provider.MediaStore.Audio.Media.TITLE -> requireNotNull(context).getString(tone.title)
                        OpenableColumns.SIZE -> size
                        else -> null
                    }
                },
            )
        }
    }

    private fun tone(uri: Uri): NotificationTone? = NotificationTone.resolve(requireNotNull(context), uri)

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read only")
}
