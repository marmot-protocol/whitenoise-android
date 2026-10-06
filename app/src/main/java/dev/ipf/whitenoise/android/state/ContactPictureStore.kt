package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.STORED_AVATAR_KEY_PREFIX
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** A private, immutable image selection; none of these values is a public profile URL or export input. */
internal data class ContactPictureReference(
    val owner: String,
    val contact: String,
    val fileName: String,
) {
    val cacheKey: String get() = "${STORED_AVATAR_KEY_PREFIX}private:$owner:$contact:$fileName"
}

/** An editor keeps existing bytes, clears the selection, or replaces it with normalized static pixels. */
internal sealed interface ContactPictureChange {
    data object Keep : ContactPictureChange

    data object Clear : ContactPictureChange

    class Replace(
        val bytes: ByteArray,
    ) : ContactPictureChange
}

/**
 * Account-private image files and the existing nickname/notes keys commit as one preference edit.
 * Immutable files land before their record; failed/stale writes never replace the previous selection.
 * A process dying between those operations leaves only an orphan, removed by the next maintenance pass.
 */
internal class ContactPictureStore(
    private val preferences: SharedPreferences,
    private val root: File,
) {
    /** Memory-backed preference lookup: composition never touches the filesystem. */
    fun reference(
        account: String?,
        contact: String,
    ): ContactPictureReference? =
        synchronized(lock) {
            val key = key(account, contact) ?: return@synchronized null
            val file = preferences.getString(key, null)?.takeIf { FILE_NAME.matches(it) } ?: return@synchronized null
            ContactPictureReference(owner(account!!), digest(contact.trim().lowercase(java.util.Locale.ROOT)), file)
        }

    /** Resolve only the current immutable file for this opaque account/contact, including replaced handles. */
    fun current(reference: ContactPictureReference): ContactPictureReference? =
        synchronized(lock) {
            preferences
                .getString(PREFIX + reference.owner + ":" + reference.contact, null)
                ?.takeIf(FILE_NAME::matches)
                ?.let { reference.copy(fileName = it) }
        }

    /** Hash comparison keeps active display ownership separate from notification reads for signed-in accounts. */
    fun belongsToAccount(
        reference: ContactPictureReference,
        account: String?,
    ): Boolean = account != null && reference.owner == owner(account)

    /** A cleared selection also overrides already-captured private notification pixels with the public fallback. */
    fun hasChoice(
        account: String,
        contact: String,
    ): Boolean =
        synchronized(lock) {
            key(account, contact)?.let(preferences::contains) == true
        }

    /** Reads only a currently selected, bounded file owned by this exact account/contact record. */
    fun read(reference: ContactPictureReference): ByteArray? =
        synchronized(lock) {
            val selected = preferences.getString(PREFIX + reference.owner + ":" + reference.contact, null)
            if (selected != reference.fileName || !FILE_NAME.matches(reference.fileName)) return@synchronized null
            val file = File(File(root, reference.owner), reference.fileName)
            if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@synchronized null
            file
                .inputStream()
                .use {
                    dev.ipf.whitenoise.android.media.MediaPipeline
                        .readBoundedBytes(it, MAX_BYTES)
                }?.takeIf(::validPicture)
        }

    /** Durably saves all three editor fields, checking the captured screen/account owner under the cleanup lock. */
    fun save(
        account: String,
        contact: String,
        nickname: String,
        notes: String,
        change: ContactPictureChange,
        canWrite: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            val pictureKey = key(account, contact) ?: return@synchronized false
            if (!canWrite()) return@synchronized false
            val directory = File(root, owner(account))
            var prepared: File? = null
            try {
                if (change is ContactPictureChange.Replace) {
                    require(validPicture(change.bytes))
                    check(directory.isDirectory || directory.mkdirs())
                    prepared = File(directory, UUID.randomUUID().toString() + ".png")
                    FileOutputStream(prepared).use { output ->
                        output.write(change.bytes)
                        output.fd.sync()
                    }
                }
                if (!canWrite()) return@synchronized false
                val previous =
                    listOf(
                        pictureKey,
                        requireNotNull(ContactNicknamePreferences.preferenceKey(account, contact)),
                        requireNotNull(ContactNotesPreferences.preferenceKey(account, contact)),
                    ).associateWith { preferences.getString(it, null) }
                val edit =
                    preferences
                        .edit()
                        .putString(
                            requireNotNull(ContactNicknamePreferences.preferenceKey(account, contact)),
                            ProfileSanitizer.displayName(nickname),
                        ).putString(
                            requireNotNull(ContactNotesPreferences.preferenceKey(account, contact)),
                            notes.trim().takeIf(String::isNotEmpty),
                        )
                when (change) {
                    ContactPictureChange.Keep -> Unit
                    ContactPictureChange.Clear -> edit.putString(pictureKey, CLEARED)
                    is ContactPictureChange.Replace -> edit.putString(pictureKey, checkNotNull(prepared).name)
                }
                if (!edit.commit()) {
                    restore(previous)
                    return@synchronized false
                }
                prepared = null
                cleanOrphans(directory)
                true
            } finally {
                prepared?.delete()
            }
        }

    /** Cleanup shares the commit lock; no suspended editor can resurrect this account after revocation. */
    fun clearAccount(account: String): Boolean =
        synchronized(lock) {
            val prefix = PREFIX + owner(account) + ":"
            val keys = preferences.all.keys.filter { it.startsWith(prefix) }
            val previous = keys.associateWith { preferences.getString(it, null) }
            val edit = preferences.edit()
            keys.forEach(edit::remove)
            if (!edit.commit()) {
                restore(previous)
                return@synchronized false
            }
            val directory = File(root, owner(account))
            !directory.exists() || directory.deleteRecursively()
        }

    /** Removes files left by replacement or interrupted writes without touching another storage domain. */
    fun cleanOrphans() =
        synchronized(lock) {
            root.listFiles()?.filter(File::isDirectory)?.forEach(::cleanOrphans)
        }

    private fun cleanOrphans(directory: File) {
        val prefix = PREFIX + directory.name + ":"
        val selected =
            preferences.all
                .filterKeys { it.startsWith(prefix) }
                .values
                .toSet()
        // The committed record is authoritative; unavailable orphan files can be retried on next startup.
        directory.listFiles()?.filter { it.name !in selected }?.forEach { it.delete() }
        if (directory.listFiles()?.isEmpty() == true) directory.delete()
    }

    /** A failed commit has already altered Android's memory map; restore it before releasing readers. */
    private fun restore(previous: Map<String, String?>) {
        val editor = preferences.edit()
        previous.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.commit()
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val PREFIX = "contact_picture:"
        private const val CLEARED = "cleared"
        private val FILE_NAME = Regex("[a-f0-9-]{36}\\.png")
        internal val lock = Any()

        /** Both encoded length and decoded geometry are checked before any image allocation. */
        private fun validPicture(bytes: ByteArray): Boolean {
            if (bytes.size !in 1..MAX_BYTES) return false
            val bounds =
                android.graphics.BitmapFactory
                    .Options()
                    .apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            return bounds.outMimeType == "image/png" &&
                bounds.outWidth == bounds.outHeight &&
                bounds.outWidth in 1..dev.ipf.whitenoise.android.media.PRIVATE_CONTACT_PICTURE_EDGE
        }

        /** Identical account/contact normalization to private nickname keys, with filenames containing neither. */
        private fun key(
            account: String?,
            contact: String,
        ): String? {
            ContactNicknamePreferences.preferenceKey(account, contact) ?: return null
            return PREFIX + owner(account!!) + ":" + digest(contact.trim().lowercase(java.util.Locale.ROOT))
        }

        private fun owner(account: String): String = digest(account.trim())

        private fun digest(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Account teardown cannot interleave any of the three private fields with an admitted asynchronous Save. */
internal fun clearContactPrivateDetails(
    preferences: SharedPreferences,
    account: String,
    pictures: ContactPictureStore,
): Boolean =
    synchronized(ContactPictureStore.lock) {
        val nicknamesChanged = ContactNicknamePreferences.clearAllForAccount(preferences, account)
        ContactNotesPreferences.clearAllForAccount(preferences, account)
        check(pictures.clearAccount(account))
        nicknamesChanged
    }
