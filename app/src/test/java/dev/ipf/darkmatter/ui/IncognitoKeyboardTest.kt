package dev.ipf.darkmatter.ui

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure `imeOptions` decision backing the incognito keyboard
 * interceptor (issues #405, #561). The [EditorInfo] mutation is split out of
 * [incognitoImeOptions] so the flag contract can be exercised on the JVM without
 * Robolectric.
 */
class IncognitoKeyboardTest {
    @Test
    fun enabledSetsTheNoPersonalizedLearningBit() {
        assertEquals(
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            incognitoImeOptions(current = 0, enabled = true),
        )
    }

    @Test
    fun disabledReturnsImeOptionsUnchanged() {
        assertEquals(0, incognitoImeOptions(current = 0, enabled = false))
        assertEquals(
            EditorInfo.IME_ACTION_DONE,
            incognitoImeOptions(current = EditorInfo.IME_ACTION_DONE, enabled = false),
        )
    }

    @Test
    fun enabledIsIdempotentWhenFlagAlreadyPresent() {
        val withFlag = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        assertEquals(withFlag, incognitoImeOptions(current = withFlag, enabled = true))
    }

    @Test
    fun enabledPreservesOtherImeOptionBits() {
        assertEquals(
            EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            incognitoImeOptions(current = EditorInfo.IME_ACTION_DONE, enabled = true),
        )
    }

    @Test
    fun disabledPreservesOtherImeOptionBits() {
        assertEquals(
            EditorInfo.IME_ACTION_DONE,
            incognitoImeOptions(current = EditorInfo.IME_ACTION_DONE, enabled = false),
        )
    }
}
