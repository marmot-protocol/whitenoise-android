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

    /** Initializes no storage on provider creation; the private directory is resolved only when I/O is needed. */
    override fun onCreate(): Boolean = true

    /** Returns only validated private metadata; a missing original never advertises a stale type. */
    override fun getType(uri: Uri): String? = files.metadata(uri)?.optString("mime")

    /** Exposes bounded display-name/actual-size columns for an app-private original, without external URI grants. */
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

    /** Opens only read mode on a still-existing owned source; disappearance races surface as FileNotFoundException. */
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r" || files.metadata(uri) == null) throw FileNotFoundException("Unavailable share file")
        val file = files.resolve(uri) ?: throw FileNotFoundException("Unavailable share file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** This provider never accepts writes or creates source ownership through ContentResolver. */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException()

    /** Metadata mutation belongs to serialized private intake, not provider callers. */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    /** Lease reconciliation owns deletion; provider callers cannot remove another shelf/send's original. */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
