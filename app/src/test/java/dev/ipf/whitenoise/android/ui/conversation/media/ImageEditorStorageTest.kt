package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageEditorStorageTest {
    private lateinit var root: File
    private val bitmaps = mutableListOf<Bitmap>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("image-editor-storage").toFile()
    }

    @After
    fun tearDown() {
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        root.deleteRecursively()
    }

    @Test
    fun savePublishesOnePrivatePngWithoutLeavingAPartialFile() {
        val bitmap = bitmap(16, 8, Color.MAGENTA)

        val file = writeEditedBitmap(root, bitmap)

        assertNotNull(file)
        val saved = requireNotNull(file)
        assertEquals(File(root, MediaCacheDirs.IMAGE_EDITOR).canonicalFile, saved.parentFile!!.canonicalFile)
        assertTrue(saved.name.endsWith(".png"))
        assertTrue(saved.length() > 0L)
        assertTrue(
            saved.parentFile!!
                .listFiles()
                .orEmpty()
                .none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun oversizeOutputIsRejectedAndReclaimed() {
        val bitmap = bitmap(16, 8, Color.MAGENTA)

        val file = writeEditedBitmap(root, bitmap, maxOutputBytes = 1L)

        assertNull(file)
        assertTrue(File(root, MediaCacheDirs.IMAGE_EDITOR).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun startupSweepDeletesPartialAndStaleArtifactsButKeepsRecentCompletedDrafts() {
        val directory = File(root, MediaCacheDirs.IMAGE_EDITOR).apply { mkdirs() }
        val now = 10_000L
        val stalePartial =
            File(directory, "stale-render.tmp").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L)
            }
        val freshPartial =
            File(directory, "fresh-render.tmp").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now)
            }
        val stale =
            File(directory, "stale.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L)
            }
        val recent =
            File(directory, "recent.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(9_000L)
            }

        sweepStaleImageEditorFiles(root, maxAgeMillis = 5_000L, nowMillis = now)

        assertFalse(stalePartial.exists())
        assertTrue(freshPartial.exists())
        assertFalse(stale.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun startupPartialSweepLeavesCompletedDraftsForStateRestoration() {
        val directory = File(root, MediaCacheDirs.IMAGE_EDITOR).apply { mkdirs() }
        val now = 10_000L
        val stalePartial =
            File(directory, "stale-render.tmp").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L)
            }
        val freshPartial =
            File(directory, "fresh-render.tmp").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now)
            }
        val completed = File(directory, "restored.png").apply { writeBytes(byteArrayOf(1)) }

        sweepIncompleteImageEditorFiles(root, maxAgeMillis = 5_000L, nowMillis = now)

        assertFalse(stalePartial.exists())
        assertTrue(freshPartial.exists())
        assertTrue(completed.exists())
    }

    @Test
    fun restoredEditorUriProtectsCompletedDraftFromOrphanSweep() {
        val directory = File(root, MediaCacheDirs.IMAGE_EDITOR).apply { mkdirs() }
        val now = 10_000L
        val restored =
            File(directory, "restored.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L)
            }
        val orphan =
            File(directory, "orphan.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000L)
            }
        val authority = "dev.ipf.test.fileprovider"
        val restoredUri = Uri.parse("content://$authority/image_editor/${restored.name}")

        val protectedNames = ownedEditorFileNamesForUris(root, authority, listOf(restoredUri))
        sweepStaleImageEditorFiles(
            root,
            maxAgeMillis = 5_000L,
            nowMillis = now,
            protectedFileNames = protectedNames,
        )

        assertTrue(restored.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun conversationRecreationCleanupKeepsCompletedEditedRevisions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        clearMediaTempFiles(context)
        val edited =
            File(File(context.cacheDir, MediaCacheDirs.IMAGE_EDITOR).apply { mkdirs() }, "edited.png").apply {
                writeBytes(byteArrayOf(1))
            }

        try {
            clearMediaTempFiles(context)

            assertTrue("a saveable editor URI must survive screen recreation", edited.exists())
        } finally {
            edited.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun ownershipResolutionRejectsOtherAuthoritiesAndTraversal() {
        val owned =
            File(File(root, MediaCacheDirs.IMAGE_EDITOR).apply { mkdirs() }, "edited.png").apply {
                writeBytes(byteArrayOf(1))
            }
        val authority = "dev.ipf.test.fileprovider"

        assertEquals(
            owned.canonicalFile,
            ownedEditorFileForUri(
                cacheRoot = root,
                expectedAuthority = authority,
                uri = Uri.parse("content://$authority/image_editor/edited.png"),
            )?.canonicalFile,
        )
        assertNull(
            ownedEditorFileForUri(
                cacheRoot = root,
                expectedAuthority = authority,
                uri = Uri.parse("content://other/image_editor/edited.png"),
            ),
        )
        assertNull(
            ownedEditorFileForUri(
                cacheRoot = root,
                expectedAuthority = authority,
                uri = Uri.parse("content://$authority/image_editor/..%2Fcamera%2Fsource.jpg"),
            ),
        )
        assertFalse(File(root, "camera/source.jpg").exists())
    }

    private fun bitmap(
        width: Int,
        height: Int,
        color: Int,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            bitmaps += this
        }
}
