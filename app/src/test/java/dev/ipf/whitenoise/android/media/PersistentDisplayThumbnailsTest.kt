package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** Exercises authenticated derivative storage, without a native or network boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistentDisplayThumbnailsTest {
    private val directory = Files.createTempDirectory("display-thumbnail-test").toFile()
    private val source = DisplayThumbnailSource("account:chat:message:0", 7uL, "a".repeat(64))
    private val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    /** Removes only this test's private fixture. */
    @After
    fun cleanUp() {
        bitmap.recycle()
        directory.deleteRecursively()
    }

    /** A small source retains its decode ceiling after restart, without being upscaled. */
    @Test
    fun restartRetainsPixelsAndDecodeProvenance() {
        val writer = cache()
        writer.write(source, MediaThumbnail(bitmap, 768), writer.captureGeneration())
        val reader = cache()
        val result = checkNotNull(reader.read(source, 512, reader.captureGeneration()))
        assertEquals(768, result.maxEdgePx)
        assertEquals(24, result.bitmap.width)
        assertEquals(16, result.bitmap.height)
        assertEquals(Color.MAGENTA, result.bitmap.getPixel(3, 3))
        assertNull(reader.read(source, 1024, reader.captureGeneration()))
    }

    /** Account, attachment index, source epoch and ciphertext replacement cannot reuse old pixels. */
    @Test
    fun identitiesCannotAlias() {
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 256), cache.captureGeneration())
        listOf(
            source.copy(attachmentKey = "other:chat:message:0"),
            source.copy(attachmentKey = "account:chat:message:1"),
            source.copy(sourceEpoch = 8uL),
            source.copy(ciphertextTag = "b".repeat(64)),
        ).forEach { assertNull(cache.read(it, 128, cache.captureGeneration())) }
    }

    /** Every explicit invalidation rejects a producer that began before it, even when no file existed yet. */
    @Test
    fun removalCannotBeUndoneByLateFirstPublication() {
        val cache = cache()
        val removals: List<() -> Unit> = listOf(
            { cache.remove(source.attachmentKey) },
            { cache.removeByCiphertextTags(setOf(source.ciphertextTag)) },
            { cache.clear() },
        )
        removals.forEach { remove ->
            val pending = cache.captureGeneration()
            remove()
            cache.write(source, MediaThumbnail(bitmap, 256), pending)
            assertNull(cache.read(source, 128, cache.captureGeneration()))
        }
    }

    /** Persisted tags and enumerable size slots survive a restart; no loaded-message index is required. */
    @Test
    fun expiryAndRemovalDeleteAllSizesAfterRestart() {
        val writer = cache()
        listOf(128, 384, 768).forEach { writer.write(source, MediaThumbnail(bitmap, it), writer.captureGeneration()) }
        val reader = cache()
        reader.removeByCiphertextTags(setOf(source.ciphertextTag))
        assertEquals(0L, reader.residentBytes())
        assertNull(cache().read(source, 128, cache().captureGeneration()))
        listOf(128, 384).forEach { reader.write(source, MediaThumbnail(bitmap, it), reader.captureGeneration()) }
        cache().remove(source.attachmentKey)
        assertEquals(0L, cache().residentBytes())
    }

    /** Transparent pixels round-trip without replacing alpha with an opaque background. */
    @Test
    fun transparentDerivativesRemainLossless() {
        bitmap.eraseColor(Color.argb(80, 255, 0, 255))
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration())
        val decoded = checkNotNull(cache.read(source, 128, cache.captureGeneration()))
        assertEquals(bitmap.getPixel(3, 3), decoded.bitmap.getPixel(3, 3))
    }

    /** A damaged authenticated envelope is a miss and cannot publish unverified encoded pixels. */
    @Test
    fun corruptionIsAMiss() {
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration())
        val file = checkNotNull(directory.listFiles()).single { it.extension == "enc" }
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(cache.read(source, 128, cache.captureGeneration()))
    }

    /** The derivative budget applies to encrypted bytes and evicts older entries instead of growing unbounded. */
    @Test
    fun encryptedFootprintIsBounded() {
        val budget = 1024L
        val cache = cache(budget)
        repeat(30) { index ->
            cache.write(source.copy(attachmentKey = "attachment-$index"), MediaThumbnail(bitmap, 128), cache.captureGeneration())
        }
        assertTrue(cache.residentBytes() <= budget)
        assertNull(cache.read(source.copy(attachmentKey = "attachment-0"), 128, cache.captureGeneration()))
        assertNotNull(cache.read(source.copy(attachmentKey = "attachment-29"), 128, cache.captureGeneration()))
    }

    /** Independent instances share encrypted bytes and a test-only key, as across process restart. */
    private fun cache(budget: Long = PersistentDisplayThumbnails.MAX_BYTES): PersistentDisplayThumbnails =
        PersistentDisplayThumbnails(
            DiskByteCache(
                File(directory.path),
                maxBytes = budget,
                keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") },
            ),
        )
}
