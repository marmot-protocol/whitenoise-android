package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Exercises the real import owner with platform preference counters and a memory-changing failed commit. */
@RunWith(RobolectricTestRunner::class)
class PushLegacyImportAdmissionTest {
    private lateinit var context: CountingContext
    private lateinit var key: SecretKey

    @Before
    fun prepare() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        (SOURCES + TARGET + "whitenoise.push.safety").forEach { name ->
            // Clear cached preferences as well as disk, matching the other import fixtures.
            val preferences = base.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertTrue(preferences.edit().clear().commit())
            assertTrue(preferences.all.isEmpty())
            base.deleteSharedPreferences(name)
        }
        context = CountingContext(base)
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        PushTokenStore.installKeyProvider(provider())
        SOURCES.forEach { assertFalse(target().hasImported(it)) }
        assertTrue(base.getSharedPreferences("whitenoise.push.safety", Context.MODE_PRIVATE).all.isEmpty())
    }

    @After
    fun restoreKeyProvider() {
        PushTokenStore.installKeyProvider(null)
    }

    /** A persisted receipt is observation-only and bypasses even an unreadable encrypted leftover. */
    @Test
    fun acknowledgedReceiptsSkipEveryLegacyOpenAndTargetRewriteAcrossFreshStores() {
        val target = target()
        SOURCES.forEach { source ->
            val legacy = context.getSharedPreferences(source, Context.MODE_PRIVATE)
            legacy.edit().putString(KEY_FCM_TOKEN, "retained-token").commit()
            target.importLegacy(source, legacy)
        }
        context.resetCounts()
        repeat(3) {
            val store = PushTokenStore.create(context)
            assertEquals(PushStorageRecovery.Ready, store.recoveryState())
            assertEquals("retained-token", store.lastToken())
        }
        assertEquals(0, context.targetEdits)
        assertEquals(0, context.legacyOpens.values.sum())
        SOURCES.forEach { source ->
            assertFalse(File(context.dataDir, "shared_prefs/$source.xml").exists())
            assertTrue(context.deletions.getValue(source) > 0)
        }
    }

    /** Read-only receipt checks do not produce an encrypt-and-commit round even on a fresh adapter. */
    @Test
    fun hasImportedReadsTheReceiptWithoutEditingTargetPreferences() {
        val legacy = context.getSharedPreferences(SOURCES.last(), Context.MODE_PRIVATE)
        legacy.edit().putString(KEY_FCM_TOKEN, "retained-token").commit()
        target().importLegacy(SOURCES.last(), legacy)
        context.resetCounts()
        repeat(10) { assertTrue(target().hasImported(SOURCES.last())) }
        assertEquals(0, context.targetEdits)
    }

    /** Safe quarantine survives time and fresh owners, but an explicit Retry can import repaired bytes. */
    @Test
    fun readyQuarantineIsNotReopenedUntilExplicitRetry() {
        val sourceName = SOURCES[1]
        val legacy = context.getSharedPreferences(sourceName, Context.MODE_PRIVATE)
        legacy
            .edit()
            .putString(KEY_WAKE_ATTEMPTS, "malformed")
            .putStringSet(KEY_PENDING_CLEARS, emptySet())
            .commit()
        val before = legacy.all
        val first = PushTokenStore.create(context)
        assertEquals(PushStorageRecovery.Ready, first.recoveryState())
        val opened = context.legacyOpens.getValue(sourceName)
        repeat(3) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
            assertEquals(PushStorageRecovery.Ready, first.recoveryState())
            assertEquals(PushStorageRecovery.Ready, PushTokenStore.create(context).recoveryState())
        }
        assertEquals(opened, context.legacyOpens.getValue(sourceName))
        assertEquals(before, legacy.all)
        assertFalse(target().hasImported(sourceName))
        legacy.edit().remove(KEY_WAKE_ATTEMPTS).commit()
        assertEquals(PushStorageRecovery.Ready, first.recoveryState(retry = true))
        assertTrue(context.legacyOpens.getValue(sourceName) > opened)
        assertTrue(target().hasImported(sourceName))
        assertFalse(File(context.dataDir, "shared_prefs/$sourceName.xml").exists())
    }

    /** A receipt exposed by commit=false cannot retire cleanup obligations through another store instance. */
    @Test
    fun failedCommitReceiptRemainsUnconfirmedAcrossFreshStoreUntilDurableRetry() {
        val sourceName = SOURCES[1]
        val legacy = context.getSharedPreferences(sourceName, Context.MODE_PRIVATE)
        legacy
            .edit()
            .putString(KEY_FCM_TOKEN, "retained-token")
            .putStringSet(KEY_PENDING_CLEARS, setOf("account-a"))
            .commit()
        context.failTargetCommit = true
        assertEquals(PushStorageRecovery.LegacyImportPending, PushTokenStore.create(context).recoveryState())
        assertTrue(context.failedTargetCommits > 0)
        assertTrue(legacy.contains(KEY_FCM_TOKEN))
        context.resetCounts()
        val fresh = PushTokenStore.create(context)
        assertEquals(PushStorageRecovery.LegacyImportPending, fresh.recoveryState())
        assertFalse(fresh.registrationAllowed())
        assertEquals(0, context.deletions[sourceName] ?: 0)
        assertEquals(setOf("account-a"), legacy.getStringSet(KEY_PENDING_CLEARS, emptySet()))
        context.failTargetCommit = false
        assertEquals(PushStorageRecovery.Ready, fresh.recoveryState(retry = true))
        assertEquals("retained-token", fresh.lastToken())
        assertEquals(setOf("account-a"), fresh.pendingClears())
        assertTrue(context.deletions.getValue(sourceName) > 0)
        assertFalse(File(context.dataDir, "shared_prefs/$sourceName.xml").exists())
    }

    private fun provider(): SecureStoreKeyProvider =
        object : SecureStoreKeyProvider {
            override fun secretKey(): SecretKey = key
        }

    private fun target() = PushTokenPreferences(KeystoreSecureStore(context, TARGET, provider()), Any())

    /** Counts actual platform opens/writes while retaining the same application storage identity. */
    private class CountingContext(
        base: Context,
    ) : ContextWrapper(base) {
        val legacyOpens = mutableMapOf<String, Int>()
        val deletions = mutableMapOf<String, Int>()
        var targetEdits = 0
        var failTargetCommit = false
        var failedTargetCommits = 0

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences {
            if (name in SOURCES) legacyOpens[name] = (legacyOpens[name] ?: 0) + 1
            val preferences = super.getSharedPreferences(name, mode)
            if (name != TARGET) return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    targetEdits += 1
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String,
                            value: String?,
                        ): SharedPreferences.Editor = apply { editor.putString(key, value) }

                        override fun commit(): Boolean {
                            if (failTargetCommit) {
                                failedTargetCommits += 1
                                editor.apply() // Android can publish the new receipt in memory despite commit=false.
                                return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }

        override fun deleteSharedPreferences(name: String): Boolean {
            deletions[name] = (deletions[name] ?: 0) + 1
            return super.deleteSharedPreferences(name)
        }

        fun resetCounts() {
            legacyOpens.clear()
            deletions.clear()
            targetEdits = 0
        }
    }

    private companion object {
        const val TARGET = "whitenoise.push.tokens.keystore"
        val SOURCES =
            listOf("whitenoise.push.tokens.secure", "whitenoise.push.tokens.fallback", "whitenoise.push.tokens")
    }
}
