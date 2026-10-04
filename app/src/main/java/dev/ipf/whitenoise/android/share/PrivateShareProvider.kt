package dev.ipf.whitenoise.android.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Read-only same-UID bridge into no-backup intake files; no external grant is permitted. */
class PrivateShareProvider : ContentProvider() {
    private val files: PrivateShareFiles get() = PrivateShareFiles(requireNotNull(context))

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = files.metadata(uri)?.optString("mime")

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val metadata = files.metadata(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> metadata.getString("name")
                        OpenableColumns.SIZE -> metadata.getLong("size")
                        else -> null
                    }
                },
            )
        }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r" || files.metadata(uri) == null) throw FileNotFoundException("Unavailable share file")
        return ParcelFileDescriptor.open(requireNotNull(files.resolve(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
