package dev.ipf.whitenoise.android.notifications

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Exercises actual ContentResolver export, byte reuse and cleanup beneath a local MediaStore fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NotificationToneExportTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun legacyOwnedCopiesKeepTheirUrisAndReceiveFriendlyNames() {
        val provider = Store(context)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        NotificationToneExport.addToAndroid(context)
        val originalUris = provider.rows.keys.toList()
        provider.rows.values.zip(NotificationTone.entries).forEach { (row, tone) ->
            row.values.put(MediaStore.MediaColumns.DISPLAY_NAME, "whitenoise_${tone.token} (1).wav")
        }
        NotificationToneExport.addToAndroid(context)
        assertEquals(originalUris, provider.rows.keys.toList())
        assertEquals(2, provider.insertCount)
        assertEquals(0, provider.deleteCount)
        provider.rows.values.zip(NotificationTone.entries).forEach { (row, tone) ->
            assertEquals(tone.filename, row.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        }
    }

    @Test
    fun repeatedImportReusesExactOwnedBytesAndRepairsPickerMetadata() {
        val provider = Store(context)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        NotificationToneExport.addToAndroid(context)
        assertEquals(2, provider.insertCount)
        provider.rows.values.forEach { it.values.put(MediaStore.Audio.Media.IS_NOTIFICATION, 0) }
        NotificationToneExport.addToAndroid(context)
        assertEquals(2, provider.insertCount)
        assertEquals(0, provider.deleteCount)
        provider.rows.values.forEach {
            assertEquals(1, it.values.getAsInteger(MediaStore.Audio.Media.IS_NOTIFICATION))
            assertEquals(0, it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertEquals(NotificationTone.mimeType, it.values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        }
    }

    @Test
    fun retryIncludesCompletePendingRowsInsteadOfAddingDuplicates() {
        val provider = Store(context)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        NotificationToneExport.addToAndroid(context)
        provider.rows.values.forEach { it.values.put(MediaStore.MediaColumns.IS_PENDING, 1) }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        provider
            .query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "owner and directory",
                arrayOf(context.packageName, "Notifications/White Noise/"),
                null,
            ).use {
                assertEquals(0, it.count)
            }
        NotificationToneExport.addToAndroid(context)
        assertEquals(2, provider.insertCount)
        assertTrue(provider.pendingModes.all { it == MediaStore.MATCH_INCLUDE })
        provider.rows.values.forEach { assertEquals(0, it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)) }
    }

    @Test
    fun failedPublishDeletesOnlyTheNewPendingRow() {
        val provider = Store(context).apply { failPublish = true }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        assertThrows(IllegalStateException::class.java) { NotificationToneExport.addToAndroid(context) }
        assertEquals(1, provider.insertCount)
        assertEquals(1, provider.deleteCount)
        assertTrue(provider.rows.isEmpty())
    }

    @Test
    fun foreignAndChangedSameNameCopiesAreNeverOverwritten() {
        val provider = Store(context)
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        NotificationToneExport.addToAndroid(context)
        val old = provider.rows.values.first()
        old.output.reset()
        old.output.write(byteArrayOf(9, 8, 7))
        val oldBytes = old.output.toByteArray()
        val foreign = provider.rows.values.last()
        foreign.values.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, "another.application")
        foreign.values.put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
        NotificationToneExport.addToAndroid(context)
        assertEquals(4, provider.insertCount)
        assertEquals(0, foreign.values.getAsInteger(MediaStore.Audio.Media.IS_NOTIFICATION))
        assertTrue(oldBytes.contentEquals(old.output.toByteArray()))
        assertEquals(0, provider.deleteCount)
        NotificationToneExport.addToAndroid(context)
        assertEquals(4, provider.insertCount)
        assertTrue(provider.selections.all { it.contains(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) })
    }

    private data class Row(
        val values: ContentValues,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    private class Store(
        private val app: Context,
    ) : ContentProvider() {
        val rows = linkedMapOf<Uri, Row>()
        val selections = mutableListOf<String>()
        val pendingModes = mutableListOf<Int>()
        var insertCount = 0
        var deleteCount = 0
        var failPublish = false

        override fun onCreate() = true

        override fun getType(uri: Uri) = NotificationTone.mimeType

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            queryRows(
                requireNotNull(projection),
                requireNotNull(selection),
                requireNotNull(selectionArgs),
                false,
            )

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            val args = requireNotNull(queryArgs)
            val pending = args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_DEFAULT)
            pendingModes += pending
            return queryRows(
                requireNotNull(projection),
                requireNotNull(args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)),
                requireNotNull(args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)),
                pending == MediaStore.MATCH_INCLUDE,
            )
        }

        private fun queryRows(
            projection: Array<out String>,
            selection: String,
            args: Array<out String>,
            includePending: Boolean,
        ): Cursor {
            selections += selection
            assertEquals(app.packageName, args[0])
            assertEquals("Notifications/White Noise/", args[1])
            return MatrixCursor(projection).apply {
                rows.forEach { (id, row) ->
                    if (
                        row.values.getAsString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) == args[0] &&
                        (includePending || row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) != 1)
                    ) {
                        addRow(
                            arrayOf(
                                id.lastPathSegment!!.toLong(),
                                row.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME),
                            ),
                        )
                    }
                }
            }
        }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri {
            val target = Uri.withAppendedPath(uri, (++insertCount).toString())
            val row =
                Row(
                    ContentValues(requireNotNull(values)).apply {
                        put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, app.packageName)
                    },
                )
            val originalName = row.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            var name = originalName
            var suffix = 1
            while (rows.values.any { it.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == name }) {
                name = originalName.removeSuffix(".wav") + " (${suffix++}).wav"
            }
            row.values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            rows[target] = row
            shadowOf(app.contentResolver).registerOutputStreamSupplier(target) { row.output }
            shadowOf(app.contentResolver).registerInputStreamSupplier(target) {
                ByteArrayInputStream(row.output.toByteArray())
            }
            return target
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            if (failPublish) return 0
            rows.getValue(uri).values.putAll(requireNotNull(values))
            return 1
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            deleteCount++
            rows.remove(uri)
            return 1
        }
    }
}
