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

        store.markStarted(
            "reply-a",
            scope = "group-a",
            recoveryBoundary = boundary(timelineAt = 10uL, messageIdHex = "message-before-reply-a"),
        )
        assertTrue(store.hasStarted("reply-a"))
        assertEquals(
            boundary(timelineAt = 10uL, messageIdHex = "message-before-reply-a"),
            store.startedRecoveryState("reply-a")?.boundary,
        )
        assertTrue(store.markCommittedMessage("reply-a", "c".repeat(64)))
        assertEquals("c".repeat(64), store.startedRecoveryState("reply-a")?.committedMessageIdHex)
        store.markCompleted("reply-a")

        assertTrue(store.isCompleted("reply-a"))
        assertFalse(store.hasStarted("reply-a"))
        assertNull(store.startedRecoveryState("reply-a"))
        assertFalse(store.isCompleted("reply-b"))
    }

    @Test
    fun attemptBoundariesAreStrictlyOrderedWithinScope() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })
        val proposed = NotificationReplyRecoveryBoundary(10uL, "f".repeat(64))

        assertEquals(proposed, store.markStarted("first", "group", proposed))
        assertEquals(
            NotificationReplyRecoveryBoundary(11uL, "f".repeat(64)),
            store.markStarted("second", "group", proposed),
        )
    }

    @Test
    fun mixedCaseBoundaryAndCommitIdsPersistAsLowercase() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })

        val persisted = store.markStarted("reply", "group", NotificationReplyRecoveryBoundary(10uL, "A".repeat(64)))
        assertEquals("a".repeat(64), persisted?.messageIdHex)
        assertEquals("a".repeat(64), store.startedRecoveryState("reply")?.boundary?.messageIdHex)

        assertTrue(store.markCommittedMessage("reply", "B".repeat(64)))
        assertEquals("b".repeat(64), store.startedRecoveryState("reply")?.committedMessageIdHex)
    }

    @Test
    fun distinctRequestsPersistIndependentRecoveryBoundaries() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })

        store.markStarted("request-a", "group", boundary(timelineAt = 10uL, messageIdHex = "message-before-a"))
        store.markStarted("request-b", "group", boundary(timelineAt = 20uL, messageIdHex = "message-before-b"))

        assertEquals(
            boundary(timelineAt = 10uL, messageIdHex = "message-before-a"),
            store.startedRecoveryState("request-a")?.boundary,
        )
        assertEquals(
            boundary(timelineAt = 20uL, messageIdHex = "message-before-b"),
            store.startedRecoveryState("request-b")?.boundary,
        )
        assertEquals(
            boundary(timelineAt = 20uL, messageIdHex = "message-before-b"),
            store.recoverySnapshot("request-a")?.nextAttemptBoundary,
        )

        store.markCompleted("request-a")

        assertNull(store.startedRecoveryState("request-a"))
        assertEquals(
            boundary(timelineAt = 20uL, messageIdHex = "message-before-b"),
            store.startedRecoveryState("request-b")?.boundary,
        )
    }

    @Test
    fun completedLaterRequestStillFencesEarlierRecovery() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })
        store.markStarted("request-b", "group", boundary(timelineAt = 10uL, messageIdHex = "before-b"))
        store.markStarted("request-c", "group", boundary(timelineAt = 20uL, messageIdHex = "before-c"))
        store.markCompleted("request-c")

        assertEquals(
            boundary(timelineAt = 20uL, messageIdHex = "before-c"),
            store.recoverySnapshot("request-b")?.nextAttemptBoundary,
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
        assertNull(store.startedRecoveryState("legacy"))
        assertEquals(NotificationReplyRecoveryLookup.Indeterminate, store.recoveryLookup("legacy"))
        assertEquals(NotificationReplyRecoveryLookup.NotStarted, store.recoveryLookup("fresh"))
    }

    @Test
    fun malformedActiveAttemptRetainsTerminalRecoveryFences() {
        val prefs = FakeSharedPreferences()
        prefs
            .edit()
            .putLong(NotificationReplyCompletionStore.startedStorageKey("legacy"), 1_000L)
            .commit()
        val store = NotificationReplyCompletionStore(prefs, nowMillis = { 1_000L })
        store.markStarted("later", "group", boundary(20uL, "before-later"))

        store.markCompleted("later")

        assertTrue(prefs.contains(NotificationReplyCompletionStore.recoverySequenceStorageKey("later")))
    }

    @Test
    fun activeAndTerminalMarkersSurviveLaterCleanup() {
        var now = 10L
        val prefs = FakeSharedPreferences()
        val store = NotificationReplyCompletionStore(prefs, nowMillis = { now })

        store.markStarted("pending", "group", boundary(timelineAt = 10uL, messageIdHex = "pending-boundary"))
        store.markCompleted("old")
        now += 8L * 24L * 60L * 60L * 1000L
        store.markCompleted("new")

        assertTrue(store.hasStarted("pending"))
        assertEquals(
            boundary(timelineAt = 10uL, messageIdHex = "pending-boundary"),
            store.recoverySnapshot("pending")?.recoveryState?.boundary,
        )
        assertTrue(store.isCompleted("old"))
        assertTrue(store.isCompleted("new"))
    }

    @Test
    fun completedFenceSurvivesWhileEarlierAttemptIsActive() {
        var now = 10L
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { now })
        store.markStarted("earlier", "group", boundary(10uL, "before-earlier"))
        store.markStarted("later", "group", boundary(20uL, "before-later"))
        store.markCompleted("later")

        now += 8L * 24L * 60L * 60L * 1000L
        store.markCompleted("unrelated")

        assertTrue(store.isCompleted("later"))
        assertEquals(boundary(20uL, "before-later"), store.recoverySnapshot("earlier")?.nextAttemptBoundary)
    }

    @Test
    fun abandonedLaterAttemptStillFencesEarlierRecovery() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })
        store.markStarted("earlier", "group", boundary(10uL, "before-earlier"))
        store.markStarted("later", "group", boundary(20uL, "before-later"))

        store.markAbandoned("later", NotificationReplyAbandonedOutcome.Failure)

        assertFalse(store.hasStarted("later"))
        assertNull(store.recoverySnapshot("later"))
        assertEquals(NotificationReplyAbandonedOutcome.Failure, store.abandonedOutcome("later"))
        assertEquals(boundary(20uL, "before-later"), store.recoverySnapshot("earlier")?.nextAttemptBoundary)
    }

    @Test
    fun abandonedAttemptDropsItsRecoveryState() {
        val store = NotificationReplyCompletionStore(FakeSharedPreferences(), nowMillis = { 1_000L })
        store.markStarted("abandoned", "group", boundary(10uL, "before"))

        store.markAbandoned("abandoned", NotificationReplyAbandonedOutcome.Success)

        assertFalse(store.hasStarted("abandoned"))
        assertNull(store.recoverySnapshot("abandoned"))
        assertEquals(NotificationReplyAbandonedOutcome.Success, store.abandonedOutcome("abandoned"))
    }

    private fun boundary(
        timelineAt: ULong,
        messageIdHex: String,
    ): NotificationReplyRecoveryBoundary {
        val hex =
            messageIdHex
                .hashCode()
                .toUInt()
                .toString(16)
                .padStart(8, '0')
        return NotificationReplyRecoveryBoundary(timelineAt, hex.repeat(8))
    }

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
