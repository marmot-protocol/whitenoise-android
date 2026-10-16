package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class PushTokenPreferencesTest {
    private lateinit var context: Context
    private lateinit var key: SecretKey
    private val lock = Any()

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        listOf("push-target", "legacy-a", "legacy-b").forEach(context::deleteSharedPreferences)
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun adapter(unavailable: Boolean = false) =
        PushTokenPreferences(
            KeystoreSecureStore(
                context, "push-target",
                object : SecureStoreKeyProvider {
                    override fun secretKey(): SecretKey {
                        if (unavailable) throw GeneralSecurityException("private key details")
                        return key
                    }
                },
            ),
            lock,
        )

    @Test
    fun importsEveryTypeAndRetainsUnknownLegacyData() {
        val legacy = context.getSharedPreferences("legacy-a", Context.MODE_PRIVATE)
        legacy.edit()
            .putString("fcm_token", "secret-token")
            .putBoolean("pending_native_push_registration_sync", true)
            .putLong("pending_push_wake_catch_up_generation", 7L)
            .putLong("push_wake_catch_up_sequence", 7L)
            .putInt("push_wake_attempts", 2)
            .putLong("push_wake_retry_at", 40_000L)
            .putStringSet("pending_clears", setOf("account-a"))
            .putStringSet("pending_native_push_disables", setOf("account-b"))
            .putString("unknown-future-value", "preserve")
            .commit()
        val before = legacy.all.filterKeys { it != "unknown-future-value" }
        adapter().importLegacy("legacy-a", legacy)
        assertEquals(before, adapter().getAll())
        assertEquals(mapOf("unknown-future-value" to "preserve"), legacy.all)
        val disk = context.getSharedPreferences("push-target", Context.MODE_PRIVATE).all.toString()
        assertFalse(disk.contains("secret-token"))
        assertFalse(disk.contains("account-a"))
    }

    @Test
    fun clearedTokenCannotReturnFromAReadableLaterSource() {
        val first = context.getSharedPreferences("legacy-a", Context.MODE_PRIVATE)
        first.edit().putString("fcm_token", "old-token").commit()
        adapter().importLegacy("first", first)
        PushTokenStore(adapter()).clear()
        val late = context.getSharedPreferences("legacy-b", Context.MODE_PRIVATE)
        late.edit().putString("fcm_token", "older-token").commit()
        adapter().importLegacy("late", late)
        assertNull(PushTokenStore(adapter()).lastToken())
    }

    @Test
    fun legacyCleanupObligationsAreUnionedBeforeNewWrites() {
        val first = context.getSharedPreferences("legacy-a", Context.MODE_PRIVATE)
        val second = context.getSharedPreferences("legacy-b", Context.MODE_PRIVATE)
        first.edit().putStringSet("pending_clears", setOf("a")).commit()
        second.edit().putStringSet("pending_clears", setOf("b")).commit()
        adapter().importLegacy("first", first)
        adapter().importLegacy("second", second)
        assertEquals(setOf("a", "b"), PushTokenStore(adapter()).pendingClears())
    }

    @Test
    fun instancesShareAtomicWakeGenerationAndTokenChanges() {
        val first = PushTokenStore(adapter())
        val second = PushTokenStore(adapter())
        first.setToken("latest")
        assertEquals("latest", second.lastToken())
        assertTrue(first.recordPendingPushWakeCatchUp())
        val old = first.pendingPushWakeCatchUpGeneration()
        assertTrue(second.recordPendingPushWakeCatchUp())
        assertFalse(first.clearPendingPushWakeCatchUp(old))
        assertTrue(first.pendingPushWakeCatchUpGeneration() > old)
    }

    @Test
    fun unavailableKeyPreservesImportsAndDoesNotPretendToBeEmpty() {
        val legacy = context.getSharedPreferences("legacy-a", Context.MODE_PRIVATE)
        legacy.edit().putStringSet("pending_clears", setOf("account-a")).commit()
        assertThrows(GeneralSecurityException::class.java) { adapter(true).importLegacy("first", legacy) }
        assertTrue(legacy.contains("pending_clears"))
        assertFalse(adapter(true).edit().putString("fcm_token", "new").commit())
        adapter().importLegacy("first", legacy)
        assertThrows(PushTokenStorageException::class.java) { adapter(true).getAll() }
        assertEquals(setOf("account-a"), PushTokenStore(adapter()).pendingClears())
    }
    @Test
    fun lateReadableLegacyCleanupIsPreservedAfterNewCleanupWrites() {
        val store = PushTokenStore(adapter())
        assertTrue(store.recordPendingDisable("new-account"))
        val legacy = context.getSharedPreferences("legacy-a", Context.MODE_PRIVATE)
        legacy.edit().putStringSet("pending_native_push_disables", setOf("old-account")).commit()
        adapter().importLegacy("late", legacy)
        assertEquals(setOf("old-account", "new-account"), store.pendingDisables())
    }

    @Test
    fun processKnownCleanupFenceClearsOnlyAfterDurableRetry() {
        var suppressed = false
        val store = PushTokenStore(
            adapter(),
            registrationSuppressed = { suppressed },
            suppressRegistration = { suppressed = true },
            cleanupSuppressed = { suppressed },
            clearRegistrationSuppression = { suppressed = false },
        )
        store.suppressRegistrationUntilReconciled("deferred-account")
        assertTrue(suppressed)
        assertTrue(store.registrationAllowed())
        assertFalse(suppressed)
        assertEquals(setOf("deferred-account"), store.pendingDisables())
    }

    @Test
    fun malformedLegacyValueIsRetainedInsteadOfMarkedImported() {
        val legacy = context.getSharedPreferences("legacy-a", Context.MODE_PRIVATE)
        legacy.edit().putString("push_wake_attempts", "invalid").commit()
        assertThrows(IllegalArgumentException::class.java) { adapter().importLegacy("bad", legacy) }
        assertFalse(adapter().hasImported("bad"))
        assertTrue(legacy.contains("push_wake_attempts"))
    }

}
