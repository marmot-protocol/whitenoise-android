package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted no-backup pending-forward entry must restore the complete
 * unresolved request — source owner, immutable payload identity and order,
 * selected destination owner, and chat selections — across process
 * recreation, without ever exposing plaintext on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrossAccountForwardProcessRecreationTest {
    private lateinit var dir: File
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    /** Creates a fresh temp directory for the encrypted store. */
    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pending-forward-store-test").toFile()
    }

    /** Deletes the temp store directory. */
    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Builds the encrypted store over the temp directory with a fixed key. */
    private fun store(): EncryptedPendingForwardRequestStore =
        EncryptedPendingForwardRequestStore(
            DiskByteCache(dir, maxBytes = 512 * 1024, keyProvider = keyProvider),
        )

    /** Builds one complete media attachment reference. */
    private fun reference(fileName: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "image/png",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 4uL,
            dim = "800x600",
            thumbhash = "hash",
        )

    /** Builds one pending request with ordered text and media payloads. */
    private fun request(
        requestId: String = "request-1",
        destination: String? = "account-b",
        selected: List<String> = listOf("11".repeat(32), "22".repeat(32)),
    ) = PendingForwardRequest(
        requestId = requestId,
        sourceAccountRef = "account-a",
        originGroupIdHex = "aa".repeat(32),
        payloads =
            listOf(
                ForwardMessagePayload.Text(
                    sourceGroupIdHex = "aa".repeat(32),
                    sourceMessageIdHex = "01".repeat(32),
                    text = "private forwarded text",
                ),
                ForwardMessagePayload.Media(
                    sourceGroupIdHex = "aa".repeat(32),
                    sourceMessageIdHex = "02".repeat(32),
                    caption = "private caption",
                    expiresAtSeconds = 1234uL,
                    attachments =
                        listOf(
                            ForwardAttachmentSource(0, reference("first.png")),
                            ForwardAttachmentSource(1, reference("second.png")),
                        ),
                ),
            ),
        destinationAccountRef = destination,
        selectedGroupIds = selected,
    )

    /** A request round-trips across store recreation with identity and order intact. */
    @Test
    fun requestRoundTripsAcrossStoreRecreationPreservingIdentityAndOrder() {
        val request = request()
        assertTrue(store().save(request))

        val restored = store().load()

        assertEquals(request, restored)
        assertEquals(
            listOf("01".repeat(32), "02".repeat(32)),
            restored!!.payloads.map(ForwardMessagePayload::sourceMessageIdHex),
        )
        val media = restored.payloads[1] as ForwardMessagePayload.Media
        assertEquals(listOf(0, 1), media.attachments.map(ForwardAttachmentSource::attachmentIndex))
        assertEquals(listOf("first.png", "second.png"), media.attachments.map { it.reference.fileName })
    }

    /** No message text, caption, or account ref appears unencrypted on disk. */
    @Test
    fun plaintextNeverReachesDiskUnencrypted() {
        assertTrue(store().save(request()))

        val onDisk =
            dir
                .walkTopDown()
                .filter(File::isFile)
                .joinToString("") { it.readBytes().decodeToString() }

        assertFalse("private forwarded text" in onDisk)
        assertFalse("private caption" in onDisk)
        assertFalse("account-a" in onDisk)
    }

    /** Saving a second request replaces the first entirely. */
    @Test
    fun storeHoldsOnlyTheNewestUnresolvedRequest() {
        val store = store()
        assertTrue(store.save(request(requestId = "request-1")))
        assertTrue(store.save(request(requestId = "request-2", destination = null, selected = emptyList())))

        val restored = store.load()

        assertEquals("request-2", restored?.requestId)
        assertNull(restored?.destinationAccountRef)
        assertTrue(restored!!.selectedGroupIds.isEmpty())
    }

    /** Removal only deletes the entry whose id matches. */
    @Test
    fun removalIsIdMatchedSoAStaleDismisserCannotDeleteANewerRequest() {
        val store = store()
        assertTrue(store.save(request(requestId = "request-2")))

        store.remove("request-1")
        assertEquals("request-2", store.load()?.requestId)

        store.remove("request-2")
        assertNull(store.load())
    }

    /** Malformed decodes return null and invalid saves are refused. */
    @Test
    fun malformedAndBlankRequestsAreRejectedOrIgnored() {
        assertNull(decodePendingForwardRequest("not json".encodeToByteArray()))
        assertNull(decodePendingForwardRequest("""{"version":99}""".encodeToByteArray()))
        assertFalse(store().save(request().copy(requestId = "")))
        assertFalse(store().save(request().copy(payloads = emptyList())))
    }
}
