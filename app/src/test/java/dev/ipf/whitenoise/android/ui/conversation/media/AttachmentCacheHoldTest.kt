package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.holdCacheFileInUse
import dev.ipf.whitenoise.android.media.releaseCacheFileInUse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentCacheHoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val root = Files.createTempDirectory("attachment-cache-hold").toFile()
    private val directory = File(root, MediaCacheDirs.VIDEO).apply { mkdirs() }

    /** Removes the temporary cache directory. */
    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Save and Share read a materialized video under a hold, so a trim meanwhile keeps it, and later evicts it. */
    @Test
    fun aMaterializedFileIsHeldWhileItIsUsed() =
        runBlocking {
            val file = File(directory, "clip.mp4").apply { writeBytes(ByteArray(8)) }

            val keptDuringUse =
                withHeldMaterializedFile(materialize = { file }) { held ->
                    AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
                    held.isFile
                }

            assertTrue(keptDuringUse)
            AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
            assertFalse(file.isFile)
        }

    /** A file evicted before the hold took it is materialized again, so its user never reads a missing file. */
    @Test
    fun aFileEvictedBeforeTheHoldIsMaterializedAgain() =
        runBlocking {
            val file = File(directory, "clip.mp4")
            var materializations = 0

            val readable =
                withHeldMaterializedFile(
                    materialize = {
                        materializations++
                        // The first publication is evicted before the hold, the second lands under it.
                        if (materializations == 2) file.writeBytes(ByteArray(8))
                        file
                    },
                ) { held -> held.isFile }

            assertTrue(readable)
            assertEquals(2, materializations)
        }

    /** Should a republished file land on another path, the hold and the user follow it to that file. */
    @Test
    fun aRepublicationOnAnotherPathIsHeldAndUsed() =
        runBlocking {
            val evicted = File(directory, "evicted.mp4")
            val republished = File(directory, "republished.mp4")
            var materializations = 0

            val used =
                withHeldMaterializedFile(
                    materialize = {
                        materializations++
                        if (materializations == 1) {
                            evicted
                        } else {
                            republished.apply { writeBytes(ByteArray(8)) }
                        }
                    },
                ) { held ->
                    AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
                    held.takeIf(File::isFile)
                }

            assertEquals(republished, used)
            AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
            assertFalse(republished.isFile)
        }

    /**
     * A hold that lands while a trim has already chosen its file is never confirmed for a file that trim then deletes:
     * the holder's presence check waits for the trim. A hold that lands before the trim reaches a file keeps it,
     * since the trim reads the holds again before each deletion.
     */
    @Test
    fun aHoldRacingAnEvictionIsNeverConfirmedForADeletedFile() {
        val raced = File(directory, "raced.mp4").apply { writeBytes(ByteArray(8)) }
        val waiting = File(directory, "waiting.mp4").apply { writeBytes(ByteArray(8)) }
        assertTrue(raced.setLastModified(1_000L) && waiting.setLastModified(2_000L))
        val chosen = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        AttachmentPlaintextCache.beforeEvictionForTests = { file ->
            if (file == raced) {
                chosen.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            val trim = thread { AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L) }
            assertTrue(chosen.await(5, TimeUnit.SECONDS))
            holdCacheFileInUse(raced)
            holdCacheFileInUse(waiting)
            var racedConfirmed = false
            var waitingConfirmed = false
            val check =
                thread {
                    racedConfirmed = AttachmentPlaintextCache.isHeldFilePresent(raced)
                    waitingConfirmed = AttachmentPlaintextCache.isHeldFilePresent(waiting)
                }
            // Let the check reach the trim's lock before the trim deletes the file it already chose.
            check.join(200L)
            proceed.countDown()
            trim.join(5_000L)
            check.join(5_000L)

            assertFalse(racedConfirmed && !raced.isFile)
            assertTrue(waitingConfirmed && waiting.isFile)
        } finally {
            AttachmentPlaintextCache.beforeEvictionForTests = null
            releaseCacheFileInUse(raced)
            releaseCacheFileInUse(waiting)
        }
    }

    /** The viewer holds the file it plays for as long as it is composed, and releases it when it leaves. */
    @Test
    fun aComposedViewerHoldsItsFileUntilItLeaves() {
        val file = File(directory, "clip.mp4").apply { writeBytes(ByteArray(8)) }
        val showing = mutableStateOf(true)
        composeRule.setContent { if (showing.value) HoldCacheFileWhileComposed(file) }

        composeRule.waitForIdle()
        AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
        assertTrue(file.isFile)

        composeRule.runOnIdle { showing.value = false }
        composeRule.waitForIdle()
        AttachmentPlaintextCache.trimDirectoryToByteCap(directory, 0L)
        assertFalse(file.isFile)
    }
}
