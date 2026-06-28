package dev.ipf.whitenoise.android.notifications

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

class PushTokenStoreTest {
    @Test
    fun setTokenAndLastTokenRoundTrip() {
        val store = store()
        store.setToken("fcm-abc123")
        assertEquals("fcm-abc123", store.lastToken())
    }

    @Test
    fun blankTokenReadsAsNull() {
        val store = store()
        store.setToken("")
        assertNull(store.lastToken())
        store.setToken("   ")
        assertNull(store.lastToken())
    }

    @Test
    fun clearResetsLastTokenToNull() {
        val store = store()
        store.setToken("fcm-abc123")
        store.clear()
        assertNull(store.lastToken())
    }

    @Test
    fun nativePushRegistrationSyncPendingDefaultsToFalse() {
        assertEquals(false, store().nativePushRegistrationSyncPending())
    }

    @Test
    fun recordPendingNativePushRegistrationSyncSetsTheDurableFlag() {
        val store = store()
        store.recordPendingNativePushRegistrationSync()
        assertEquals(true, store.nativePushRegistrationSyncPending())
    }

    @Test
    fun clearPendingNativePushRegistrationSyncResetsTheDurableFlag() {
        val store = store()
        store.recordPendingNativePushRegistrationSync()
        store.clearPendingNativePushRegistrationSync()
        assertEquals(false, store.nativePushRegistrationSyncPending())
    }

    @Test
    fun recordPendingClearAddsTheRef() {
        val store = store()
        store.recordPendingClear("npub-a")
        assertEquals(setOf("npub-a"), store.pendingClears())
    }

    @Test
    fun recordPendingClearIsIdempotent() {
        val store = store()
        store.recordPendingClear("npub-a")
        store.recordPendingClear("npub-a")
        assertEquals(setOf("npub-a"), store.pendingClears())
    }

    @Test
    fun recordPendingClearRejectsBlankRefs() {
        val store = store()
        store.recordPendingClear("")
        store.recordPendingClear("   ")
        assertTrue(store.pendingClears().isEmpty())
    }

    @Test
    fun clearPendingRemovesTheRef() {
        val store = store()
        store.recordPendingClear("npub-a")
        store.recordPendingClear("npub-b")
        store.clearPending("npub-a")
        assertEquals(setOf("npub-b"), store.pendingClears())
    }

    @Test
    fun clearPendingForAnAbsentRefIsANoOp() {
        val store = store()
        store.recordPendingClear("npub-a")
        store.clearPending("npub-missing")
        assertEquals(setOf("npub-a"), store.pendingClears())
    }

    @Test
    fun pendingClearsReturnsADefensiveCopy() {
        val store = store()
        // Two refs so the .toSet() copy is a mutable LinkedHashSet (a single
        // element collapses to an immutable singleton); we want a mutable handle
        // to prove the mutation lands on the copy, not the store.
        store.recordPendingClear("npub-a")
        store.recordPendingClear("npub-b")

        // Mutating the returned set must not leak into the store; pendingClears()
        // defends against SharedPreferences.getStringSet sharing its backing
        // instance by returning a .toSet() copy. The fake deliberately returns
        // the live backing set, so a regression that dropped the copy would let
        // this mutation corrupt the store and fail the re-read below.
        @Suppress("UNCHECKED_CAST")
        (store.pendingClears() as MutableSet<String>).add("npub-injected")

        assertEquals(setOf("npub-a", "npub-b"), store.pendingClears())
    }

    @Test
    fun recordPendingDisableAddsTheRef() {
        val store = store()
        store.recordPendingDisable("npub-a")
        assertEquals(setOf("npub-a"), store.pendingDisables())
    }

    @Test
    fun recordPendingDisableIsIdempotent() {
        val store = store()
        store.recordPendingDisable("npub-a")
        store.recordPendingDisable("npub-a")
        assertEquals(setOf("npub-a"), store.pendingDisables())
    }

    @Test
    fun recordPendingDisableRejectsBlankRefs() {
        val store = store()
        store.recordPendingDisable("")
        store.recordPendingDisable("   ")
        assertTrue(store.pendingDisables().isEmpty())
    }

    @Test
    fun clearPendingDisableRemovesTheRef() {
        val store = store()
        store.recordPendingDisable("npub-a")
        store.recordPendingDisable("npub-b")
        store.clearPendingDisable("npub-a")
        assertEquals(setOf("npub-b"), store.pendingDisables())
    }

    @Test
    fun clearPendingDisableForAnAbsentRefIsANoOp() {
        val store = store()
        store.recordPendingDisable("npub-a")
        store.clearPendingDisable("npub-missing")
        assertEquals(setOf("npub-a"), store.pendingDisables())
    }

    @Test
    fun pendingDisablesReturnsADefensiveCopy() {
        val store = store()
        store.recordPendingDisable("npub-a")
        store.recordPendingDisable("npub-b")

        @Suppress("UNCHECKED_CAST")
        (store.pendingDisables() as MutableSet<String>).add("npub-injected")

        assertEquals(setOf("npub-a", "npub-b"), store.pendingDisables())
    }

    @Test
    fun clearsAndDisablesAreTrackedIndependently() {
        val store = store()
        store.recordPendingClear("npub-a")
        store.recordPendingDisable("npub-b")
        assertEquals(setOf("npub-a"), store.pendingClears())
        assertEquals(setOf("npub-b"), store.pendingDisables())
    }

    // #167 regression: onNewToken runs on a Firebase background thread, so the
    // record/clear read-modify-write can race a concurrent sign-out. The fix is
    // the process-wide synchronized(LOCK) in PushTokenStore. These tests use a
    // fresh store instance per thread over one shared prefs file — exactly the
    // shape #167 describes — so they also assert the lock is companion-scoped
    // (a per-instance lock would serialize nothing across them). Without the
    // lock the RMW loses updates and the asserted set state is wrong.

    @Test
    fun concurrentRecordPendingClearKeepsEveryUpdate() {
        val prefs = FakeSharedPreferences()
        val threads = 16
        val perThread = 64
        val start = CountDownLatch(1)

        val workers =
            (0 until threads).map { t ->
                Thread {
                    start.await()
                    val store = PushTokenStore(prefs)
                    for (i in 0 until perThread) store.recordPendingClear("acct-$t-$i")
                }.apply { start() }
            }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(threads * perThread, PushTokenStore(prefs).pendingClears().size)
    }

    @Test
    fun concurrentClearPendingRemovesEveryRef() {
        val prefs = FakeSharedPreferences()
        val refs = (0 until 512).map { "acct-$it" }
        val seed = PushTokenStore(prefs)
        refs.forEach { seed.recordPendingClear(it) }

        val start = CountDownLatch(1)
        val workers =
            refs.chunked(32).map { chunk ->
                Thread {
                    start.await()
                    val store = PushTokenStore(prefs)
                    chunk.forEach { store.clearPending(it) }
                }.apply { start() }
            }
        start.countDown()
        workers.forEach { it.join() }

        assertTrue(PushTokenStore(prefs).pendingClears().isEmpty())
    }

    @Test
    fun concurrentRecordsToClearsAndDisablesDoNotCrossContaminate() {
        val prefs = FakeSharedPreferences()
        val n = 256
        val start = CountDownLatch(1)

        val clearer =
            Thread {
                start.await()
                val store = PushTokenStore(prefs)
                for (i in 0 until n) store.recordPendingClear("clear-$i")
            }.apply { start() }
        val disabler =
            Thread {
                start.await()
                val store = PushTokenStore(prefs)
                for (i in 0 until n) store.recordPendingDisable("disable-$i")
            }.apply { start() }
        start.countDown()
        clearer.join()
        disabler.join()

        val store = PushTokenStore(prefs)
        assertEquals(n, store.pendingClears().size)
        assertEquals(n, store.pendingDisables().size)
        assertTrue(store.pendingClears().none { it.startsWith("disable-") })
        assertTrue(store.pendingDisables().none { it.startsWith("clear-") })
    }

    private fun store() = PushTokenStore(FakeSharedPreferences())

    /**
     * In-memory [SharedPreferences] for JVM unit tests — no Robolectric. Only the
     * surface [PushTokenStore] touches (getString / getStringSet / edit with
     * putString, putStringSet, remove, apply) carries real behavior; the rest
     * are inert. Backed by a synchronized map so the only race left for the
     * concurrency tests to expose is PushTokenStore's own read-modify-write, not
     * corruption inside the fake. [getStringSet] returns the live backing set
     * (the documented Android sharing hazard) on purpose, so the defensive-copy
     * tests genuinely exercise pendingClears()/pendingDisables().
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val values: MutableMap<String, Any?> = Collections.synchronizedMap(HashMap())

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = (values[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = (values[key] as? MutableSet<String>) ?: defValues

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun getAll(): MutableMap<String, *> = values

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

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

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                // Store an independent copy so the editor input can't alias the
                // backing set; the store owns its own instance thereafter.
                puts[key] = values?.let { LinkedHashSet(it) }
                return this
            }

            override fun putInt(
                key: String,
                value: Int,
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

            override fun putFloat(
                key: String,
                value: Float,
            ): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ): SharedPreferences.Editor {
                puts[key] = value
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removals += key
                return this
            }

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
                    removals.forEach { values.remove(it) }
                    values.putAll(puts)
                }
                puts.clear()
                removals.clear()
            }
        }
    }
}
