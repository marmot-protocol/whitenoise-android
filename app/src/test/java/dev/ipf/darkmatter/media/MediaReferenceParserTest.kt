package dev.ipf.darkmatter.media

import dev.ipf.marmotkit.MediaReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun parsesCanonicalSixFieldImeta() {
        val ref = MediaReferenceParser.parseImetaTag(listOf(canonicalImetaTag()))
        assertNotNull(ref)
        assertEquals(URL, ref!!.url)
        assertEquals(MIME_JPEG, ref.mediaType)
        assertEquals("photo.jpg", ref.fileName)
        assertEquals(SHA256_HEX, ref.fileHashHex)
        assertEquals(NONCE_HEX, ref.nonceHex)
        assertEquals("mip04-v2", ref.version)
    }

    @Test
    fun isLenientAboutFieldOrder() {
        // Reverse order — still parses.
        val reversed =
            MessageTagFfi(
                listOf(
                    "imeta",
                    "v mip04-v2",
                    "n $NONCE_HEX",
                    "x $SHA256_HEX",
                    "filename photo.jpg",
                    "m $MIME_JPEG",
                    "url $URL",
                ),
            )
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(reversed)))
    }

    @Test
    fun returnsNull_whenAnyRequiredFieldMissing() {
        val required = listOf("url", "m", "filename", "x", "n", "v")
        for (drop in required) {
            val entries = canonicalEntries().filterNot { it.startsWith("$drop ") }
            val tag = MessageTagFfi(listOf("imeta") + entries)
            assertNull("missing $drop should fail", MediaReferenceParser.parseImetaTag(listOf(tag)))
        }
    }

    @Test
    fun returnsNull_whenVersionIsNotMip04V2() {
        val tag = imetaWithOverride("v" to "mip04-v1")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenSha256IsWrongLength() {
        val tag = imetaWithOverride("x" to "abc123")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenSha256HasNonHexChars() {
        // 64 chars, but contains 'z'.
        val bad = "z" + SHA256_HEX.drop(1)
        val tag = imetaWithOverride("x" to bad)
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun returnsNull_whenNonceIsWrongLength() {
        val tag = imetaWithOverride("n" to "abcd")
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun ignoresMalformedEntriesWithoutAValue() {
        // "filename" with no space-value. Should NOT crash, just treat as missing.
        val tag =
            MessageTagFfi(
                listOf(
                    "imeta",
                    "url $URL",
                    "m $MIME_JPEG",
                    "filename",
                    "x $SHA256_HEX",
                    "n $NONCE_HEX",
                    "v mip04-v2",
                ),
            )
        assertNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun acceptsUppercaseHex() {
        // The Rust validator accepts both cases; our parser must too.
        val tag = imetaWithOverride("x" to SHA256_HEX.uppercase(), "n" to NONCE_HEX.uppercase())
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    @Test
    fun firstImetaWinsWhenMultiplePresent() {
        // Defensive — a malformed second imeta shouldn't poison the first.
        val first = canonicalImetaTag()
        val second = MessageTagFfi(listOf("imeta", "url junk"))
        val ref = MediaReferenceParser.parseImetaTag(listOf(first, second))
        assertNotNull(ref)
        assertEquals(URL, ref!!.url)
    }

    @Test
    fun returnsNull_whenUrlSchemeIsNotHttp() {
        // SSRF / local-file guard: only http(s) media URLs are downloadable.
        // See issue #98.
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "file:///etc/passwd"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "gopher://example.com/x"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "ftp://example.com/x.bin"))))
    }

    @Test
    fun returnsNull_whenUrlHostIsPrivateOrLoopback() {
        // A malicious group member must not be able to point auto-download at
        // the device's loopback or the local network. See issue #98.
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "http://127.0.0.1:8080/admin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "https://192.168.1.1/x.bin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "https://[::1]/x.bin"))))
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "http://localhost/x.bin"))))
        // 172.16/12 boundary: .16-.31 are private, .32 is public (off-by-one guard).
        assertNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "http://172.31.255.255/x.bin"))))
    }

    @Test
    fun acceptsPublicHttpAndHttpsMediaUrls() {
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "http://blossom.example/x.bin"))))
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "https://blossom.example/x.bin"))))
        // Just outside the 172.16/12 private block — must be allowed.
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(imetaWithOverride("url" to "http://172.32.0.1/x.bin"))))
    }

    // ---- toImetaTag (round-trip) -------------------------------------------

    @Test
    fun toImetaTag_roundTripsThroughParse() {
        // The whole point of the builder is symmetry with the parser, so a
        // ref written and re-parsed must equal the original.
        val original =
            MediaReferenceFfi(
                url = URL,
                fileHashHex = SHA256_HEX,
                nonceHex = NONCE_HEX,
                fileName = "photo.jpg",
                mediaType = MIME_JPEG,
                version = "mip04-v2",
            )
        val tag = MediaReferenceParser.toImetaTag(original)
        val parsed = MediaReferenceParser.parseImetaTag(listOf(tag))
        assertEquals(original, parsed)
    }

    @Test
    fun toImetaTag_preservesFilenameWithSpaces() {
        // Picker emits names like "Screenshot 2026-06-05 at 12.34.56.jpg".
        // The parser splits "key value" on the FIRST space, so the value
        // (everything after) survives — but only if the writer doesn't add
        // any escaping that the parser doesn't unescape.
        val original =
            MediaReferenceFfi(
                url = URL,
                fileHashHex = SHA256_HEX,
                nonceHex = NONCE_HEX,
                fileName = "Screenshot 2026-06-05 at 12.34.56.jpg",
                mediaType = MIME_JPEG,
                version = "mip04-v2",
            )
        val parsed = MediaReferenceParser.parseImetaTag(listOf(MediaReferenceParser.toImetaTag(original)))
        assertEquals("Screenshot 2026-06-05 at 12.34.56.jpg", parsed?.fileName)
    }

    @Test
    fun toImetaTag_emitsCanonicalFieldOrder() {
        // Defensive: if the Rust receive-side validator ever became
        // order-sensitive, our optimistic-injected tag must match what Rust
        // emits. Document the canonical order via assertion.
        val tag =
            MediaReferenceParser.toImetaTag(
                MediaReferenceFfi(
                    url = URL,
                    fileHashHex = SHA256_HEX,
                    nonceHex = NONCE_HEX,
                    fileName = "f.jpg",
                    mediaType = MIME_JPEG,
                    version = "mip04-v2",
                ),
            )
        // The tag values include the "imeta" name plus the six fields in order.
        val keys = tag.values.drop(1).map { it.substringBefore(' ') }
        assertEquals(listOf("url", "m", "filename", "x", "n", "v"), keys)
    }

    @Test
    fun isImageMedia_truthyForImageMimePrefix() {
        assertTrue(MediaReferenceParser.isImageMedia(parsedFixture(mime = "image/jpeg")))
        assertTrue(MediaReferenceParser.isImageMedia(parsedFixture(mime = "image/png")))
        assertTrue(MediaReferenceParser.isImageMedia(parsedFixture(mime = "IMAGE/HEIC"))) // case-insensitive
    }

    @Test
    fun isImageMedia_falsyForNonImageMime() {
        assertFalse(MediaReferenceParser.isImageMedia(parsedFixture(mime = "application/pdf")))
        assertFalse(MediaReferenceParser.isImageMedia(parsedFixture(mime = "video/mp4")))
        assertFalse(MediaReferenceParser.isImageMedia(parsedFixture(mime = "text/plain")))
    }

    // ---- helpers ------------------------------------------------------------

    private fun canonicalImetaTag() = MessageTagFfi(listOf("imeta") + canonicalEntries())

    private fun canonicalEntries() =
        listOf(
            "url $URL",
            "m $MIME_JPEG",
            "filename photo.jpg",
            "x $SHA256_HEX",
            "n $NONCE_HEX",
            "v mip04-v2",
        )

    private fun imetaWithOverride(vararg overrides: Pair<String, String>): MessageTagFfi {
        val byKey = canonicalEntries().associateBy { it.substringBefore(' ') }.toMutableMap()
        for ((k, v) in overrides) byKey[k] = "$k $v"
        return MessageTagFfi(listOf("imeta") + byKey.values.toList())
    }

    private fun parsedFixture(mime: String) =
        MediaReferenceParser.parseImetaTag(
            listOf(imetaWithOverride("m" to mime)),
        )!!

    @Test
    fun toImetaTag_roundTripsThroughParser() {
        val ref =
            MediaReferenceFfi(
                url = URL,
                fileHashHex = SHA256_HEX,
                nonceHex = NONCE_HEX,
                fileName = "photo.jpg",
                mediaType = MIME_JPEG,
                version = "mip04-v2",
            )
        val parsed = MediaReferenceParser.parseImetaTag(listOf(MediaReferenceParser.toImetaTag(ref)))
        assertEquals(ref, parsed)
    }

    @Test
    fun toImetaTag_pinsVersionEvenWhenReferenceVersionIsWrong() {
        // A stale/foreign version must not produce a tag our own parser rejects.
        val ref =
            MediaReferenceFfi(
                url = URL,
                fileHashHex = SHA256_HEX,
                nonceHex = NONCE_HEX,
                fileName = "photo.jpg",
                mediaType = MIME_JPEG,
                version = "mip04-v1",
            )
        val tag = MediaReferenceParser.toImetaTag(ref)
        assertTrue(tag.values.contains("v mip04-v2"))
        assertNotNull(MediaReferenceParser.parseImetaTag(listOf(tag)))
    }

    private companion object {
        const val URL = "https://blossom.primal.net/abcdef.bin"
        const val MIME_JPEG = "image/jpeg"
        const val SHA256_HEX = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789" // 64
        const val NONCE_HEX = "0123456789abcdef01234567" // 24
    }
}
