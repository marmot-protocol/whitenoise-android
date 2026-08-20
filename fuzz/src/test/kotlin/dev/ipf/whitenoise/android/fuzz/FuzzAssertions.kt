package dev.ipf.whitenoise.android.fuzz

import org.junit.jupiter.api.Assertions

/** Fixed-message assertions that avoid interpolating attacker-controlled values on failure. */
object FuzzAssertions {
    fun assertTrue(
        message: String,
        condition: Boolean,
    ) {
        Assertions.assertTrue(condition, message)
    }

    fun assertFalse(
        message: String,
        condition: Boolean,
    ) {
        Assertions.assertFalse(condition, message)
    }

    fun assertNull(
        message: String,
        value: Any?,
    ) {
        Assertions.assertNull(value, message)
    }

    fun <T> assertEquals(
        message: String,
        expected: T,
        actual: T,
    ) {
        Assertions.assertTrue(expected == actual, message)
    }
}
