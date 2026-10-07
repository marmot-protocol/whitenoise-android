package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import android.util.Log
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
 * Only preference reads and commits run under [lock]: bytes are written, read and deleted outside it,
 * so composition-time readers never wait on a flush.
 */
internal class ContactPictureStore(
    private val preferences: SharedPreferences,
    private val root: File,
    deleteDirectory: (File) -> Boolean = File::deleteRecursively,
) {
    private val bytes = ContactPictureBytes(preferences, deleteDirectory)

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
                .getString(CONTACT_PICTURE_PREFIX + reference.owner + ":" + reference.contact, null)
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
    fun read(reference: ContactPictureReference): ByteArray? {
        val record = CONTACT_PICTURE_PREFIX + reference.owner + ":" + reference.contact
        val selected =
            synchronized(lock) {
                FILE_NAME.matches(reference.fileName) && preferences.getString(record, null) == reference.fileName
            }
        // Committed files are immutable, so a removal racing this read only yields the public fallback.
        val file = File(File(root, reference.owner), reference.fileName)
        if (!selected || !file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        return file
            .inputStream()
            .use {
                dev.ipf.whitenoise.android.media.MediaPipeline
                    .readBoundedBytes(it, MAX_BYTES)
            }?.takeIf(::validPicture)
    }

    /**
     * Durably saves all three editor fields. Replacement bytes are written and synced outside [lock]; the
     * captured screen/account owner is re-checked and the record committed under it, and the files that
     * record no longer references are removed outside it again.
     */
    fun save(
        account: String,
        contact: String,
        nickname: String,
        notes: String,
        change: ContactPictureChange,
        canWrite: () -> Boolean,
    ): Boolean {
        val pictureKey = key(account, contact)
        if (pictureKey == null || !canWrite()) return false
        val directory = File(root, owner(account))
        var prepared: File? = null
        try {
            if (change is ContactPictureChange.Replace) {
                require(validPicture(change.bytes))
                prepared = bytes.prepare(directory, change.bytes)
            }
            val superseded =
                synchronized(lock) { commit(account, contact, pictureKey, nickname, notes, change, prepared, canWrite) }
            if (superseded != null) {
                prepared = null
                superseded.forEach(File::delete)
                bytes.removeIfEmpty(directory)
            }
            return superseded != null
        } finally {
            prepared?.let(bytes::discard)
        }
    }

    /** Commits removal of every picture record of [account]; callers hold [lock] with the other private fields. */
    fun revokeAccountLocked(account: String): Boolean {
        val prefix = CONTACT_PICTURE_PREFIX + owner(account) + ":"
        val keys = preferences.all.keys.filter { it.startsWith(prefix) }
        val previous = keys.associateWith { preferences.getString(it, null) }
        val edit = preferences.edit()
        keys.forEach(edit::remove)
        val committed = edit.commit()
        if (!committed) restore(previous)
        return committed
    }

    /**
     * Deletes a revoked owner's bytes without the lock. Failed byte removal is retried by orphan maintenance,
     * so an inaccessible file cannot abort the remaining sign-out or wipe after ownership was revoked.
     */
    fun removeAccountBytes(account: String) = bytes.removeOwner(File(root, owner(account)))

    /** Removes files left by replacement or interrupted writes without touching another storage domain. */
    fun cleanOrphans() {
        val directories = root.listFiles().orEmpty().filter(File::isDirectory)
        val leftovers = synchronized(lock) { directories.flatMap(bytes::unreferencedLocked) }
        leftovers.forEach(File::delete)
        directories.forEach(bytes::removeIfEmpty)
    }

    /**
     * Re-checks admission and commits the three records; callers hold [lock]. Returns the files the
     * committed record no longer references, or null when the owner expired or the durable commit failed.
     */
    @Suppress("LongParameterList") // One transaction covers every field the editor commits together.
    private fun commit(
        account: String,
        contact: String,
        pictureKey: String,
        nickname: String,
        notes: String,
        change: ContactPictureChange,
        prepared: File?,
        canWrite: () -> Boolean,
    ): List<File>? {
        if (!canWrite()) return null
        val nicknameKey = requireNotNull(ContactNicknamePreferences.preferenceKey(account, contact))
        val notesKey = requireNotNull(ContactNotesPreferences.preferenceKey(account, contact))
        val previous = listOf(pictureKey, nicknameKey, notesKey).associateWith { preferences.getString(it, null) }
        val edit =
            preferences
                .edit()
                .putString(nicknameKey, ProfileSanitizer.displayName(nickname))
                .putString(notesKey, notes.trim().takeIf(String::isNotEmpty))
        when (change) {
            ContactPictureChange.Keep -> Unit
            ContactPictureChange.Clear -> edit.putString(pictureKey, CLEARED)
            is ContactPictureChange.Replace -> edit.putString(pictureKey, checkNotNull(prepared).name)
        }
        val committed = edit.commit()
        if (committed) prepared?.let(bytes::releaseLocked) else restore(previous)
        return if (committed) bytes.unreferencedLocked(File(root, owner(account))) else null
    }

    /** A failed commit has already altered Android's memory map; restore it before releasing readers. */
    private fun restore(previous: Map<String, String?>) {
        val editor = preferences.edit()
        previous.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.commit()
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val CLEARED = "cleared"
        private const val LOG_TAG = "ContactPictureStore"
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
            val contactDigest = digest(contact.trim().lowercase(java.util.Locale.ROOT))
            return CONTACT_PICTURE_PREFIX + owner(account!!) + ":" + contactDigest
        }

        /** Matches nickname ownership normalization without exposing the account label in paths. */
        private fun owner(account: String): String = digest(account.trim())

        /** Stable SHA-256 names keep contact identifiers out of local filenames and display handles. */
        private fun digest(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Teardown reports a surviving picture record instead of aborting the remaining sign-out steps. */
        internal fun logSurvivingRecords() {
            Log.w(LOG_TAG, "private picture records survived teardown; orphan maintenance retries")
        }
    }
}

private const val CONTACT_PICTURE_PREFIX = "contact_picture:"

/** Byte-level file work for one store; only the methods named `Locked` expect [ContactPictureStore.lock]. */
private class ContactPictureBytes(
    private val preferences: SharedPreferences,
    private val deleteDirectory: (File) -> Boolean,
) {
    /** Files an admitted Save is still writing; sweeps skip them until their record commits or fails. */
    private val inFlight = mutableSetOf<File>()

    /** Registers the opaque target before any byte lands so a concurrent sweep cannot treat it as an orphan. */
    fun prepare(
        directory: File,
        bytes: ByteArray,
    ): File {
        val file = File(directory, UUID.randomUUID().toString() + ".png")
        synchronized(ContactPictureStore.lock) { inFlight += file }
        var written = false
        try {
            check(directory.isDirectory || directory.mkdirs())
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            written = true
        } finally {
            if (!written) discard(file)
        }
        return file
    }

    /** Forgets and deletes bytes whose record never committed. */
    fun discard(file: File) {
        synchronized(ContactPictureStore.lock) { inFlight -= file }
        file.delete()
    }

    /** A committed record now references [file], so sweeps may judge it by that record alone. */
    fun releaseLocked(file: File) {
        inFlight -= file
    }

    /** Files in one opaque owner directory that neither a committed record nor an admitted Save references. */
    fun unreferencedLocked(directory: File): List<File> {
        val prefix = CONTACT_PICTURE_PREFIX + directory.name + ":"
        val selected =
            preferences.all
                .filterKeys { it.startsWith(prefix) }
                .values
                .toSet()
        // The committed record is authoritative; unavailable orphan files can be retried on next startup.
        return directory.listFiles().orEmpty().filter { it.name !in selected && it !in inFlight }
    }

    /** Drops an owner directory once nothing committed or in flight still lives in it. */
    fun removeIfEmpty(directory: File) {
        synchronized(ContactPictureStore.lock) {
            if (inFlight.none { it.parentFile == directory } && directory.listFiles()?.isEmpty() == true) {
                directory.delete()
            }
        }
    }

    /** Deletes a revoked owner's bytes, sparing files an admitted Save is still writing. */
    fun removeOwner(directory: File) {
        if (!directory.exists()) return
        runCatching {
            val pending = synchronized(ContactPictureStore.lock) { inFlight.filter { it.parentFile == directory } }
            if (pending.isEmpty()) {
                deleteDirectory(directory)
            } else {
                directory
                    .listFiles()
                    .orEmpty()
                    .filter { it !in pending }
                    .forEach(File::delete)
            }
        }
    }
}

/** Revokes and removes one account's pictures in a single step for callers outside the shared teardown. */
internal fun ContactPictureStore.clearAccount(account: String): Boolean {
    val revoked = synchronized(ContactPictureStore.lock) { revokeAccountLocked(account) }
    if (revoked) removeAccountBytes(account)
    return revoked
}

/**
 * Account teardown cannot interleave any of the three private fields with an admitted asynchronous Save.
 * A failed durable picture commit is logged, never thrown: the remaining sign-out or wipe steps must run.
 */
internal fun clearContactPrivateDetails(
    preferences: SharedPreferences,
    account: String,
    pictures: ContactPictureStore,
): Boolean {
    val (nicknamesChanged, revoked) =
        synchronized(ContactPictureStore.lock) {
            val changed = ContactNicknamePreferences.clearAllForAccount(preferences, account)
            ContactNotesPreferences.clearAllForAccount(preferences, account)
            changed to pictures.revokeAccountLocked(account)
        }
    if (revoked) pictures.removeAccountBytes(account) else ContactPictureStore.logSurvivingRecords()
    return nicknamesChanged
}
