package dev.ipf.whitenoise.android.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedMediaFallbackTest {
    @Test
    fun singleTypeFallbacksExposeTheirTypeAndCount() {
        assertEquals(
            SharedMediaFallback(SharedMediaFallbackType.Urls, count = 3),
            sharedMediaFallbackContent(videoCount = 0, voiceCount = 0, fileCount = 0, urlCount = 3),
        )
        assertEquals(
            SharedMediaFallback(SharedMediaFallbackType.Files, count = 2),
            sharedMediaFallbackContent(videoCount = 0, voiceCount = 0, fileCount = 2, urlCount = 0),
        )
        assertEquals(
            SharedMediaFallback(SharedMediaFallbackType.Voice, count = 1),
            sharedMediaFallbackContent(videoCount = 0, voiceCount = 1, fileCount = 0, urlCount = 0),
        )
    }

    @Test
    fun mixedFallbacksRemainGeneric() {
        val expected = SharedMediaFallback(SharedMediaFallbackType.Generic)

        assertEquals(
            expected,
            sharedMediaFallbackContent(videoCount = 0, voiceCount = 1, fileCount = 1, urlCount = 0),
        )
        assertEquals(
            expected,
            sharedMediaFallbackContent(videoCount = 0, voiceCount = 0, fileCount = 1, urlCount = 1),
        )
    }

    @Test
    fun videoFallbacksRemainGeneric() {
        val expected = SharedMediaFallback(SharedMediaFallbackType.Generic)

        assertEquals(
            expected,
            sharedMediaFallbackContent(videoCount = 1, voiceCount = 0, fileCount = 0, urlCount = 0),
        )
        assertEquals(
            expected,
            sharedMediaFallbackContent(videoCount = 1, voiceCount = 0, fileCount = 0, urlCount = 1),
        )
    }
}
