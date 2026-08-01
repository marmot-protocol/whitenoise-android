package dev.ipf.whitenoise.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownSpeakableTextAndroidTest {
    @Test
    fun emptyDelimiterCleanupCompilesWithAndroidRegexEngine() {
        assertEquals(
            "Ready.",
            legacyTextToSpeakableText("Ready () [] {}"),
        )
    }
}
