package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListGroupIdSearchTest {
    @Test
    fun hexPrefixesOfPlausibleLengthQualify() {
        assertTrue(looksLikeGroupIdNeedle("deadbeef"))
        assertTrue(looksLikeGroupIdNeedle("0123456789abcdef"))
        assertTrue(looksLikeGroupIdNeedle("a".repeat(64)))
    }

    @Test
    fun shortHexLookingWordsStayPlainTextQueries() {
        assertFalse(looksLikeGroupIdNeedle("cafe"))
        assertFalse(looksLikeGroupIdNeedle("dead"))
        assertFalse(looksLikeGroupIdNeedle("abcdef1"))
    }

    @Test
    fun nonHexNeedlesNeverQualify() {
        assertFalse(looksLikeGroupIdNeedle("research workgroup"))
        assertFalse(looksLikeGroupIdNeedle("abcdefgh"))
        assertFalse(looksLikeGroupIdNeedle("npub1abcdef00"))
        assertFalse(looksLikeGroupIdNeedle(""))
    }
}
