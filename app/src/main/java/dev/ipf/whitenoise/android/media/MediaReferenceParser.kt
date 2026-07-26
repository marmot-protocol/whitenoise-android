package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.HostSafety
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Pure parser for encrypted-media `imeta` tags carried on kind-9
 * messages. Rust validates incoming tags before projection; this parser is
 * used for optimistic UI bridge records that Android creates locally before
 * the projected event echoes back.
 *
 * Wire shape from the native chat media `imeta` tag:
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
    private const val VERSION_V1_VALUE = "encrypted-media-v1"
    private const val VERSION_V2_VALUE = "encrypted-media-v2"
    private const val BLOSSOM_LOCATOR_KIND = "blossom-v1"
    private const val SHA256_HEX_LEN = 64 // 32 bytes
    private const val NONCE_HEX_LEN = 24 // 12 bytes
    private const val HEX_CHARS = "0123456789abcdefABCDEF"

    private data class ParsedFetchableLocator(
        val host: String,
        val nativeValue: String,
    )

    /**
     * Build the `imeta` tag for [reference] in canonical encrypted-media field
     * order. Inverse of [parseImetaTag]; used to render a just-uploaded image
     * optimistically (bridging the gap until the published event echoes back)
     * without waiting for the projection round-trip.
     */
    fun toImetaTag(reference: MediaAttachmentReferenceFfi): MessageTagFfi =
        MessageTagFfi(
            buildList {
                add(TAG_NAME)
                add("v ${reference.version.wireValue()}")
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
    fun parseImetaTag(tags: List<MessageTagFfi>): MediaAttachmentReferenceFfi? = parseAllImetaTags(tags).firstOrNull()

    /**
     * Parses every valid imeta tag in [tags] in order. Album messages from
     * encrypted-media messages carry N imeta tags (one per attachment); the order
     * is the album's display order. Invalid tags are skipped silently.
     */
    fun parseAllImetaTags(tags: List<MessageTagFfi>): List<MediaAttachmentReferenceFfi> =
        tags.mapNotNull { tag ->
            val values = tag.values
            if (values.firstOrNull() != TAG_NAME) return@mapNotNull null
            parseImetaValues(values.drop(1))
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
            // `blurhash` (and any other key this version doesn't model, e.g.
            // NIP-92 extras from interoperating clients) falls through to the
            // generic key/value path below and is ignored: the required-field
            // validation — locator, hashes, nonce, and the pinned
            // `v encrypted-media-v1` — already rejects tags from other
            // protocol versions, so an extra hint key must not drop an
            // otherwise-valid attachment (#981).
            if (entry.startsWith("locator ")) {
                val rest = entry.removePrefix("locator ")
                val split = rest.indexOf(' ')
                if (split <= 0 || split == rest.lastIndex) return null
                val kind = rest.substring(0, split)
                val value = rest.substring(split + 1)
                if (kind.isBlank()) return null
                // The native fetch path only supports blossom-v1 locators. Keep
                // strict validation for fetchable locators, but ignore future or
                // interoperating locator kinds instead of dropping the whole tag.
                if (kind != BLOSSOM_LOCATOR_KIND) continue
                if (!isDownloadableBlossomLocator(value)) return null
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
        val version =
            when (fields["v"]) {
                VERSION_V1_VALUE -> EncryptedMediaVersionFfi.V1
                VERSION_V2_VALUE -> EncryptedMediaVersionFfi.V2
                else -> return null
            }
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

    private fun EncryptedMediaVersionFfi.wireValue(): String =
        when (this) {
            EncryptedMediaVersionFfi.V1 -> VERSION_V1_VALUE
            EncryptedMediaVersionFfi.V2 -> VERSION_V2_VALUE
        }

    /**
     * Whether [raw] is a media URL we're willing to download: a non-blank
     * default-port `https` URL whose host is not loopback / the local network. Defense in
     * depth against SSRF via a malicious imeta tag — a hostile group member
     * could otherwise point auto-download at `http://127.0.0.1:8080/...` or an
     * RFC-1918 service. See issue #98.
     *
     * `http` is rejected outright (#157): this is an E2EE client, and a
     * cleartext fetch would leak the attachment URL and the downloader's IP to
     * any on-path observer, defeating the point of the encrypted transport.
     */
    private fun isDownloadableBlossomLocator(raw: String): Boolean {
        // Delegate to the hardened parser so the parse-time gate enforces the
        // same authority rules (https, no userInfo, default/443 port, public
        // host) the download path uses. The caller has already filtered to the
        // blossom kind, so this takes the raw value only.
        val parsed = parseFetchableLocator(raw) ?: return false
        return !HostSafety.isPrivateOrLoopbackHost(parsed.host)
    }

    /**
     * Returns a copy of [reference] whose fetchable locators are rewritten from
     * the authority parsed and validated here before the value crosses into the
     * native downloader. That keeps the native fetch from consuming a raw
     * locator string whose authority could be interpreted differently from this
     * Kotlin SSRF gate.
     */
    internal fun safeDownloadReference(
        reference: MediaAttachmentReferenceFfi,
        resolve: (String) -> List<InetAddress>?,
    ): MediaAttachmentReferenceFfi? {
        val safeLocators =
            reference.locators.map { locator ->
                if (locator.kind != BLOSSOM_LOCATOR_KIND) return@map locator
                val parsed = parseFetchableLocator(locator.value) ?: return null
                if (unsafeFetchableLocatorHost(parsed, resolve) != null) return null
                locator.copy(value = parsed.nativeValue)
            }
        return reference.copy(locators = safeLocators)
    }

    private fun unsafeFetchableLocatorHost(
        parsed: ParsedFetchableLocator,
        resolve: (String) -> List<InetAddress>?,
    ): String? {
        if (HostSafety.isPrivateOrLoopbackHost(parsed.host)) return parsed.host
        val resolved = resolve(parsed.host)
        if (resolved.isNullOrEmpty() || resolved.any { HostSafety.isPrivateOrLoopbackAddress(it) }) return parsed.host
        return null
    }

    private fun parseFetchableLocator(raw: String): ParsedFetchableLocator? {
        if (raw.isBlank()) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return null
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host =
            uri.host
                ?.trim()
                ?.removeSurrounding("[", "]")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return ParsedFetchableLocator(host = host, nativeValue = nativeLocatorValue(uri, host))
    }

    private fun nativeLocatorValue(
        uri: URI,
        host: String,
    ): String =
        buildString {
            append("https://")
            append(if (host.contains(':')) "[$host]" else host)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
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

    /**
     * Whether this record is a voice / audio attachment that should render
     * as an inline playable bubble. Today this matches everything under the
     * `audio/` MIME tree; the recorder ships `audio/mp4` AAC-LC m4a but the
     * predicate is intentionally broader so other senders' clips also play.
     */
    fun isAudioMedia(reference: MediaAttachmentReferenceFfi): Boolean = reference.mediaType.startsWith("audio/", ignoreCase = true)

    fun isVideoMedia(reference: MediaAttachmentReferenceFfi): Boolean = reference.mediaType.startsWith("video/", ignoreCase = true)
}
