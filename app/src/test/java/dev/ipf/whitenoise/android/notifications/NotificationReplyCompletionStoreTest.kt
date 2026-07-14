package dev.ipf.whitenoise.android.notifications

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReplyCompletionStoreTest {
    @Test
    fun completedMarkerRoundTrips() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })

        assertFalse(store.isCompleted("reply-a"))
        assertFalse(store.hasStarted("reply-a"))

        store.markStarted("reply-a", boundary(timelineAt = 10uL, messageIdHex = "message-before-reply-a"))
        assertTrue(store.hasStarted("reply-a"))
        assertEquals(
            boundary(timelineAt = 10uL, messageIdHex = "message-before-reply-a"),
            store.startedRecoveryBoundary("reply-a"),
        )
        store.markCompleted("reply-a")

        assertTrue(store.isCompleted("reply-a"))
        assertFalse(store.hasStarted("reply-a"))
        assertNull(store.startedRecoveryBoundary("reply-a"))
        assertFalse(store.isCompleted("reply-b"))
    }

    @Test
    fun distinctRequestsPersistIndependentRecoveryBoundaries() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })

        store.markStarted("request-a", boundary(timelineAt = 10uL, messageIdHex = "message-before-a"))
        store.markStarted("request-b", boundary(timelineAt = 20uL, messageIdHex = "message-before-b"))

        assertEquals(
            boundary(timelineAt = 10uL, messageIdHex = "message-before-a"),
            store.startedRecoveryBoundary("request-a"),
        )
        assertEquals(
            boundary(timelineAt = 20uL, messageIdHex = "message-before-b"),
            store.startedRecoveryBoundary("request-b"),
        )

        store.markCompleted("request-a")

        assertNull(store.startedRecoveryBoundary("request-a"))
        assertEquals(
            boundary(timelineAt = 20uL, messageIdHex = "message-before-b"),
            store.startedRecoveryBoundary("request-b"),
        )
    }

    @Test
    fun legacyStartedMarkerDoesNotInventARecoveryBoundary() {
        val prefs = FakeSharedPreferences()
        prefs
            .edit()
            .putLong(NotificationReplyCompletionStore.startedStorageKey("legacy"), 1_000L)
            .commit()
        val store = NotificationReplyCompletionStore(prefs, nowMillis = { 1_000L })

        assertTrue(store.hasStarted("legacy"))
        assertNull(store.startedRecoveryBoundary("legacy"))
    }

    @Test
    fun markStartedAndCompletedPruneExpiredMarkers() {
        var now = 10L
        val prefs = FakeSharedPreferences()
        val store = NotificationReplyCompletionStore(prefs, nowMillis = { now })

        store.markStarted("stale", boundary(timelineAt = 10uL, messageIdHex = "stale-boundary"))
        store.markCompleted("old")
        now += 8L * 24L * 60L * 60L * 1000L
        store.markCompleted("new")

        assertFalse(store.hasStarted("stale"))
        assertNull(store.startedRecoveryBoundary("stale"))
        assertFalse(store.isCompleted("old"))
        assertTrue(store.isCompleted("new"))
    }

    private fun boundary(
        timelineAt: ULong,
        messageIdHex: String,
    ): NotificationReplyRecoveryBoundary = NotificationReplyRecoveryBoundary(timelineAt, messageIdHex)

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = values[key] as? Long ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = values[key] as? String ?: defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = values[key] as? Int ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putLong(
                key: String?,
                value: Long,
            ): SharedPreferences.Editor {
                if (key != null) updates[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) updates[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            override fun apply() {
                commit()
            }

            override fun commit(): Boolean {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }

            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor {
                if (key != null) updates[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = this

            override fun putInt(
                key: String?,
                value: Int,
            ): SharedPreferences.Editor = this

            override fun putFloat(
                key: String?,
                value: Float,
            ): SharedPreferences.Editor = this

            override fun putBoolean(
                key: String?,
                value: Boolean,
            ): SharedPreferences.Editor = this
        }
    }
}
