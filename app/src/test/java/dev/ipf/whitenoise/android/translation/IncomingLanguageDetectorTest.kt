package dev.ipf.whitenoise.android.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomingLanguageDetectorTest {
    private val detector = IncomingLanguageDetector(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test
    fun packagedProfilesRecognizeClearConversationText() {
        assertEquals("fr", detector.detect("Bonjour tout le monde"))
        assertEquals("en", detector.detect("She wrote to ask whether the meeting is still on for tomorrow."))
        assertEquals("de", detector.detect("Guten Morgen, hast du heute Nachmittag Zeit für einen Spaziergang?"))
        assertEquals("es", detector.detect("Hola, ¿quieres tomar un café mañana por la mañana?"))
        assertEquals(
            "fa",
            detector.detect("من امروز به خانه رفتم و با دوستانم درباره کار و زندگی صحبت کردم. بعد از آن به فروشگاه رفتم و برای شام غذا خریدم."),
        )
        assertEquals("ja", detector.detect("今晩は友達と一緒に映画を見に行きます。"))
    }

    @Test
    fun sharedBengaliScriptDoesNotMisidentifyAssamese() {
        assertNull(detector.detect("অসমীয়া ভাষা অসমৰ মানুহে ব্যৱহাৰ কৰা এটা ভাৰতীয় ভাষা।"))
    }

    @Test
    fun shortLinksEmojiAndAmbiguousScriptsGiveNoSuggestion() {
        for (text in listOf("Salut", "https://bonjour-tout-le-monde.example/path", "🎉🙂", "天地玄黃宇宙洪荒日月盈昃辰宿列張")) {
            assertNull(text, detector.detect(text))
        }
    }
}
