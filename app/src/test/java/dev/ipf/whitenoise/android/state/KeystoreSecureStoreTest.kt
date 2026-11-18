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

    /** Counts key requests, one per Keystore-backed open or seal. */
    private class CountingKeyProvider(
        private val secret: SecretKey,
    ) : SecureStoreKeyProvider {
        var uses = 0

        /** Hands out the key and counts the request. */
        override fun secretKey(): SecretKey = secret.also { uses++ }
    }

    /** A store over the shared test file with the read cache on. */
    private fun cachingStore(provider: SecureStoreKeyProvider) =
        KeystoreSecureStore(context = context, fileName = FILE, keyProvider = provider, cacheReads = true)

    /** A caching store opens an unchanged payload once, however often it is read. */
    @Test
    fun cachingStoreOpensAnUnchangedPayloadOnce() {
        store().write("a", "first")
        val provider = CountingKeyProvider(key)
        val subject = cachingStore(provider)

        repeat(5) { assertEquals(mapOf("a" to "first"), subject.readAll()) }

        assertEquals(1, provider.uses)
    }

    /** The default store keeps opening the payload on every read, so only opted-in stores hold plaintext. */
    @Test
    fun defaultStoreOpensThePayloadOnEveryRead() {
        store().write("a", "first")
        val provider = CountingKeyProvider(key)
        val subject = KeystoreSecureStore(context = context, fileName = FILE, keyProvider = provider)

        repeat(3) { subject.readAll() }

        assertEquals(3, provider.uses)
    }

    /** Short-lived caching stores over one file and key provider share a single open. */
    @Test
    fun cachingStoresWithOneKeyProviderShareAnOpen() {
        store().write("a", "first")
        val provider = CountingKeyProvider(key)

        repeat(4) { assertEquals(mapOf("a" to "first"), cachingStore(provider).readAll()) }

        assertEquals(1, provider.uses)
    }

    /** A payload written through another store is opened again instead of served from the cache. */
    @Test
    fun cachingStoreSeesAWriteMadeThroughAnotherStore() {
        store().write("a", "first")
        val provider = CountingKeyProvider(key)
        val subject = cachingStore(provider)
        subject.readAll()

        store().write("a", "second")

        assertEquals(mapOf("a" to "second"), subject.readAll())
        assertEquals(2, provider.uses)
    }

    /** A caching store reads back what it just sealed without opening it, for every write path. */
    @Test
    fun cachingStoreReadsItsOwnWritesWithoutOpeningThem() {
        val provider = CountingKeyProvider(key)
        val subject = cachingStore(provider)

        subject.write("a", "first")
        assertEquals(mapOf("a" to "first"), subject.readAll())
        assertTrue(subject.putAllDurably(mapOf("b" to "second")))
        assertEquals(mapOf("a" to "first", "b" to "second"), subject.readAll())
        assertTrue(subject.replaceAllDurably(mapOf("c" to "third")))
        assertEquals(mapOf("c" to "third"), subject.readAll())

        assertEquals(3, provider.uses)
        assertEquals(mapOf("c" to "third"), store().readAll())
    }

    /** What a caching store serves after a write equals what a fresh open of the same payload yields. */
    @Test
    fun cachedWriteMatchesAFreshOpenForTextThatDoesNotSurviveEncoding() {
        val subject = cachingStore(CountingKeyProvider(key))

        subject.write("a", "lone\uD83D surrogate")

        assertEquals(store().readAll(), subject.readAll())
    }

    /** A caller cannot change the map a caching store hands out, so later reads stay what was sealed. */
    @Test
    fun cachedMapCannotBeModifiedByACaller() {
        store().write("a", "first")
        store().write("b", "second")
        val subject = cachingStore(CountingKeyProvider(key))

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (subject.readAll() as MutableMap<String, String>)["a"] = "changed"
        }

        assertEquals(mapOf("a" to "first", "b" to "second"), subject.readAll())
    }

    /** A cached payload is never served to a store holding a different key. */
    @Test
    fun cachedPayloadIsNotServedToAnotherKey() {
        store().write("a", "first")
        cachingStore(CountingKeyProvider(key)).readAll()
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThrows(GeneralSecurityException::class.java) { cachingStore(CountingKeyProvider(otherKey)).readAll() }
    }

    /** A store with the cache off never fills the cache a caching store reads from. */
    @Test
    fun storeWithTheCacheOffDoesNotFillTheSharedCache() {
        val provider = CountingKeyProvider(key)
        val plain = KeystoreSecureStore(context = context, fileName = FILE, keyProvider = provider)
        plain.write("a", "secret")
        plain.readAll()
        val usesBefore = provider.uses

        assertEquals(mapOf("a" to "secret"), cachingStore(provider).readAll())

        assertEquals(usesBefore + 1, provider.uses)
    }

    /** A store with the cache off is never served from a caching store's cache. */
    @Test
    fun storeWithTheCacheOffIsNotServedFromTheSharedCache() {
        val provider = CountingKeyProvider(key)
        cachingStore(provider).write("a", "secret")
        val usesBefore = provider.uses

        KeystoreSecureStore(context = context, fileName = FILE, keyProvider = provider).readAll()

        assertEquals(usesBefore + 1, provider.uses)
    }

    /** After a clear, the same ciphertext put back is opened again, so the cached copy was dropped. */
    @Test
    fun clearingDropsTheCachedPayload() {
        val provider = CountingKeyProvider(key)
        val subject = cachingStore(provider)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val clears = listOf<() -> Unit>({ subject.clear() }, { subject.clearDurably() }, { subject.readAll() })
        for (clear in clears) {
            subject.write("a", "first")
            val sealed = prefs.getString("payload", null)
            prefs.edit().remove("payload").commit()
            clear()
            prefs.edit().putString("payload", sealed).commit()
            val usesBefore = provider.uses

            assertEquals(mapOf("a" to "first"), subject.readAll())

            assertEquals(usesBefore + 1, provider.uses)
        }
    }

    /** A full-length payload the cipher rejects fails on every read and is never cached as a value. */
    @Test
    fun rejectedPayloadIsNotCached() {
        val subject = cachingStore(CountingKeyProvider(key))
        subject.write("a", "first")
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = Base64.decode(prefs.getString("payload", null), Base64.NO_WRAP)
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        prefs.edit().putString("payload", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()

        assertThrows(GeneralSecurityException::class.java) { subject.readAll() }
        assertThrows(GeneralSecurityException::class.java) { subject.readAll() }
    }

    /** A fresh read reaches the key even for a cached payload, so a broken key is noticed. */
    @Test
    fun freshReadOpensACachedPayloadAgain() {
        store().write("a", "first")
        val provider = CountingKeyProvider(key)
        val subject = cachingStore(provider)
        subject.readAll()

        assertEquals(mapOf("a" to "first"), subject.readAll(fresh = true))
        assertEquals(mapOf("a" to "first"), subject.readAll())

        assertEquals(2, provider.uses)
    }

    private companion object {
        const val FILE = "test.keystore.store"
        const val IV_BYTES = 12
    }
}
