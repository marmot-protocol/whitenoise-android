package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        writer.write(source, MediaThumbnail(bitmap, 768), writer.captureGeneration(source))
        val reader = cache()
        val result = checkNotNull(reader.read(source, 512, reader.captureGeneration(source)))
        assertEquals(768, result.maxEdgePx)
        assertEquals(24, result.bitmap.width)
        assertEquals(16, result.bitmap.height)
        assertTrue(Color.red(result.bitmap.getPixel(3, 3)) >= 250)
        assertEquals(255, Color.alpha(result.bitmap.getPixel(3, 3)))
        assertNull(reader.read(source, 1024, reader.captureGeneration(source)))
    }

    /** Account, attachment index, source epoch and ciphertext replacement cannot reuse old pixels. */
    @Test
    fun identitiesCannotAlias() {
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 256), cache.captureGeneration(source))
        listOf(
            source.copy(attachmentKey = "other:chat:message:0"),
            source.copy(attachmentKey = "account:chat:message:1"),
            source.copy(sourceEpoch = 8uL),
            source.copy(ciphertextTag = "b".repeat(64)),
        ).forEach { assertNull(cache.read(it, 128, cache.captureGeneration(it))) }
    }

    /** Every explicit invalidation rejects a producer that began before it, even when no file existed yet. */
    @Test
    fun removalCannotBeUndoneByLateFirstPublication() {
        val cache = cache()
        val removals: List<() -> Unit> =
            listOf(
                { cache.remove(source.attachmentKey) },
                { cache.removeByCiphertextTags(setOf(source.ciphertextTag)) },
                { cache.clear() },
            )
        removals.forEach { remove ->
            val pending = cache.captureGeneration(source)
            remove()
            cache.write(source, MediaThumbnail(bitmap, 256), pending)
            assertNull(cache.read(source, 128, cache.captureGeneration(source)))
        }
    }

    /** A producer that starts while cleanup waits for another writer cannot repopulate the completed wipe. */
    @Test
    fun tokensCapturedDuringBlockedCleanupAreRejected() {
        val pause = AtomicBoolean(false)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache =
            PersistentDisplayThumbnails(
                DiskByteCache(
                    directory,
                    maxBytes = PersistentDisplayThumbnails.MAX_BYTES,
                    keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") },
                    afterEncryptedWrite = {
                        if (pause.get()) {
                            writing.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    },
                ),
            )
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration(source))
        pause.set(true)
        val before = cache.captureGeneration(source)
        val writer = CompletableFuture.runAsync { cache.write(source, MediaThumbnail(bitmap, 384), before) }
        check(writing.await(10, TimeUnit.SECONDS))
        val cleaner = CompletableFuture.runAsync { cache.clear() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (cache.isCurrent(before) && System.nanoTime() < deadline) Thread.yield()
            val during = cache.captureGeneration(source)
            assertTrue(during != before)
            assertTrue(!cache.isCurrent(during))
            assertNull(cache.read(source, 128, during))
            release.countDown()
            writer.get(10, TimeUnit.SECONDS)
            cleaner.get(10, TimeUnit.SECONDS)
            pause.set(false)
            cache.write(source, MediaThumbnail(bitmap, 256), during)
            assertEquals(0L, cache.residentBytes())
        } finally {
            release.countDown()
            writer.get(10, TimeUnit.SECONDS)
            cleaner.get(10, TimeUnit.SECONDS)
        }
    }

    /** An unrelated key or tag cannot discard another attachment's completed decode or pending write. */
    @Test
    fun invalidationOnlyRetiresMatchingSources() {
        val cache = cache()
        val pending = cache.captureGeneration(source)
        cache.remove("another-attachment")
        cache.removeByCiphertextTags(setOf("b".repeat(64)))
        assertTrue(cache.isCurrent(pending))
        cache.write(source, MediaThumbnail(bitmap, 128), pending)
        assertNotNull(cache.read(source, 128, pending))
        cache.removeByCiphertextTags(setOf(source.ciphertextTag))
        assertTrue(!cache.isCurrent(pending))
        assertNull(cache.read(source, 128, cache.captureGeneration(source)))
    }

    /** Disposing a queued presentation prevents its encoder from publishing after it has left the screen. */
    @Test
    fun disposedEncoderCannotPublish() {
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration(source)) { false }
        assertEquals(0L, cache.residentBytes())
    }

    /** Persisted tags and enumerable size slots survive a restart; no loaded-message index is required. */
    @Test
    fun expiryAndRemovalDeleteAllSizesAfterRestart() {
        val writer = cache()
        listOf(128, 384, 768).forEach {
            writer.write(source, MediaThumbnail(bitmap, it), writer.captureGeneration(source))
        }
        val reader = cache()
        reader.removeByCiphertextTags(setOf(source.ciphertextTag))
        assertEquals(0L, reader.residentBytes())
        assertNull(reader.read(source, 128, reader.captureGeneration(source)))
        listOf(128, 384).forEach { reader.write(source, MediaThumbnail(bitmap, it), reader.captureGeneration(source)) }
        cache().remove(source.attachmentKey)
        assertEquals(0L, cache().residentBytes())
    }

    /** Worker-shaped ARGB buffers still select JPEG when every copied pixel is actually opaque. */
    @Test
    fun opaqueWorkerPixelsUseTheMeasuredJpegPath() {
        assertTrue(bitmap.hasAlpha())
        assertEquals(Bitmap.CompressFormat.JPEG, displayThumbnailEncodingFormat(bitmap))
        bitmap.setPixel(0, bitmap.height - 1, Color.TRANSPARENT)
        assertEquals(Bitmap.CompressFormat.WEBP_LOSSLESS, displayThumbnailEncodingFormat(bitmap))
    }

    /** Transparent pixels round-trip without replacing alpha with an opaque background. */
    @Test
    fun transparentDerivativesRemainLossless() {
        bitmap.eraseColor(Color.argb(80, 255, 0, 255))
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration(source))
        val decoded = checkNotNull(cache.read(source, 128, cache.captureGeneration(source)))
        assertEquals(bitmap.getPixel(3, 3), decoded.bitmap.getPixel(3, 3))
    }

    /** A damaged authenticated envelope is a miss and cannot publish unverified encoded pixels. */
    @Test
    fun corruptionIsAMiss() {
        val cache = cache()
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration(source))
        val file = checkNotNull(directory.listFiles()).single { it.extension == "enc" }
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(cache.read(source, 128, cache.captureGeneration(source)))
    }

    /** The derivative budget applies to encrypted bytes and evicts older entries instead of growing unbounded. */
    @Test
    fun encryptedFootprintIsBounded() {
        val sizingCache = cache()
        sizingCache.write(source, MediaThumbnail(bitmap, 128), sizingCache.captureGeneration(source))
        val entryBytes = sizingCache.residentBytes()
        assertTrue(entryBytes > 0)
        sizingCache.clear()
        val budget = entryBytes * 2
        val cache = cache(budget)
        repeat(30) { index ->
            cache.write(
                source.copy(attachmentKey = "attachment-$index"),
                MediaThumbnail(bitmap, 128),
                cache.captureGeneration(source.copy(attachmentKey = "attachment-$index")),
            )
        }
        assertTrue(cache.residentBytes() in 1..budget)
        val oldest = source.copy(attachmentKey = "attachment-0")
        val newest = source.copy(attachmentKey = "attachment-29")
        assertNull(cache.read(oldest, 128, cache.captureGeneration(oldest)))
        assertNotNull(cache.read(newest, 128, cache.captureGeneration(newest)))
    }

    /** Existence probes allocate no pixels, and a rejected codec result cannot block regeneration forever. */
    @Test
    fun rejectedPayloadCanBeRegeneratedWithoutDecodingDuringTheExistenceProbe() {
        val disk =
            DiskByteCache(
                directory,
                PersistentDisplayThumbnails.MAX_BYTES,
                DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") },
            )
        var decodeCalls = 0
        var rejectPixels = true
        val cache =
            PersistentDisplayThumbnails(disk) { bytes, offset, length ->
                decodeCalls++
                if (rejectPixels) null else BitmapFactory.decodeByteArray(bytes, offset, length)
            }
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration(source))
        assertTrue(cache.hasMatching(source, 128, cache.captureGeneration(source)))
        assertEquals(0, decodeCalls)
        assertNull(cache.read(source, 128, cache.captureGeneration(source)))
        assertEquals(1, decodeCalls)
        assertTrue(!cache.hasMatching(source, 128, cache.captureGeneration(source)))
        rejectPixels = false
        cache.write(source, MediaThumbnail(bitmap, 128), cache.captureGeneration(source))
        assertTrue(cache.hasMatching(source, 128, cache.captureGeneration(source)))
        assertNotNull(cache.read(source, 128, cache.captureGeneration(source)))
        assertEquals(2, decodeCalls)
    }

    /** Deletion fences even a producer whose first derivative was not yet present on disk. */
    @Test
    fun deletedMessageCannotBeResurrectedByALateFirstWrite() {
        val cache = cache()
        val pending = cache.captureGeneration(source)
        cache.removeMessage("account:chat:message:")
        cache.write(source, MediaThumbnail(bitmap, 128), pending)
        assertEquals(0L, cache.residentBytes())
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
