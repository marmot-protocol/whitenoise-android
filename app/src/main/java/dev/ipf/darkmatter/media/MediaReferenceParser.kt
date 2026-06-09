package dev.ipf.darkmatter.media

import dev.ipf.darkmatter.core.HostSafety
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import java.net.URI

/**
 * Pure parser for the encrypted-media-v1 `imeta` tag carried on kind-9
 * messages. Rust validates incoming tags before projection; this parser is
 * used for optimistic UI bridge records that Android creates locally before
 * the projected event echoes back.
 *
 * Wire shape — per `darkmatter/crates/marmot-app/src/media.rs::imeta_tag`:
 * ```
 * ["imeta", "v encrypted-media-v1",
 *           "locator blossom-v1 <URL>",
 *           "ciphertext_sha256 <hex 32B>",
 *           "plaintext_sha256 <hex 32B>",
 *           "nonce <hex 12B>",
 *           "m <mime>",
 *           "filename <name>",
 *           "dim <width>x<height>",
 *           "thumbhash <base64>"]
 * ```
 */
object MediaReferenceParser {
    private const val TAG_NAME = "imeta"
    private const val VERSION_VALUE = "encrypted-media-v1"
    private const val BLOSSOM_LOCATOR_KIND = "blossom-v1"
    private const val SHA256_HEX_LEN = 64 // 32 bytes
    private const val NONCE_HEX_LEN = 24 // 12 bytes
    private const val HEX_CHARS = "0123456789abcdefABCDEF"

    /**
     * Build the `imeta` tag for [reference] in the canonical encrypted-media-v1 field
     * order. Inverse of [parseImetaTag]; used to render a just-uploaded image
     * optimistically (bridging the gap until the published event echoes back)
     * without waiting for the projection round-trip.
     */
    fun toImetaTag(reference: MediaAttachmentReferenceFfi): MessageTagFfi =
        MessageTagFfi(
            buildList {
                add(TAG_NAME)
                add("v $VERSION_VALUE")
                reference.locators.forEach { add("locator ${it.kind} ${it.value}") }
                add("ciphertext_sha256 ${reference.ciphertextSha256}")
                add("plaintext_sha256 ${reference.plaintextSha256}")
                add("nonce ${reference.nonceHex}")
                add("m ${reference.mediaType}")
                add("filename ${reference.fileName}")
                reference.dim?.takeIf { it.isNotBlank() }?.let { add("dim $it") }
                reference.thumbhash?.takeIf { it.isNotBlank() }?.let { add("thumbhash $it") }
            },
        )

    /**
     * Returns the first valid imeta-tag attachment reference in [tags], or
     * null when no imeta tag is present or no imeta tag passes validation.
     */
    fun parseImetaTag(tags: List<MessageTagFfi>): MediaAttachmentReferenceFfi? {
        for (tag in tags) {
            val values = tag.values
            if (values.firstOrNull() != TAG_NAME) continue
            val ref = parseImetaValues(values.drop(1)) ?: continue
            return ref
        }
        return null
    }

    /**
     * Parses the imeta tag's value list (after the `"imeta"` name) into a
     * [MediaAttachmentReferenceFfi]. Returns null when any required field is
     * missing or fails validation. Lenient about ordering — Rust emits in a
     * fixed order today but the parser doesn't assume it.
     */
    private fun parseImetaValues(values: List<String>): MediaAttachmentReferenceFfi? {
        val fields = mutableMapOf<String, String>()
        val locators = mutableListOf<MediaLocatorFfi>()
        for (entry in values) {
            if (entry.startsWith("blurhash ")) return null
            if (entry.startsWith("locator ")) {
                val rest = entry.removePrefix("locator ")
                val split = rest.indexOf(' ')
                if (split <= 0 || split == rest.lastIndex) return null
                val kind = rest.substring(0, split)
                val value = rest.substring(split + 1)
                if (kind.isBlank() || !isDownloadableLocator(kind, value)) return null
                locators += MediaLocatorFfi(kind = kind, value = value)
                continue
            }
            val spaceIdx = entry.indexOf(' ')
            if (spaceIdx <= 0 || spaceIdx == entry.lastIndex) continue
            val key = entry.substring(0, spaceIdx)
            val value = entry.substring(spaceIdx + 1)
            if (key.isBlank() || value.isBlank()) continue
            // Last occurrence wins, matching Rust's parse into a map.
            fields[key] = value
        }
        if (locators.isEmpty()) return null
        val mediaType = fields["m"]?.takeIf { it.isNotBlank() } ?: return null
        val fileName = fields["filename"]?.takeIf { it.isNotBlank() } ?: return null
        val ciphertextHash = fields["ciphertext_sha256"]?.takeIf { isHex(it, SHA256_HEX_LEN) } ?: return null
        val plaintextHash = fields["plaintext_sha256"]?.takeIf { isHex(it, SHA256_HEX_LEN) } ?: return null
        val nonce = fields["nonce"]?.takeIf { isHex(it, NONCE_HEX_LEN) } ?: return null
        val version = fields["v"]?.takeIf { it == VERSION_VALUE } ?: return null
        return MediaAttachmentReferenceFfi(
            locators = locators,
            ciphertextSha256 = ciphertextHash,
            plaintextSha256 = plaintextHash,
            nonceHex = nonce,
            fileName = fileName,
            mediaType = mediaType,
            version = version,
            sourceEpoch = 0uL,
            dim = fields["dim"],
            thumbhash = fields["thumbhash"],
        )
    }

    /**
     * Whether [raw] is a media URL we're willing to download: a non-blank
     * http(s) URL whose host is not loopback / the local network. Defense in
     * depth against SSRF via a malicious imeta tag — a hostile group member
     * could otherwise point auto-download at `http://127.0.0.1:8080/...` or an
     * RFC-1918 service. See issue #98.
     */
    private fun isDownloadableLocator(
        kind: String,
        raw: String,
    ): Boolean {
        if (kind != BLOSSOM_LOCATOR_KIND) return false
        if (raw.isBlank()) return false
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        if (host.isBlank()) return false
        return !HostSafety.isPrivateOrLoopbackHost(host)
    }

    /** True iff [s] has [requiredLength] characters, all hex. */
    private fun isHex(
        s: String,
        requiredLength: Int,
    ): Boolean {
        if (s.length != requiredLength) return false
        for (c in s) if (c !in HEX_CHARS) return false
        return true
    }

    /**
     * Whether this record is a media attachment that should render as an
     * image bubble. Tied to mime prefix so non-image attachments (Phase 3)
     * route through a different surface.
     */
    fun isImageMedia(reference: MediaAttachmentReferenceFfi): Boolean = reference.mediaType.startsWith("image/", ignoreCase = true)
}
