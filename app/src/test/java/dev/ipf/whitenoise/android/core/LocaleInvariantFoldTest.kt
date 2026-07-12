package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocaleInvariantFoldTest {
    @Test
    fun foldAndReplyMediaClassificationIgnoreTurkishDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))

            assertEquals("iris", localeInvariantFold("IRIS"))
            assertEquals(ReplyMediaKind.Voice, replyMediaKindFromJson("{\"type\":\"AUDIO/AAC\"}"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
