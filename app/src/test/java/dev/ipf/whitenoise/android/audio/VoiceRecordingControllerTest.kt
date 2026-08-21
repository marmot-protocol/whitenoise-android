package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    @Test
    fun recordingFocusRequestIsStoredOnlyAfterGrant() {
        val body = voiceRecordingControllerSource().kotlinFunctionBody("requestRecordingFocus")

        assertTrue(
            "requestRecordingFocus should only retain the focus request after AudioManager grants it",
            "focusRequest = req" in body &&
                body.indexOf("requestAudioFocus(req)") < body.indexOf("focusRequest = req"),
        )
    }

    @Test
    fun abortedRestartClearsStateAndReleasesAnIdleMicrophoneLease() {
        val source = voiceRecordingControllerSource()
        val start = source.kotlinFunctionBody("start")
        val cleanup = source.kotlinFunctionBody("completeRestart")

        assertTrue(
            "the restart cleanup must release its lease when no recorder took ownership",
            "finally" in start &&
                "completeRestart()" in start &&
                "restarting = false" in cleanup &&
                "if (!isRecording && recorder == null) releaseMicrophoneLease()" in cleanup,
        )
    }

    private fun voiceRecordingControllerSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/audio/VoiceRecordingController.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/audio/VoiceRecordingController.kt"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("Missing VoiceRecordingController.kt source file")
}
