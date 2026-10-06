package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Real private files and SharedPreferences exercise durable ownership, atomic editing and cleanup. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ContactPictureStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("private-picture-test", Context.MODE_PRIVATE)
    private val root get() = File(context.noBackupFilesDir, "pictures-test")
    private val store get() = ContactPictureStore(preferences, root)

    @Test fun normalizedOwnerAndContactPersistWithoutSharingPictures() {
        save(" personal ", " AA ", Color.RED)
        save("work", "aa", Color.BLUE)
        val restored = ContactPictureStore(preferences, root)
        val a = checkNotNull(restored.reference("personal", "aa"))
        val b = checkNotNull(restored.reference("work", "AA"))
        assertNotEquals(a, b)
        assertArrayEquals(contactPicturePng(Color.RED), restored.read(a))
        assertArrayEquals(contactPicturePng(Color.BLUE), restored.read(b))
        assertNull(restored.reference("personal", "bb"))
        assertFalse(root.walkTopDown().any { it.name.contains("personal") || it.name.contains("work") })
        assertFalse(a.cacheKey.contains(root.absolutePath))
    }

    @Test fun replacementAndClearCommitAllFieldsAndLeaveNoOrphan() {
        save("a", "contact", Color.RED)
        val old = checkNotNull(store.reference("a", "contact"))
        assertTrue(
            store.save(
                "a",
                "contact",
                " New name ",
                " New note ",
                ContactPictureChange.Replace(contactPicturePng(Color.BLUE)),
            ) { true },
        )
        val current = checkNotNull(store.reference("a", "contact"))
        assertNotEquals(old, current)
        assertNull(store.read(old))
        assertEquals(1, root.walkTopDown().count(File::isFile))
        assertEquals("New name", ContactNicknamePreferences.readNickname(preferences, "a", "contact"))
        assertEquals("New note", ContactNotesPreferences.readNotes(preferences, "a", "contact"))
        assertTrue(store.save("a", "contact", "", "", ContactPictureChange.Clear) { true })
        assertNull(store.reference("a", "contact"))
        assertNull(ContactNicknamePreferences.readNickname(preferences, "a", "contact"))
        assertEquals(0, root.walkTopDown().count(File::isFile))
    }

    @Test fun staleOwnerAndInvalidPixelsPreserveAllPreviousFields() {
        save("a", "contact", Color.RED)
        val old = store.reference("a", "contact")
        var checks = 0
        assertFalse(
            store.save(
                "a",
                "contact",
                "Changed",
                "Changed",
                ContactPictureChange.Replace(contactPicturePng(Color.BLUE)),
            ) { ++checks == 1 },
        )
        assertEquals(old, store.reference("a", "contact"))
        assertEquals("Saved name", ContactNicknamePreferences.readNickname(preferences, "a", "contact"))
        assertThrows(IllegalArgumentException::class.java) {
            store.save("a", "contact", "Changed", "Changed", ContactPictureChange.Replace(byteArrayOf(1))) { true }
        }
        assertEquals(1, root.walkTopDown().count(File::isFile))
    }

    @Test fun cleanupIsDurableAndPrefixSafeAndRemovesInterruptedFiles() {
        save("a", "contact", Color.RED)
        save("ab", "contact", Color.BLUE)
        val removed = checkNotNull(store.reference("a", "contact"))
        val retained = checkNotNull(store.reference("ab", "contact"))
        File(File(root, removed.owner), "interrupted.tmp").writeBytes(byteArrayOf(1))
        store.cleanOrphans()
        assertEquals(2, root.walkTopDown().count(File::isFile))
        assertTrue(store.clearAccount("a"))
        val restored = ContactPictureStore(preferences, root)
        assertNull(restored.reference("a", "contact"))
        assertNull(restored.read(removed))
        assertArrayEquals(contactPicturePng(Color.BLUE), restored.read(retained))
        assertEquals(1, root.walkTopDown().count(File::isFile))
    }

    @Test fun missingCorruptAndOversizedFilesDoNotEraseTheRecord() {
        save("a", "contact", Color.RED)
        val ref = checkNotNull(store.reference("a", "contact"))
        val file = File(File(root, ref.owner), ref.fileName)
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read(ref))
        file.writeBytes(ByteArray(ContactPictureStore.MAX_BYTES + 1))
        assertNull(store.read(ref))
        file.delete()
        assertNull(store.read(ref))
        assertEquals(ref, store.reference("a", "contact"))
    }

    @Test fun failedDiskCommitRestoresAllFieldsAndRetainsTheOldImage() {
        save("a", "contact", Color.RED)
        val ref = store.reference("a", "contact")
        val failing =
            ContactPictureStore(
                object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor = FailedEditor(preferences.edit())
                },
                root,
            )
        assertFalse(
            failing.save(
                "a",
                "contact",
                "Changed",
                "Changed",
                ContactPictureChange.Replace(contactPicturePng(Color.BLUE)),
            ) { true },
        )
        assertEquals(ref, store.reference("a", "contact"))
        assertEquals("Saved name", ContactNicknamePreferences.readNickname(preferences, "a", "contact"))
        assertEquals("Saved note", ContactNotesPreferences.readNotes(preferences, "a", "contact"))
        assertEquals(1, root.walkTopDown().count(File::isFile))
        assertFalse(failing.clearAccount("a"))
        assertEquals(ref, store.reference("a", "contact"))
    }

    /** A Save already past admission must finish before teardown removes nickname, notes and picture together. */
    @Test fun cleanupWaitsForAnAdmittedSaveAndRemovesAllThreeFields() {
        // Resolve Robolectric's instrumented helper before worker scheduling enters the timed section.
        clearContactPrivateDetails(preferences, "unused", store)
        val ready = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val checks =
            java.util.concurrent.atomic
                .AtomicInteger()
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val save =
                executor.submit<Boolean> {
                    store.save(
                        "a",
                        "contact",
                        "Late name",
                        "Late note",
                        ContactPictureChange.Replace(contactPicturePng(Color.RED)),
                    ) {
                        if (checks.incrementAndGet() == 2) {
                            ready.countDown()
                            check(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        }
                        true
                    }
                }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val cleanupStarted = java.util.concurrent.CountDownLatch(1)
            val cleanupThread =
                java.util.concurrent.atomic
                    .AtomicReference<Thread>()
            val cleanup =
                executor.submit<Boolean> {
                    cleanupThread.set(Thread.currentThread())
                    cleanupStarted.countDown()
                    clearContactPrivateDetails(preferences, "a", store)
                }
            assertTrue(cleanupStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(5)
            while (cleanupThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, cleanupThread.get().state)
            release.countDown()
            assertTrue(save.get(5, java.util.concurrent.TimeUnit.SECONDS))
            cleanup.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNull(store.reference("a", "contact"))
            assertNull(ContactNicknamePreferences.readNickname(preferences, "a", "contact"))
            assertNull(ContactNotesPreferences.readNotes(preferences, "a", "contact"))
            assertEquals(0, root.walkTopDown().count(File::isFile))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun save(
        account: String,
        contact: String,
        color: Int,
    ) {
        assertTrue(
            store.save(
                account,
                contact,
                "Saved name",
                "Saved note",
                ContactPictureChange.Replace(contactPicturePng(color)),
            ) { true },
        )
    }

    private class FailedEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegate {
        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor {
            delegate.putString(key, value)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }

        override fun commit(): Boolean {
            delegate.commit()
            return false
        }
    }
}

/** Small deterministic normalized pixels shared by store, loader and UI regression fixtures. */
internal fun contactPicturePng(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }
}
