package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MediaReferenceParserTest {
    @Test
    fun returnsNull_whenNoImetaTagPresent() {
        val tags = listOf(MessageTagFfi(listOf("e", "abc")), MessageTagFfi(listOf("p", "def")))
        assertNull(MediaReferenceParser.parseImetaTag(tags))
    }

    @Test
    fun returnsNull_whenTagListEmpty() {
        assertNull(MediaReferenceParser.parseImetaTag(emptyList()))
    }

    @Test
    fun parsesCanonicalEncryptedMediaImeta() {
        val ref = MediaReferenceParser.parseImetaTag(listOf(canonicalImetaTag()))
        assertNotNull(ref)
        assertEquals(URL, ref!!.locators.single().value)
        assertEquals("blossom-v1", ref.locators.single().kind)
        assertEquals(MIME_JPEG, ref.mediaType)
        assertEquals("photo.jpg", ref.fileName)
        assertEquals(CIPHERTEXT_SHA256_HEX, ref.ciphertextSha256)
        assertEquals(PLAINTEXT_SHA256_HEX, ref.plaintextSha256)
        assertEquals(NONCE_HEX, ref.nonceHex)
        assertEquals("encrypted-media-v1", ref.version)
        assertEquals("640x480", ref.dim)
        assertEquals(THUMBHASH, ref.thumbhash)
    }

    @Test
    fun isLenientAboutFieldOrder() {
        val reversed =
            MessageTagFfi(
                listOf(
                    "imeta",
                    "thumbhash $THUMBHASH",
                    "dim 640x480",
                    "filename photo.jpg",
                    "m $MIME_JPEG",
                    "nonce $NONCE_HEX",
                    "plaintext_sha256 $PLAINTEXT_SHA256_HEX",
                    "ciphertext_sha256 $CIPHERTEXT_SHA256_HEX",
                    "locator blossom-v1 $URL",
                    "v encrypted-media-v1",
                ),
            )
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(reversed)))
    }

    @Test
    fun returnsNull_whenAnyRequiredFieldMissing() {
        val required = listOf("locator", "m", "filename", "ciphertext_sha256", "plaintext_sha256", "nonce", "v")
        for (drop in required) {
            val entries = canonicalEntries().filterNot { it.startsWith("$drop ") }
            val tag = MessageTagFfi(listOf("imeta") + entries)
            assertNull("missing $drop should fail", MediaReferenceParser.parseImetaTag(listOf(tag)))
        }
    }

    @Test
    fun returnsNull_whenVersionIsNotEncryptedMediaV1() {
        val tag = imetaWithOverride("v" to "mip04-v2")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenCiphertextSha256IsWrongLength() {
        val tag = imetaWithOverride("ciphertext_sha256" to "abc123")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenPlaintextSha256HasNonHexChars() {
        val bad = "z" + PLAINTEXT_SHA256_HEX.drop(1)
        val tag = imetaWithOverride("plaintext_sha256" to bad)
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenNonceIsWrongLength() {
        val tag = imetaWithOverride("nonce" to "abcd")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun ignoresMalformedEntriesWithoutAValue() {
        val tag =
            MessageTagFfi(
                listOf(
                    "imeta",
                    "v encrypted-media-v1",
                    "locator blossom-v1 $URL",
                    "m $MIME_JPEG",
                    "filename",
                    "ciphertext_sha256 $CIPHERTEXT_SHA256_HEX",
                    "plaintext_sha256 $PLAINTEXT_SHA256_HEX",
                    "nonce $NONCE_HEX",
                ),
            )
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenLocatorIsMalformed() {
        val tag =
            MessageTagFfi(
                listOf("imeta", "locator blossom-v1") +
                    canonicalEntries().filterNot { it.startsWith("locator ") },
            )
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun acceptsUppercaseHex() {
        val tag =
            imetaWithOverride(
                "ciphertext_sha256" to CIPHERTEXT_SHA256_HEX.uppercase(),
                "plaintext_sha256" to PLAINTEXT_SHA256_HEX.uppercase(),
                "nonce" to NONCE_HEX.uppercase(),
            )
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun firstImetaWinsWhenMultiplePresent() {
        val first = canonicalImetaTag()
        val second = MessageTagFfi(listOf("imeta", "locator blossom-v1 junk"))
        val ref = MediaReferenceParser.parseImetaTag(listOf(first, second))
        assertNotNull(ref)
        assertEquals(URL, ref!!.locators.single().value)
    }

    @Test
    fun lastDuplicateFieldWins() {
        val tag =
            MessageTagFfi(
                canonicalImetaTag().values + "filename final.jpg",
            )
        val ref = MediaReferenceParser.parseImetaTag(listOf(tag))
        assertEquals("final.jpg", ref?.fileName)
    }

    @Test
    fun returnsNull_whenLocatorSchemeIsNotHttp() {
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "file:///etc/passwd"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "gopher://example.com/x"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "ftp://example.com/x.bin"))))
    }

    @Test
    fun returnsNull_whenLocatorHostIsPrivateOrLoopback() {
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "http://127.0.0.1:8080/admin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "https://192.168.1.1/x.bin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "https://[::1]/x.bin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "http://localhost/x.bin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "http://172.31.255.255/x.bin"))))
    }

    @Test
    fun acceptsPublicHttpsMediaLocators() {
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "https://blossom.example/x.bin"))))
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "https://172.32.0.1/x.bin"))))
    }

    @Test
    fun returnsNull_whenLocatorSchemeIsCleartextHttp() {
        // #157: cleartext http leaks the attachment URL + downloader IP to any
        // on-path observer in an E2EE client; even public-host http is rejected.
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "http://blossom.example/x.bin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("blossom-v1", "http://172.32.0.1/x.bin"))))
    }

    @Test
    fun returnsNull_whenLocatorKindIsNotBlossomV1() {
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithLocator("other-v1", URL))))
    }

    @Test
    fun returnsNull_whenBlurhashIsPresent() {
        val tag = MessageTagFfi(canonicalImetaTag().values + "blurhash legacy")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun toImetaTag_roundTripsThroughParse() {
        val original = referenceFixture()
        val tag = MediaReferenceParser.toImetaTag(original)
        val parsed = MediaReferenceParser.parseImetaTag(listOf(tag))
        assertEquals(original.copy(sourceEpoch = 0uL), parsed)
    }

    @Test
    fun toImetaTag_preservesFilenameWithSpaces() {
        val original = referenceFixture(fileName = "Screenshot 2026-06-05 at 12.34.56.jpg")
        val parsed = MediaReferenceParser.parseImetaTag(listOf(MediaReferenceParser.toImetaTag(original)))
        assertEquals("Screenshot 2026-06-05 at 12.34.56.jpg", parsed?.fileName)
    }

    @Test
    fun toImetaTag_emitsCanonicalFieldOrder() {
        val tag = MediaReferenceParser.toImetaTag(referenceFixture())
        val keys = tag.values.drop(1).map { it.substringBefore(' ') }
        assertEquals(
            listOf("v", "locator", "ciphertext_sha256", "plaintext_sha256", "nonce", "m", "filename", "dim", "thumbhash"),
            keys,
        )
    }

    @Test
    fun isImageMedia_truthyForImageMimePrefix() {
        assertTrue(MediaReferenceParser.isImageMedia(parsedFixture(mime = "image/jpeg")))
        assertTrue(MediaReferenceParser.isImageMedia(parsedFixture(mime = "image/png")))
        assertTrue(MediaReferenceParser.isImageMedia(parsedFixture(mime = "IMAGE/HEIC")))
    }

    @Test
    fun isImageMedia_falsyForNonImageMime() {
        assertFalse(MediaReferenceParser.isImageMedia(parsedFixture(mime = "application/pdf")))
        assertFalse(MediaReferenceParser.isImageMedia(parsedFixture(mime = "video/mp4")))
        assertFalse(MediaReferenceParser.isImageMedia(parsedFixture(mime = "text/plain")))
    }

    private fun canonicalImetaTag() = MessageTagFfi(listOf("imeta") + canonicalEntries())

    private fun canonicalEntries() =
        listOf(
            "v encrypted-media-v1",
            "locator blossom-v1 $URL",
            "ciphertext_sha256 $CIPHERTEXT_SHA256_HEX",
            "plaintext_sha256 $PLAINTEXT_SHA256_HEX",
            "nonce $NONCE_HEX",
            "m $MIME_JPEG",
            "filename photo.jpg",
            "dim 640x480",
            "thumbhash $THUMBHASH",
        )

    private fun imetaWithOverride(vararg overrides: Pair<String, String>): MessageTagFfi {
        val byKey = canonicalEntries().associateBy { it.substringBefore(' ') }.toMutableMap()
        for ((k, v) in overrides) byKey[k] = "$k $v"
        return MessageTagFfi(listOf("imeta") + byKey.values.toList())
    }

    private fun imetaWithLocator(
        kind: String,
        url: String,
    ): MessageTagFfi {
        val entries = canonicalEntries().filterNot { it.startsWith("locator ") }
        return MessageTagFfi(listOf("imeta", "locator $kind $url") + entries)
    }

    private fun parsedFixture(mime: String) =
        MediaReferenceParser.parseImetaTag(
            listOf(imetaWithOverride("m" to mime)),
        )!!

    private fun referenceFixture(fileName: String = "photo.jpg") =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = URL)),
            ciphertextSha256 = CIPHERTEXT_SHA256_HEX,
            plaintextSha256 = PLAINTEXT_SHA256_HEX,
            nonceHex = NONCE_HEX,
            fileName = fileName,
            mediaType = MIME_JPEG,
            version = "encrypted-media-v1",
            sourceEpoch = 99uL,
            dim = "640x480",
            thumbhash = THUMBHASH,
        )

    // ---- firstUnsafeLocatorHost (resolve-time SSRF gate) -------------------

    @Test
    fun firstUnsafeLocatorHost_allowsPublicNameResolvingToPublicAddress() {
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(listOf(blossom("https://media.example/blob"))) {
                listOf(addr(93, 184, 216, 34))
            }
        assertNull(unsafe)
    }

    @Test
    fun firstUnsafeLocatorHost_blocksPublicNameResolvingToLoopback() {
        // The core gap: a public-looking host whose A-record points at loopback
        // passes the literal check but must be blocked at resolve time.
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(listOf(blossom("https://attacker.example/blob"))) {
                listOf(addr(127, 0, 0, 1))
            }
        assertEquals("attacker.example", unsafe)
    }

    @Test
    fun firstUnsafeLocatorHost_blocksPublicNameResolvingToRfc1918() {
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(listOf(blossom("https://attacker.example/blob"))) {
                listOf(addr(10, 0, 0, 5))
            }
        assertEquals("attacker.example", unsafe)
    }

    @Test
    fun firstUnsafeLocatorHost_blocksLiteralPrivateHostWithoutResolving() {
        var resolverCalled = false
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(listOf(blossom("https://127.0.0.1/blob"))) {
                resolverCalled = true
                listOf(addr(8, 8, 8, 8))
            }
        assertEquals("127.0.0.1", unsafe)
        // Literal check short-circuits before any DNS work.
        assertFalse(resolverCalled)
    }

    @Test
    fun firstUnsafeLocatorHost_blocksWhenResolutionFails() {
        // Can't prove the target is public, so don't hand it to the native fetch.
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(listOf(blossom("https://attacker.example/blob"))) { null }
        assertEquals("attacker.example", unsafe)
    }

    @Test
    fun firstUnsafeLocatorHost_blocksWhenAnyLocatorIsUnsafe() {
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(
                listOf(blossom("https://good.example/a"), blossom("https://attacker.example/b")),
            ) { host -> if (host == "good.example") listOf(addr(93, 184, 216, 34)) else listOf(addr(192, 168, 1, 9)) }
        assertEquals("attacker.example", unsafe)
    }

    @Test
    fun firstUnsafeLocatorHost_failsClosedOnMalformedLocator() {
        // A locator whose host can't be parsed must be treated as unsafe, not
        // skipped — otherwise it would reach the native fetch unchecked.
        var resolverCalled = false
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(listOf(blossom("not a url"))) {
                resolverCalled = true
                listOf(addr(8, 8, 8, 8))
            }
        assertNotNull(unsafe)
        assertFalse(resolverCalled)
    }

    @Test
    fun firstUnsafeFetchableLocatorHost_ignoresUnsupportedKindAndAllowsSafeBlossom() {
        // The engine never fetches a non-blossom locator, so an unsafe entry of
        // another kind must not block an otherwise-downloadable attachment.
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(
                listOf(
                    locator(kind = "ipfs-v1", url = "https://127.0.0.1/blob"),
                    blossom("https://media.example/blob"),
                ),
            ) { listOf(addr(93, 184, 216, 34)) }
        assertNull(unsafe)
    }

    @Test
    fun firstUnsafeFetchableLocatorHost_ignoresUnsupportedKindWithNoFetchableLocator() {
        // No fetchable locator at all → nothing for the engine to fetch, so the
        // preflight has nothing to block (the engine no-ops on its own).
        var resolverCalled = false
        val unsafe =
            MediaReferenceParser.firstUnsafeFetchableLocatorHost(
                listOf(locator(kind = "ipfs-v1", url = "https://127.0.0.1/blob")),
            ) {
                resolverCalled = true
                listOf(addr(8, 8, 8, 8))
            }
        assertNull(unsafe)
        assertFalse(resolverCalled)
    }

    private fun blossom(url: String) = MediaLocatorFfi(kind = "blossom-v1", value = url)

    private fun locator(
        kind: String,
        url: String,
    ) = MediaLocatorFfi(kind = kind, value = url)

    private fun addr(vararg octets: Int): InetAddress = InetAddress.getByAddress(ByteArray(octets.size) { octets[it].toByte() })

    private companion object {
        const val URL = "https://blossom.primal.net/abcdef.bin"
        const val MIME_JPEG = "image/jpeg"
        const val CIPHERTEXT_SHA256_HEX = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val PLAINTEXT_SHA256_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val NONCE_HEX = "0123456789abcdef01234567"
        const val THUMBHASH = "1QcSHQRnh493V4dIh4eXh1h4kJUI"
    }
}
