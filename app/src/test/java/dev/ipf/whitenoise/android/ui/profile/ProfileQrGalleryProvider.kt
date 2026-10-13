package dev.ipf.whitenoise.android.ui.profile

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

internal class ProfileQrGalleryProvider(
    private val testContext: Context,
) : ContentProvider() {
    var collection: Uri? = null
    var values: ContentValues? = null
    var completed: ContentValues? = null
    val output = ByteArrayOutputStream()

    @Volatile var insertCount = 0
    var updateResult = 1
    var beforeInsert: () -> Unit = {}

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

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

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        completed = values?.let(::ContentValues)
        return updateResult
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 1
}
