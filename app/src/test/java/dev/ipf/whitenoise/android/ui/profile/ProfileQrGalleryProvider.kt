package dev.ipf.whitenoise.android.ui.profile

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

/** An in-memory MediaStore stand-in that records the pending insert, its bytes and its completion update. */
internal class ProfileQrGalleryProvider(
    private val testContext: Context,
) : ContentProvider() {
    var collection: Uri? = null
    var values: ContentValues? = null

    @Volatile var completed: ContentValues? = null
    val output = ByteArrayOutputStream()

    @Volatile var insertCount = 0
    var updateResult = 1
    var beforeInsert: () -> Unit = {}

    /** The provider has no setup of its own. */
    override fun onCreate() = true

    /** Gallery rows are never typed by this fixture. */
    override fun getType(uri: Uri): String? = null

    /** Nothing in the export flow reads rows back. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    /** Records the pending row, runs the test hook and exposes an in-memory output stream for its bytes. */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        insertCount++
        beforeInsert()
        collection = uri
        this.values = values?.let(::ContentValues)
        val destination = Uri.withAppendedPath(uri, "1")
        shadowOf(testContext.contentResolver).registerOutputStreamSupplier(destination) { output }
        return destination
    }

    /** Records the completion values and reports the configured number of updated rows. */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        completed = values?.let(::ContentValues)
        return updateResult
    }

    /** Accepts cleanup of a failed pending row. */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 1
}
