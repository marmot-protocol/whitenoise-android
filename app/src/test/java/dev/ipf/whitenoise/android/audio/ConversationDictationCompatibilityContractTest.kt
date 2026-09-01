package dev.ipf.whitenoise.android.audio

import android.content.ComponentName
import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConversationDictationCompatibilityContractTest {
    @Test
    fun recognitionIntentPrefersPrivateOfflineProcessing() {
        val intent = conversationDictationRecognitionIntent()

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
    }

    @Test
    fun selectedRecognitionServiceProbeRejectsMissingMalformedAndUndiscoveredProviders() {
        val selected = ComponentName("org.example.speech", "org.example.speech.Recognition")

        assertNull(conversationDictationRecognitionServiceComponent(null))
        assertNull(conversationDictationRecognitionServiceComponent(" "))
        assertNull(conversationDictationRecognitionServiceComponent("not-a-component"))
        assertEquals(
            selected,
            conversationDictationRecognitionServiceComponent(
                "org.example.speech/org.example.speech.Recognition",
            ),
        )
        assertFalse(conversationDictationRecognitionServiceAvailable(selected, emptyList()))
        assertFalse(
            conversationDictationRecognitionServiceAvailable(
                selected,
                listOf(ComponentName("org.other", "org.other.Recognition")),
            ),
        )
        assertTrue(conversationDictationRecognitionServiceAvailable(selected, listOf(selected)))
    }

    @Test
    fun manifestAndRunbookKeepBothAndroidSpeechContractsDiscoverable() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val matrix = projectFile("docs/composer-dictation-device-matrix.md").readText()

        assertTrue("android.speech.action.RECOGNIZE_SPEECH" in manifest)
        assertTrue("android.speech.RecognitionService" in manifest)
        assertTrue("SpeechRecognizer" in matrix)
        assertTrue("RecognitionService" in matrix)
        assertTrue("Voice IME" in matrix)
        assertTrue("GrapheneOS" in matrix)
        assertTrue("no silent fallback" in matrix.lowercase())
    }

    private fun projectFile(relative: String): File =
        listOf(File(relative), File("../$relative")).firstOrNull(File::exists)
            ?: error("Missing $relative")
}
