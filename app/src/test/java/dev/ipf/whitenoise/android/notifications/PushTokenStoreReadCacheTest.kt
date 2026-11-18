package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A push wake reads the push state dozens of times through short-lived stores. Each uncached read is
 * a Keystore round trip, so repeated reads must not reach the key again once the payload is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PushTokenStoreReadCacheTest {
    private lateinit var context: Context
    private var keyUses = 0
    private var keyAvailable = true

    /** Installs a counting AES key in place of the platform Keystore. */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        PushTokenStore.installKeyProvider(
            object : SecureStoreKeyProvider {
                /** Counts the request and fails it while the key is switched off. */
                override fun secretKey(): SecretKey {
                    keyUses++
                    check(keyAvailable) { "key unavailable" }
                    return key
                }
            },
        )
    }

    /** Restores the production key boundary. */
    @After
    fun tearDown() {
        PushTokenStore.installKeyProvider(null)
    }

    /** Reads through fresh stores between writes do not open the payload again. */
    @Test
    fun repeatedReadsThroughFreshStoresDoNotReachTheKeyAgain() {
        PushTokenStore.create(context).setToken("token")
        assertEquals("token", PushTokenStore.create(context).lastToken())
        val usesAfterWarmUp = keyUses
        assertTrue(usesAfterWarmUp > 0)

        repeat(10) {
            val store = PushTokenStore.create(context)
            assertEquals("token", store.lastToken())
            assertEquals(0, store.pushWakeAttempts())
            assertEquals(emptySet<String>(), store.pendingClears())
            assertEquals(emptySet<String>(), store.pendingDisables())
            assertEquals(false, store.pushWakeCatchUpPending())
        }

        assertEquals(usesAfterWarmUp, keyUses)
    }

    /** With the key gone, cached reads still answer but the recovery gate fails closed until it returns. */
    @Test
    fun recoveryGateFailsClosedWhileTheKeyIsUnavailable() {
        PushTokenStore.create(context).setToken("token")
        assertTrue(PushTokenStore.create(context).registrationAllowed())

        keyAvailable = false

        assertEquals("token", PushTokenStore.create(context).lastToken())
        assertEquals(PushStorageRecovery.StorageUnavailable, PushTokenStore.create(context).recoveryState())
        assertFalse(PushTokenStore.create(context).registrationAllowed())

        keyAvailable = true

        assertTrue(PushTokenStore.create(context).registrationAllowed())
    }

    /** A write is still sealed and is what the next read returns. */
    @Test
    fun aWriteIsSealedAndReadBack() {
        PushTokenStore.create(context).setToken("first")
        PushTokenStore.create(context).lastToken()
        val usesBeforeWrite = keyUses

        PushTokenStore.create(context).setToken("second")

        assertTrue(keyUses > usesBeforeWrite)
        assertEquals("second", PushTokenStore.create(context).lastToken())
    }
}
