package dev.ipf.whitenoise.android.state

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Robolectric has no AndroidKeyStore, so these exercise the envelope, storage,
 * and corruption behavior against an in-memory AES key. The Keystore-backed
 * key provider itself is covered by the instrumented suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeystoreSecureStoreTest {
    private lateinit var context: Context
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(FILE)
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun store(secret: SecretKey = key) =
        KeystoreSecureStore(
            context = context,
            fileName = FILE,
            keyProvider =
                object : SecureStoreKeyProvider {
                    override fun secretKey(): SecretKey = secret
                },
        )

    @Test
    fun writesRoundTripAcrossInstances() {
        store().write("a", "first")
        store().write("b", "second")

        assertEquals(mapOf("a" to "first", "b" to "second"), store().readAll())
    }

    @Test
    fun emptyStoreReadsAsEmptyRatherThanFailing() {
        assertEquals(emptyMap<String, String>(), store().readAll())
    }

    @Test
    fun nullValueRemovesTheEntry() {
        val subject = store()
        subject.write("a", "first")
        subject.write("b", "second")

        subject.write("a", null)

        assertEquals(mapOf("b" to "second"), store().readAll())
    }

    @Test
    fun putAllDurablyMergesAndReportsSuccess() {
        store().write("keep", "value")

        assertTrue(store().putAllDurably(mapOf("x" to "1", "y" to "2")))

        assertEquals(mapOf("keep" to "value", "x" to "1", "y" to "2"), store().readAll())
    }

    @Test
    fun nonBase64PayloadSurfacesAsASecurityFailureRatherThanCrashing() {
        // Base64.decode throws IllegalArgumentException, and DraftStore reads
        // inside its constructor via the Application's `appState` lazy — a
        // non-GeneralSecurityException escaping here would crash-loop the app
        // instead of routing into the store's recovery path.
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString("payload", "!!! not base64 !!!").commit()

        assertThrows(GeneralSecurityException::class.java) { store().readAll() }
        assertThrows(GeneralSecurityException::class.java) { store().write("a", "b") }
    }

    @Test
    fun emptyPayloadStringSurfacesAsASecurityFailure() {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString("payload", "").commit()

        assertThrows(GeneralSecurityException::class.java) { store().readAll() }
    }

    @Test
    fun logicalKeyNamesAreNotStoredInTheClear() {
        // EncryptedSharedPreferences encrypted key names too; a per-entry
        // scheme would leak names like group ids, so the whole map is sealed.
        store().write("group-id-abcdef", "draft text")

        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
        assertEquals(setOf("payload"), raw.keys)
        val sealed = raw.getValue("payload").toString()
        assertTrue("key name leaked", !sealed.contains("group-id-abcdef"))
        assertTrue("value leaked", !sealed.contains("draft text"))
    }

    @Test
    fun aDifferentKeyCannotDecryptThePayload() {
        store().write("a", "first")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThrows(GeneralSecurityException::class.java) { store(otherKey).readAll() }
    }

    @Test
    fun tamperedCiphertextIsRejectedByTheAuthenticationTag() {
        store().write("a", "first")
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = Base64.decode(prefs.getString("payload", null)!!, Base64.NO_WRAP)
        // Flip a bit INSIDE the ciphertext and re-encode, so the payload stays
        // valid Base64 of the right length: the only thing that can reject it
        // is the GCM tag. Mutating the encoded string's last character would
        // usually hit the '=' padding and prove only decoder strictness.
        raw[IV_BYTES + 1] = (raw[IV_BYTES + 1].toInt() xor 0x01).toByte()
        prefs.edit().putString("payload", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()

        assertThrows(AEADBadTagException::class.java) { store().readAll() }
    }

    @Test
    fun tamperedIvIsRejectedByTheAuthenticationTag() {
        store().write("a", "first")
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = Base64.decode(prefs.getString("payload", null)!!, Base64.NO_WRAP)
        // GCM authenticates the nonce too, so relocating the payload by
        // rewriting its IV must fail rather than decrypt to something else.
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        prefs.edit().putString("payload", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()

        assertThrows(AEADBadTagException::class.java) { store().readAll() }
    }

    @Test
    fun truncatedPayloadIsRejected() {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString("payload", "AAAA").commit()

        assertThrows(GeneralSecurityException::class.java) { store().readAll() }
    }

    @Test
    fun clearDropsEverything() {
        store().write("a", "first")

        store().clear()

        assertEquals(emptyMap<String, String>(), store().readAll())
    }

    private companion object {
        const val FILE = "test.keystore.store"
        const val IV_BYTES = 12
    }
}
