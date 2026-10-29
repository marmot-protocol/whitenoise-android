package dev.ipf.whitenoise.android.notifications

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/** Explicit user-requested copies for Android's sound picker. Never changes a channel or system ringtone. */
internal object NotificationToneExport {
    private const val DIRECTORY = "Notifications/White Noise/"
    private val lock = Any()

    /** Runs on an I/O dispatcher. Only exact app-owned public copies may be reused. */
    fun addToAndroid(context: Context) {
        synchronized(lock) {
            NotificationTone.entries.forEach { tone -> add(context, tone) }
        }
    }

    // Provider writes can fail with checked or runtime exceptions; every failed new row needs cleanup.
    @Suppress("TooGenericExceptionCaught")
    private fun add(
        context: Context,
        tone: NotificationTone,
    ) {
        val resolver = context.contentResolver
        val expected = context.resources.openRawResource(tone.audio).use { it.readBytes() }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        existing(context, tone, expected)?.let { uri ->
            // Repair picker metadata too; byte identity alone does not imply discoverability.
            check(resolver.update(uri, publishedValues(context, tone), null, null) == 1)
            return
        }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, tone.filename)
                put(MediaStore.Audio.Media.TITLE, context.getString(tone.title))
                put(MediaStore.MediaColumns.MIME_TYPE, NotificationTone.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 0)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri: Uri = checkNotNull(resolver.insert(collection, values))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(expected) }
            check(checkNotNull(resolver.openInputStream(uri)).use { matches(it, expected) })
            check(resolver.update(uri, publishedValues(context, tone), null, null) == 1)
        } catch (failure: Exception) {
            // Only this newly inserted, still-pending row belongs to this failed attempt.
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    private fun existing(
        context: Context,
        tone: NotificationTone,
        expected: ByteArray,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection =
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val query =
            Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(context.packageName, DIRECTORY))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
        checkNotNull(resolver.query(collection, projection, query, null))
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val stem = Regex.escape(tone.filename.removeSuffix(".wav"))
                    if (name != tone.filename && !Regex("""$stem \([0-9]+\)\.wav""").matches(name)) continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    // Bounded comparison; a changed same-name file never becomes an accepted tone.
                    val matches = checkNotNull(resolver.openInputStream(uri)).use { matches(it, expected) }
                    if (matches) {
                        return uri
                    }
                }
            }
        return null
    }

    private fun matches(
        input: java.io.InputStream,
        expected: ByteArray,
    ): Boolean {
        val buffer = ByteArray(expected.size + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) throw java.io.IOException("No progress reading tone")
            count += read
        }
        return count == expected.size && buffer.copyOf(count).contentEquals(expected)
    }

    private fun publishedValues(
        context: Context,
        tone: NotificationTone,
    ): ContentValues =
        ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.TITLE, context.getString(tone.title))
            put(MediaStore.MediaColumns.MIME_TYPE, NotificationTone.mimeType)
        }
}
