package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VoiceRecordingControllerTest {
    @Test
    fun sweepStaleVoiceTempFilesDeletesOnlyOldVoiceTakes() {
        val dir = Files.createTempDirectory("voice-sweep-test").toFile()
        try {
            val now = 2_000_000_000L
            val stale = dir.resolve("voice-1.m4a").also { it.writeText("old") }
            val fresh = dir.resolve("voice-2.m4a").also { it.writeText("new") }
            val other = dir.resolve("other.m4a").also { it.writeText("keep") }
            stale.setLastModified(now - VoiceRecordingController.STALE_VOICE_TEMP_AGE_MS - 1_000L)
            fresh.setLastModified(now)
            other.setLastModified(now - VoiceRecordingController.STALE_VOICE_TEMP_AGE_MS - 1_000L)

            val removed = VoiceRecordingController.sweepStaleVoiceTempFiles(dir, nowMillis = now)

            assertEquals(1, removed)
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            assertTrue(other.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
