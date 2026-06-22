package dev.ipf.darkmatter.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decrypted-media cache subdirectory names (#559 sub-task #E).
 *
 * `MediaCacheDirs` exists precisely so the create sites and the sign-out wipe
 * site agree on these names: a silent rename on one side would leave decrypted
 * plaintext on disk after sign-out (a privacy-footprint regression). This test
 * makes such a rename a visible, intentional diff -- the create/wipe call sites
 * reference these same constants, so changing a value here forces a deliberate
 * edit rather than letting one side drift.
 */
class MediaCacheDirsTest {
    @Test
    fun cacheDirNamesAreTheStablePrivacyCleanupTargets() {
        assertEquals("voice_attachments", MediaCacheDirs.VOICE)
        assertEquals("video_attachments", MediaCacheDirs.VIDEO)
        assertEquals("shared_media", MediaCacheDirs.SHARED)
    }

    @Test
    fun cacheDirNamesAreDistinctAndNonBlank() {
        val names = listOf(MediaCacheDirs.VOICE, MediaCacheDirs.VIDEO, MediaCacheDirs.SHARED)
        assertEquals("cache dir names must be distinct", names.size, names.toSet().size)
        names.forEach { assertTrue("cache dir name must be non-blank", it.isNotBlank()) }
    }

    @Test
    fun cacheDirNamesContainNoPathSeparators() {
        // These are single-level subdirectory names under cacheDir; a '/' would
        // change the create/delete target path and could escape the cleanup root.
        listOf(MediaCacheDirs.VOICE, MediaCacheDirs.VIDEO, MediaCacheDirs.SHARED).forEach { name ->
            assertTrue("'$name' must not contain a path separator", !name.contains('/'))
            assertEquals("'$name' must be its own filename", name, java.io.File(name).name)
        }
    }
}
