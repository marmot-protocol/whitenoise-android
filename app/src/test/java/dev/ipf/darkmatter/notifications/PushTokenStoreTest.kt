package dev.ipf.darkmatter.notifications

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class PushTokenStoreTest {
    @Test
    fun setTokenRoundTripsThroughLastToken() {
        val store = PushTokenStore(FakeSharedPreferences())

        store.setToken("fcm-token-1")

        assertEquals("fcm-token-1", store.lastToken())
    }

    @Test
    fun blankTokenIsFilteredFromLastToken() {
        val store = PushTokenStore(FakeSharedPreferences())

        store.setToken("")

        assertNull(store.lastToken())
    }

    @Test
    fun clearRemovesLastToken() {
        val store = PushTokenStore(FakeSharedPreferences())
        store.setToken("fcm-token-1")

        store.clear()

        assertNull(store.lastToken())
    }

    @Test
    fun pendingClearsHonorApiContracts() {
        val preferences = FakeSharedPreferences()
        val store = PushTokenStore(preferences)

        store.recordPendingClear("account-a")
        assertEquals(setOf("account-a"), store.pendingClears())
        assertEquals(1, preferences.stringSetWriteCount())

        store.recordPendingClear("account-a")
        store.recordPendingClear("")
        store.recordPendingClear("  ")
        store.clearPending("missing")
        store.clearPending("")
        store.clearPending("  ")
        assertEquals(setOf("account-a"), store.pendingClears())
        assertEquals(1, preferences.stringSetWriteCount())

        store.clearPending("account-a")
        assertEquals(emptySet<String>(), store.pendingClears())
        assertEquals(2, preferences.stringSetWriteCount())
    }

    @Test
    fun pendingClearsReturnsDefensiveCopy() {
        val store = PushTokenStore(FakeSharedPreferences())
        store.recordPendingClear("account-a")
        store.recordPendingClear("account-b")

        val returned = store.pendingClears()
        @Suppress("UNCHECKED_CAST")
        (returned as MutableSet<String>).add("account-c")

        assertEquals(setOf("account-a", "account-b"), store.pendingClears())
    }

    @Test
    fun pendingDisablesHonorApiContracts() {
        val preferences = FakeSharedPreferences()
        val store = PushTokenStore(preferences)

        store.recordPendingDisable("account-a")
        assertEquals(setOf("account-a"), store.pendingDisables())
        assertEquals(1, preferences.stringSetWriteCount())

        store.recordPendingDisable("account-a")
        store.recordPendingDisable("")
        store.recordPendingDisable("  ")
        store.clearPendingDisable("missing")
        store.clearPendingDisable("")
        store.clearPendingDisable("  ")
        assertEquals(setOf("account-a"), store.pendingDisables())
        assertEquals(1, preferences.stringSetWriteCount())

        store.clearPendingDisable("account-a")
        assertEquals(emptySet<String>(), store.pendingDisables())
        assertEquals(2, preferences.stringSetWriteCount())
    }

    @Test
    fun pendingDisablesReturnsDefensiveCopy() {
        val store = PushTokenStore(FakeSharedPreferences())
        store.recordPendingDisable("account-a")
        store.recordPendingDisable("account-b")

        val returned = store.pendingDisables()
        @Suppress("UNCHECKED_CAST")
        (returned as MutableSet<String>).add("account-c")

        assertEquals(setOf("account-a", "account-b"), store.pendingDisables())
    }

    @Test
    fun pendingClearsSurviveConcurrentRecordsAndClearsAcrossStoreInstances() {
        val preferences = FakeSharedPreferences(writeDelayMillis = 2)
        val accounts = (0 until 64).map { "account-$it" }

        runConcurrently(accounts) { account ->
            PushTokenStore(preferences).recordPendingClear(account)
        }
        assertEquals(accounts.toSet(), PushTokenStore(preferences).pendingClears())

        runConcurrently(accounts.withIndex().toList()) { (index, account) ->
            val store = PushTokenStore(preferences)
            if (index % 2 == 0) {
                store.clearPending(account)
            } else {
                store.recordPendingClear(account)
            }
        }

        val expectedRemaining =
            accounts
                .withIndex()
                .filter { it.index % 2 == 1 }
                .map { it.value }
                .toSet()
        assertEquals(expectedRemaining, PushTokenStore(preferences).pendingClears())
    }

    private fun <T> runConcurrently(
        items: List<T>,
        action: (T) -> Unit,
    ) {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(min(items.size, 16))
        try {
            val futures =
                items.map { item ->
                    executor.submit {
                        start.await()
                        action(item)
                    }
                }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class FakeSharedPreferences(
        private val writeDelayMillis: Long = 0,
    ) : SharedPreferences {
        private val lock = Any()
        private val values = mutableMapOf<String, Any>()
        private var stringSetWrites = 0

        fun stringSetWriteCount(): Int = synchronized(lock) { stringSetWrites }

        override fun getAll(): MutableMap<String, *> = synchronized(lock) { values.toMutableMap() }

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = synchronized(lock) { values[key] as? String ?: defValue }

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            synchronized(lock) {
                @Suppress("UNCHECKED_CAST")
                values[key] as? MutableSet<String> ?: defValues
            }

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = synchronized(lock) { values[key] as? Int ?: defValue }

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = synchronized(lock) { values[key] as? Long ?: defValue }

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = synchronized(lock) { values[key] as? Float ?: defValue }

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) updates[key] = value
                }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) updates[key] = values?.toMutableSet()
                }

            override fun putInt(
                key: String?,
                value: Int,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) updates[key] = value
                }

            override fun putLong(
                key: String?,
                value: Long,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) updates[key] = value
                }

            override fun putFloat(
                key: String?,
                value: Float,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) updates[key] = value
                }

            override fun putBoolean(
                key: String?,
                value: Boolean,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) updates[key] = value
                }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply {
                    if (key != null) removals += key
                }

            override fun clear(): SharedPreferences.Editor =
                apply {
                    clearRequested = true
                }

            override fun commit(): Boolean {
                if (writeDelayMillis > 0) Thread.sleep(writeDelayMillis)
                synchronized(lock) {
                    if (clearRequested) values.clear()
                    removals.forEach { values.remove(it) }
                    updates.forEach { (key, value) ->
                        if (value == null) {
                            values.remove(key)
                        } else {
                            if (value is MutableSet<*>) stringSetWrites += 1
                            values[key] = value
                        }
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
