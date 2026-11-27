package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Color
import android.util.AtomicFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistentDisplayAvatarsTest {
    private val fixture = Files.createTempDirectory("display-avatars-test").toFile()
    private val root = File(fixture, "entries")
    private val keyFile = File(fixture, "wrapped")
    private val wrappingKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val key = DisplayAvatarKey("alice", "chat", "target", "ref", 1uL)
    private val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    @After
    fun cleanUp() {
        bitmap.recycle()
        fixture.deleteRecursively()
    }

    @Test
    fun aReopenedPackSuppliesPixelsOnlyForCurrentSelections() {
        val writer = cache()
        writer.write(key, DisplayAvatarPixels(bitmap, true), writer.generation(key.accountRef))
        writer.writeStartupPack(key.accountRef, listOf(key), writer.generation(key.accountRef))
        val reader = cache()
        val replaced = key.copy(revision = 2uL)
        val result = reader.readStartupPack(key.accountRef, listOf(key, replaced), reader.generation(key.accountRef))

        assertEquals(setOf(key), result.keys)
        val pixels = result.getValue(key)
        assertEquals(true, pixels.animated)
        assertEquals(Color.MAGENTA, pixels.bitmap.getPixel(4, 4))
        pixels.bitmap.recycle()
        assertNull(reader.read(replaced, reader.generation(key.accountRef)))
    }

    @Test
    fun clearingOneAccountRetiresLateWritesAndPreservesOtherAccounts() {
        val cache = cache()
        val other = key.copy(accountRef = "bob")
        val pending = cache.generation(key.accountRef)
        val otherPending = cache.generation(other.accountRef)
        val pixels = DisplayAvatarPixels(bitmap, null)
        cache.write(key, pixels, pending)
        cache.write(other, pixels, otherPending)
        assertTrue(cache.clearAccount(key.accountRef))
        cache.write(key, pixels, pending)

        assertNull(cache.read(key, cache.generation(key.accountRef)))
        val surviving = checkNotNull(cache.read(other, otherPending))
        assertEquals(Color.MAGENTA, surviving.bitmap.getPixel(4, 4))
        surviving.bitmap.recycle()
    }

    @Test
    fun aDamagedWrappedKeyDropsTheOldDerivativesAndRecovers() {
        val writer = cache()
        val pixels = DisplayAvatarPixels(bitmap, false)
        writer.write(key, pixels, writer.generation(key.accountRef))
        keyFile.writeBytes(byteArrayOf(1))
        val reopened = cache()

        assertNull(reopened.read(key, reopened.generation(key.accountRef)))
        assertEquals(0L, reopened.residentBytes())
        reopened.write(key, pixels, reopened.generation(key.accountRef))
        val repairedCache = cache()
        val repaired = checkNotNull(repairedCache.read(key, repairedCache.generation(key.accountRef)))
        assertEquals(false, repaired.animated)
        repaired.bitmap.recycle()
    }

    @Test
    fun retiringAnOldRevisionPreservesItsReplacement() {
        val cache = cache()
        val replacement = key.copy(revision = 2uL)
        val old = cache.generation(key.accountRef)
        val pixels = DisplayAvatarPixels(bitmap, false)
        cache.write(key, pixels, old)
        cache.write(replacement, pixels, old)

        cache.retirePending(key.accountRef)
        cache.evictSelections(listOf(key))

        cache.write(key, pixels, old)
        assertNull(cache.read(key, cache.generation(key.accountRef)))
        checkNotNull(cache.read(replacement, cache.generation(key.accountRef))).bitmap.recycle()
    }

    @Test
    fun copiesBetweenAccountDirectoriesCannotSatisfyAnExactRead() {
        val cache = cache()
        val other = key.copy(accountRef = "bob")
        val generation = cache.generation(key.accountRef)
        val pixels = DisplayAvatarPixels(bitmap, false)
        cache.write(key, pixels, generation)
        val first = root.walkTopDown().single { it.extension == "dav1" }
        cache.write(other, pixels, cache.generation(other.accountRef))
        val second = root.walkTopDown().single { it.extension == "dav1" && it != first }
        first.copyTo(second, overwrite = true)

        assertNull(cache.read(other, cache.generation(other.accountRef)))
        checkNotNull(cache.read(key, generation)).bitmap.recycle()
    }

    @Test
    fun acceptedAccountInventoryCleansUpAnInterruptedWipe() {
        val cache = cache()
        val other = key.copy(accountRef = "bob")
        val generation = cache.generation(key.accountRef)
        val pixels = DisplayAvatarPixels(bitmap, false)
        cache.write(key, pixels, generation)
        cache.write(other, pixels, cache.generation(other.accountRef))

        cache.retainAccounts(setOf(other.accountRef), stillCurrent = { false })
        assertTrue(cache.hasEntry(key))
        cache.retainAccounts(setOf(other.accountRef), stillCurrent = { true })

        assertNull(cache.read(key, cache.generation(key.accountRef)))
        assertTrue(cache.hasEntry(other))
    }

    @Test
    fun oversizedPixelsAreScaledWithoutChangingTheOriginal() {
        val original = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        try {
            original.eraseColor(Color.MAGENTA)
            val encoded = checkNotNull(DisplayAvatarCodec.encode(DisplayAvatarPixels(original, null)))
            val display = checkNotNull(DisplayAvatarCodec.decode(encoded))
            assertEquals(512, original.width)
            assertEquals(256, display.bitmap.width)
            assertEquals(128, display.bitmap.height)
            assertEquals(Color.MAGENTA, display.bitmap.getPixel(4, 4))
            display.bitmap.recycle()
        } finally {
            original.recycle()
        }
    }

    private fun cache(): PersistentDisplayAvatars =
        PersistentDisplayAvatars(
            root,
            EnvelopeCacheKeyProvider(AtomicFile(keyFile), { wrappingKey }, {}, root::deleteRecursively),
        )
}
