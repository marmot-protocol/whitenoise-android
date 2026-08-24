package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLockPreferencesTest {
    private lateinit var context: Context
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(SECURE_FILE)
        context.deleteSharedPreferences(LEGACY_SECURE_FILE)
        legacyPrefsFile().delete()
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun readReturnsZeroWhenNeverWritten() {
        assertEquals(0L, AppLockPreferences.readLastUnlockedAtMillis(context, testStore()))
    }

    @Test
    fun writeLastUnlockedAtMillisRoundTrips() {
        val store = testStore()
        AppLockPreferences.writeLastUnlockedAtMillis(context, 1_700_000_123L, store)

        assertEquals(1_700_000_123L, AppLockPreferences.readLastUnlockedAtMillis(context, store))
    }

    @Test
    fun readReturnsZeroAfterStoreCorruption() {
        val store = testStore()
        AppLockPreferences.writeLastUnlockedAtMillis(context, 42L, store)
        context
            .getSharedPreferences(SECURE_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("payload", "not-base64")
            .commit()

        assertEquals(0L, AppLockPreferences.readLastUnlockedAtMillis(context, store))
    }

    @Test
    fun legacySecureTimestampImportsIntoKeystoreStore() {
        context
            .getSharedPreferences(LEGACY_SECURE_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("marker", "1")
            .commit()
        val legacyReader: (Context, String) -> Map<String, String>? = { ctx, _ ->
            ctx
                .getSharedPreferences("legacy-import", Context.MODE_PRIVATE)
                .apply {
                    edit().putLong(LAST_UNLOCKED_AT_KEY, 9_876L).commit()
                }.all
                .mapValues { (_, value) -> value.toString() }
        }

        assertEquals(
            9_876L,
            AppLockPreferences.readLastUnlockedAtMillis(
                context,
                testStore(),
                legacyReader = legacyReader,
            ),
        )
        assertFalse(legacyPrefsFile().exists())
    }

    @Test
    fun unreadableLegacyFileIsDroppedWithoutBlockingUnlockReads() {
        context
            .getSharedPreferences(LEGACY_SECURE_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("marker", "1")
            .commit()
        val legacyReader: (Context, String) -> Map<String, String>? = { _, _ ->
            throw java.security.GeneralSecurityException("corrupt legacy payload")
        }
        val store = testStore()

        AppLockPreferences.writeLastUnlockedAtMillis(context, 55L, store, legacyReader = legacyReader)

        assertEquals(55L, AppLockPreferences.readLastUnlockedAtMillis(context, store, legacyReader = legacyReader))
        assertFalse(legacyPrefsFile().exists())
    }

    private fun testStore(): KeystoreSecureStore =
        KeystoreSecureStore(
            context = context,
            fileName = SECURE_FILE,
            keyProvider =
                object : SecureStoreKeyProvider {
                    override fun secretKey(): SecretKey = key
                },
        )

    private fun legacyPrefsFile(): File =
        File(
            File(context.applicationInfo.dataDir, "shared_prefs"),
            "$LEGACY_SECURE_FILE.xml",
        )

    private companion object {
        const val SECURE_FILE = "whitenoise.app_lock.keystore"
        const val LEGACY_SECURE_FILE = "whitenoise.app_lock.secure"
        const val LAST_UNLOCKED_AT_KEY = "last_unlocked_at_millis"
    }
}
