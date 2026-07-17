package dev.ipf.whitenoise.android.notifications

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class NotificationTapTokensTest {
    @Test
    fun removeDeletesTokenAndTimestamp() {
        var now = 100L
        val prefs = FakeSharedPreferences()
        val tokens = NotificationTapTokens(prefs, randomBytes = ::fillBytes, nowMillis = { now++ })

        val token = tokens.tokenFor("invite-key")
        assertTrue(tokens.isValid("invite-key", token))

        tokens.remove("invite-key")

        assertFalse(tokens.isValid("invite-key", token))
        assertFalse(prefs.contains(NotificationTapTokens.storageKey("invite-key")))
        assertFalse(prefs.contains(NotificationTapTokens.storageTimeKey("invite-key")))
    }

    @Test
    fun tokenStorePrunesOldestEntriesAtCap() {
        var now = 100L
        val prefs = FakeSharedPreferences()
        val tokens = NotificationTapTokens(prefs, randomBytes = ::fillBytes, nowMillis = { now++ })
        val newestKey = "invite-${NotificationTapTokens.MAX_STORED_TOKENS + 1}"
        val newestToken =
            (0..NotificationTapTokens.MAX_STORED_TOKENS + 1)
                .associate { index ->
                    val key = "invite-$index"
                    key to tokens.tokenFor(key)
                }.getValue(newestKey)

        assertEquals(NotificationTapTokens.MAX_STORED_TOKENS, prefs.tokenEntryCount())
        assertFalse(tokens.isValid("invite-0", "abcdefghijklmnop"))
        assertTrue(tokens.isValid(newestKey, newestToken))
    }

    @Test
    fun reusingTokenTouchesEntryBeforePruning() {
        var now = 100L
        val prefs = FakeSharedPreferences()
        val tokens = NotificationTapTokens(prefs, randomBytes = ::fillBytes, nowMillis = { now++ })
        val firstToken = tokens.tokenFor("invite-0")
        val secondToken = tokens.tokenFor("invite-1")
        repeat(NotificationTapTokens.MAX_STORED_TOKENS - 2) { index ->
            tokens.tokenFor("invite-${index + 2}")
        }

        assertEquals(firstToken, tokens.tokenFor("invite-0"))
        tokens.tokenFor("overflow")

        assertTrue(tokens.isValid("invite-0", firstToken))
        assertFalse(tokens.isValid("invite-1", secondToken))
        assertEquals(NotificationTapTokens.MAX_STORED_TOKENS, prefs.tokenEntryCount())
    }

    @Test
    fun tokenValidationUsesConstantTimeByteComparison() {
        val source = notificationTapTokensSource().readText()
        val validation = source.substringAfter("fun isValid(").substringBefore("private fun pruneIfNeeded")

        assertTrue("tap tokens must use MessageDigest.isEqual", "MessageDigest.isEqual(" in validation)
        assertFalse("tap tokens must not use String.equals", "expected ==" in validation)
    }

    private fun fillBytes(bytes: ByteArray) {
        bytes.indices.forEach { bytes[it] = it.toByte() }
    }

    private fun notificationTapTokensSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationTapTokens.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/notifications/NotificationTapTokens.kt"),
        ).firstOrNull(File::exists) ?: error("Missing NotificationTapTokens.kt")

    private fun FakeSharedPreferences.tokenEntryCount(): Int = all.keys.count { it.startsWith("tap_") && !it.startsWith("tap_time_") }

    private class FakeSharedPreferences : SharedPreferences {
        private val values: MutableMap<String, Any?> = Collections.synchronizedMap(HashMap())

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = (values[key] as? String) ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun getAll(): MutableMap<String, *> = values

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = (values[key] as? Long) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val puts: MutableMap<String, Any?> = HashMap()
            private val removals: MutableSet<String> = HashSet()

            override fun putString(
                key: String,
                value: String?,
            ): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putLong(
                key: String,
                value: Long,
            ): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removals += key
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = this

            override fun putInt(
                key: String,
                value: Int,
            ): SharedPreferences.Editor = this

            override fun putFloat(
                key: String,
                value: Float,
            ): SharedPreferences.Editor = this

            override fun putBoolean(
                key: String,
                value: Boolean,
            ): SharedPreferences.Editor = this

            override fun clear(): SharedPreferences.Editor {
                synchronized(values) { values.clear() }
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() = flush()

            private fun flush() {
                synchronized(values) {
                    removals.forEach(values::remove)
                    values.putAll(puts)
                }
                puts.clear()
                removals.clear()
            }
        }
    }
}
