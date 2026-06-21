package dev.ipf.darkmatter.state

/**
 * How aggressively outgoing media (images, voice notes) is compressed before
 * send. The setting is a *ceiling*, not a target — a source already smaller
 * than the level's target ships as-is (no upscaling, no bitrate inflation).
 *
 * [Standard] is the default: a sensible quality/data trade-off for most users.
 *
 * Each level carries the concrete knobs the existing media pipeline already
 * supports:
 *  - [imageMaxEdgePx] / [imageJpegQuality] feed `MediaPipeline.readDownscaledJpeg`
 *    for the compressed levels.
 *  - [audioBitrateBps] feeds `VoiceRecorder` (mono AAC-LC; the engine has no
 *    Opus re-encode path yet, so the bitrate knob applies to the AAC encoder).
 *
 * Privacy floor (orthogonal to this knob): every level — *including* [Original]
 * — strips identifying photo metadata (EXIF GPS, device make/model, XMP/IPTC
 * sidecars where the container exposes them). "Original" keeps the encoded
 * image bytes when this client can strip metadata losslessly; unsupported image
 * containers fall back to the JPEG path rather than leaking metadata.
 *
 * Video has no re-encode path in this client, so video is always sent as-is
 * regardless of this setting — the quality levels apply to images and voice
 * notes only in v1.
 */
enum class MediaQuality(
    val preferenceValue: String,
    val imageMaxEdgePx: Int,
    val imageJpegQuality: Int,
    val audioBitrateBps: Int,
) {
    Low(
        preferenceValue = "low",
        imageMaxEdgePx = 1024,
        imageJpegQuality = 70,
        audioBitrateBps = 32_000,
    ),
    Standard(
        preferenceValue = "standard",
        imageMaxEdgePx = 2048,
        imageJpegQuality = 85,
        audioBitrateBps = 64_000,
    ),
    High(
        preferenceValue = "high",
        imageMaxEdgePx = 4096,
        imageJpegQuality = 92,
        audioBitrateBps = 96_000,
    ),

    /**
     * No downscale/re-encode for supported image containers: use source bytes
     * after metadata stripping. [imageMaxEdgePx] / [imageJpegQuality] are kept
     * as a privacy-preserving fallback for formats whose metadata cannot be
     * stripped losslessly here. Audio uses the highest supported bitrate.
     */
    Original(
        preferenceValue = "original",
        imageMaxEdgePx = Int.MAX_VALUE,
        imageJpegQuality = 100,
        audioBitrateBps = 96_000,
    ),
    ;

    val preservesOriginalImageBytes: Boolean
        get() = this == Original

    companion object {
        val DEFAULT: MediaQuality = Standard

        fun fromPreference(value: String?): MediaQuality = entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
