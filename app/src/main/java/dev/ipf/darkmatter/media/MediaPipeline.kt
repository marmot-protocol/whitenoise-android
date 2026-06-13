package dev.ipf.darkmatter.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Local prep for outgoing image attachments. Two layers:
 *
 *  - [targetDimensions] is a pure function the JVM unit test exercises.
 *  - [readDownscaledJpeg] does the actual Android `Bitmap` decode/recompress
 *    and only runs on-device.
 *
 * The downscale defaults (1920 max edge, JPEG quality 85) match the
 * Whitenoise Flutter client. Keeping the same payload envelope across
 * clients prevents one platform from accidentally shipping multi-MB blobs
 * that the other side then has to download.
 *
 * The Rust `uploadMedia` FFI takes attachment plaintext as `ByteArray`s (no
 * streaming, no progress callback), so each compressed payload sits in the JVM
 * heap during upload. Capping the edge is the only protection against OOM on a
 * 12MP source.
 */
object MediaPipeline {
    /** 1920px max edge mirrors `ImagePicker(maxWidth: 1920, maxHeight: 1920)`. */
    const val DEFAULT_MAX_EDGE_PX: Int = 1920

    /** Quality 85 mirrors `ImagePicker(imageQuality: 85)`. */
    const val DEFAULT_JPEG_QUALITY: Int = 85

    /** MIME on the wire always matches the recompressed payload, not the source. */
    const val RECOMPRESSED_MIME: String = "image/jpeg"

    /**
     * Read at most [cap] bytes from [stream] into a freshly-allocated buffer
     * and return them. Returns null if the stream would exceed the cap —
     * caller decides whether to surface that as "file too large".
     *
     * Used by the document picker (`OpenMultipleDocuments` accepts any MIME
     * and any size — a 500 MB pick would otherwise allocate the full payload
     * via `InputStream.readBytes()` and crash the process before the
     * retained-uploads LRU has anything to evict).
     *
     * Doesn't close [stream] — the caller owns the lifecycle via `use { }`.
     */
    fun readBoundedBytes(
        stream: java.io.InputStream,
        cap: Int,
    ): ByteArray? {
        require(cap >= 0) { "cap must be non-negative" }
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            // Strictly greater so a file exactly the cap size still goes through.
            if (total > cap) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** Max longer-edge (px) for in-bubble thumbnail decodes (shared by the
     *  UI bubble and the send-path thumbnail seed). */
    const val THUMBNAIL_MAX_EDGE_PX: Int = 1280

    /**
     * Full-screen viewer decode ceiling. Bounded so a malicious or oversize
     * remote attachment can't allocate a runaway ARGB_8888 bitmap (a 5000px
     * image decodes to ~100 MB). 2560px keeps quality high on phone screens
     * while keeping peak heap use bounded (~25 MB).
     */
    const val VIEWER_MAX_EDGE_PX: Int = 2560

    /**
     * Replace whatever extension the source carried with `.jpg`, since the
     * payload is always recompressed to JPEG. Without this swap, the imeta
     * tag would advertise `m=image/png` while the bytes are actually JPEG —
     * a stricter receiver that honors `m` (HEIC decode, save-as-png) would
     * break. Leaves untrimmed names alone (no dot → just append).
     */
    fun swapExtensionToJpg(name: String): String {
        if (name.isBlank()) return "image.jpg"
        val dot = name.lastIndexOf('.')
        // No dot, or leading-dot like ".env" with no name — append rather
        // than overwrite, otherwise we'd produce ".jpg" which most pickers
        // treat as a hidden file.
        if (dot <= 0) return "$name.jpg"
        return name.substring(0, dot) + ".jpg"
    }

    /**
     * Reduce an attachment's `fileName` — which arrives from a **remote**
     * `imeta` tag and is otherwise untrusted — to a bare basename safe to use
     * as a path segment when writing to the gallery or a share temp file.
     * Strips any directory components so a malicious `"../../x"` can't traverse
     * out of the target directory. Returns `"image.jpg"` for empty/dot names.
     */
    fun safeDisplayName(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/').trim()
        return base.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "image.jpg"
    }

    /**
     * Decode [bytes] downscaled so the longer edge is ≈ [maxEdgePx], using a
     * power-of-two `inSampleSize`. Used for in-bubble thumbnails so a full
     * 1920px image isn't held as a ~14 MB ARGB_8888 bitmap per visible row.
     * Returns null when the bytes can't be decoded.
     */
    fun decodeSampledBitmap(
        bytes: ByteArray,
        maxEdgePx: Int,
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val (targetW, targetH) = targetDimensions(bounds.outWidth, bounds.outHeight, maxEdgePx)
        if (targetW == 0 || targetH == 0) return null
        val opts =
            BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Compute the bitmap dimensions for a downscale that fits inside a
     * square of `maxEdgePx`. Preserves aspect ratio. Returns the source
     * dimensions unchanged when the image is already within bounds — no
     * upscaling. Pure for unit testing.
     *
     * Returns (0, 0) for any non-positive input — the caller treats this
     * as "give up, don't try to upload" rather than failing later in
     * Bitmap.createScaledBitmap with an opaque exception.
     */
    fun targetDimensions(
        srcWidth: Int,
        srcHeight: Int,
        maxEdgePx: Int,
    ): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdgePx <= 0) return 0 to 0
        val longerEdge = maxOf(srcWidth, srcHeight)
        if (longerEdge <= maxEdgePx) return srcWidth to srcHeight
        val scale = maxEdgePx.toDouble() / longerEdge.toDouble()
        // Floor-then-coerce-to-1 so a 19200x10 source doesn't yield height 0.
        val targetW = (srcWidth * scale).toInt().coerceAtLeast(1)
        val targetH = (srcHeight * scale).toInt().coerceAtLeast(1)
        return targetW to targetH
    }

    /**
     * The bytes + decoded dimensions of a downscaled JPEG. Width/height are
     * the *encoded* dimensions, not the source — receivers use them to
     * reserve aspect-ratio space before the decode completes. `thumbhash`
     * is the base64-encoded perceptual hash a receiver can render as a
     * blurred placeholder while the full bytes decrypt + decode.
     */
    data class DownscaledJpeg(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val thumbhash: String?,
    )

    /**
     * Decode the image at [uri], downscale so the longer edge ≤ [maxEdgePx],
     * and re-encode as JPEG at [quality]. Returns null when the source can't
     * be decoded (corrupt file, unsupported format, unreadable Uri).
     */
    fun readDownscaledJpeg(
        contentResolver: ContentResolver,
        uri: Uri,
        maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): DownscaledJpeg? {
        // Two-pass decode: first read just the bounds so we can decide an
        // inSampleSize, then decode at that sampled size. Avoids a 50MB
        // bitmap in heap for a 12MP source. Any I/O or decode failure below
        // surfaces as null (see the catch) so callers never crash or ship
        // partial bytes.
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { stream ->
                stream ?: return null
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            val (targetW, targetH) = targetDimensions(srcW, srcH, maxEdgePx)
            if (targetW == 0 || targetH == 0) return null
            val sampleSize = computeInSampleSize(srcW, srcH, targetW, targetH)

            val decoded: Bitmap =
                contentResolver.openInputStream(uri).use { stream ->
                    stream ?: return null
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeStream(stream, null, opts) ?: return null
                }
            val scaled =
                if (decoded.width != targetW || decoded.height != targetH) {
                    try {
                        Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                            if (it !== decoded) decoded.recycle()
                        }
                    } catch (t: Throwable) {
                        decoded.recycle() // don't leak the source bitmap on scale failure
                        throw t
                    }
                } else {
                    decoded
                }

            try {
                val width = scaled.width
                val height = scaled.height
                // Compute thumbhash from the already-decoded bitmap before
                // recycle. Failures degrade silently — a missing hash just
                // means receivers don't get a placeholder; it's not a
                // reason to drop the upload.
                val thumbhash = runCatching { Thumbhash.encodeFromBitmap(scaled) }.getOrNull()
                ByteArrayOutputStream().use { out ->
                    // compress() returns false on failure — don't ship partial bytes.
                    if (scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                        DownscaledJpeg(out.toByteArray(), width, height, thumbhash)
                    } else {
                        null
                    }
                }
            } finally {
                scaled.recycle()
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Decode an existing image-byte payload's bounds (no allocation) and
     * return them as a `WxH` string suitable for the NIP-92 `imeta dim`
     * field, or null when the bytes don't decode. Used by the document-picker
     * path which doesn't recompress — it still wants to advertise dimensions
     * to the receiver so the bubble lays out before the bytes finish
     * decoding.
     */
    fun imageDimOrNull(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        return "${w}x$h"
    }

    /**
     * Largest power-of-two inSampleSize that keeps the decoded bitmap at
     * least as large as the target on both axes. The subsequent
     * `createScaledBitmap` walks the rest of the way. Matches the pattern
     * Android documents in `BitmapFactory.Options#inSampleSize`.
     */
    internal fun computeInSampleSize(
        srcW: Int,
        srcH: Int,
        reqW: Int,
        reqH: Int,
    ): Int {
        if (reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }
}
