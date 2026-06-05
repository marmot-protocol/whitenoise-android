package dev.ipf.darkmatter.media

import dev.ipf.marmotkit.MediaReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi

/**
 * Pure parser for the MIP-04-v2 `imeta` tag carried on kind-9 messages that
 * reference an encrypted Blossom blob. Extracted from the Compose surface so
 * the validation rules (six required fields, hex lengths, version pin) are
 * unit-testable without an FFI runtime.
 *
 * Wire shape — per `darkmatter/crates/marmot-app/src/media.rs::imeta_tag`:
 * ```
 * ["imeta", "url <URL>", "m <mime>", "filename <name>",
 *           "x <hex 32B sha256 of plaintext>",
 *           "n <hex 12B nonce>",
 *           "v mip04-v2"]
 * ```
 * The Rust receive-side validator (`groups.rs::media_imeta_is_valid`) rejects
 * any imeta missing a field, with the wrong version, or with malformed hex —
 * but that runs upstream of us, so the parser here is defense-in-depth:
 * if the validator ever loosens, we still won't render junk.
 */
object MediaReferenceParser {

    private const val TAG_NAME = "imeta"
    private const val VERSION_VALUE = "mip04-v2"
    private const val SHA256_HEX_LEN = 64 // 32 bytes
    private const val NONCE_HEX_LEN = 24  // 12 bytes
    private const val HEX_CHARS = "0123456789abcdefABCDEF"

    /**
     * Build the `imeta` tag for [reference] in the canonical MIP-04-v2 field
     * order. Inverse of [parseImetaTag]; used to render a just-uploaded image
     * optimistically (bridging the gap until the published event echoes back)
     * without waiting for the projection round-trip.
     */
    fun toImetaTag(reference: MediaReferenceFfi): MessageTagFfi = MessageTagFfi(
        listOf(
            TAG_NAME,
            "url ${reference.url}",
            "m ${reference.mediaType}",
            "filename ${reference.fileName}",
            "x ${reference.fileHashHex}",
            "n ${reference.nonceHex}",
            // Always emit the canonical version this parser accepts — never
            // forward an unexpected reference.version that our own validator
            // (and the Rust receiver) would then reject.
            "v $VERSION_VALUE",
        ),
    )

    /**
     * Returns the first valid imeta-tag attachment reference in [tags], or
     * null when no imeta tag is present or no imeta tag passes validation.
     */
    fun parseImetaTag(tags: List<MessageTagFfi>): MediaReferenceFfi? {
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
     * [MediaReferenceFfi]. Returns null when any required field is missing
     * or fails validation. Lenient about ordering — Rust emits in a fixed
     * order today but the parser doesn't assume it.
     */
    private fun parseImetaValues(values: List<String>): MediaReferenceFfi? {
        val fields = mutableMapOf<String, String>()
        for (entry in values) {
            val spaceIdx = entry.indexOf(' ')
            if (spaceIdx <= 0 || spaceIdx == entry.lastIndex) continue
            val key = entry.substring(0, spaceIdx)
            val value = entry.substring(spaceIdx + 1)
            if (key.isBlank() || value.isBlank()) continue
            // First occurrence wins — matches Rust's HashMap-based parse.
            fields.putIfAbsent(key, value)
        }
        val url = fields["url"]?.takeIf { it.isNotBlank() } ?: return null
        val mediaType = fields["m"]?.takeIf { it.isNotBlank() } ?: return null
        val fileName = fields["filename"]?.takeIf { it.isNotBlank() } ?: return null
        val fileHash = fields["x"]?.takeIf { isHex(it, SHA256_HEX_LEN) } ?: return null
        val nonce = fields["n"]?.takeIf { isHex(it, NONCE_HEX_LEN) } ?: return null
        val version = fields["v"]?.takeIf { it == VERSION_VALUE } ?: return null
        return MediaReferenceFfi(
            url = url,
            fileHashHex = fileHash,
            nonceHex = nonce,
            fileName = fileName,
            mediaType = mediaType,
            version = version,
        )
    }

    /** True iff [s] has [requiredLength] characters, all hex. */
    private fun isHex(s: String, requiredLength: Int): Boolean {
        if (s.length != requiredLength) return false
        for (c in s) if (c !in HEX_CHARS) return false
        return true
    }

    /**
     * Whether this record is a media attachment that should render as an
     * image bubble. Tied to mime prefix so non-image attachments (Phase 3)
     * route through a different surface.
     */
    fun isImageMedia(reference: MediaReferenceFfi): Boolean =
        reference.mediaType.startsWith("image/", ignoreCase = true)
}
