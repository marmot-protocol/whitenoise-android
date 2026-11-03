package dev.ipf.whitenoise.android.ui.conversation

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The read-aloud estimate's visual label and spoken description in translated, plural-aware text (#3563). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsRemainingTimeTextTest {
    /** Below a minute keeps the seconds label; from 60 s minutes lead and a zero seconds part is dropped. */
    @Test
    fun englishLabelsCrossTheMinuteBoundary() {
        val en = resources(Locale.US)
        val expected =
            mapOf(
                0 to "~0s left",
                1 to "~1s left",
                59 to "~59s left",
                60 to "~1m left",
                61 to "~1m 1s left",
                119 to "~1m 59s left",
                120 to "~2m left",
                312 to "~5m 12s left",
                Int.MAX_VALUE to "~35791394m 7s left",
            )

        expected.forEach { (total, label) -> assertEquals("$total s", label, ttsRemainingLabel(en, total)) }
    }

    /** The spoken form uses words and singular or plural units, never the compact symbols. */
    @Test
    fun englishDescriptionsSpellOutUnits() {
        val en = resources(Locale.US)

        assertEquals("About 1 second remaining", ttsRemainingDescription(en, 1))
        assertEquals("About 59 seconds remaining", ttsRemainingDescription(en, 59))
        assertEquals("About 1 minute remaining", ttsRemainingDescription(en, 60))
        assertEquals("About 1 minute 1 second remaining", ttsRemainingDescription(en, 61))
        assertEquals("About 5 minutes 12 seconds remaining", ttsRemainingDescription(en, 312))
    }

    /** Russian picks its one/few/many forms for each unit independently. */
    @Test
    fun russianDescriptionsChooseEachUnitsPluralForm() {
        val ru = resources(Locale.forLanguageTag("ru"))

        assertEquals("Осталось ~5 мин 12 с", ttsRemainingLabel(ru, 312))
        assertEquals("Осталось примерно 2 минуты 21 секунда", ttsRemainingDescription(ru, 141))
        assertEquals("Осталось примерно 5 минут", ttsRemainingDescription(ru, 300))
    }

    /** French and Chinese use their own word order and joiners rather than English suffixes. */
    @Test
    fun otherLocalesUseTheirOwnPhrasing() {
        val fr = resources(Locale.FRANCE)
        val zh = resources(Locale.SIMPLIFIED_CHINESE)

        assertEquals("~5 min 12 s restantes", ttsRemainingLabel(fr, 312))
        assertEquals("Temps restant : environ 5 minutes et 12 secondes", ttsRemainingDescription(fr, 312))
        assertEquals("约剩 5 分 12 秒", ttsRemainingLabel(zh, 312))
        assertEquals("约剩 2 分钟", ttsRemainingLabel(zh, 120))
    }

    /** Application resources rebuilt for [locale]. */
    private fun resources(locale: Locale): Resources {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration(app.resources.configuration).apply { setLocale(locale) }
        return app.createConfigurationContext(configuration).resources
    }
}
