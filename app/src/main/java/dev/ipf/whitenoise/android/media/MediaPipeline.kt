package dev.ipf.whitenoise.android.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

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

    /**
     * Copy [stream] into [target] without ever writing bytes beyond [cap].
     * Returns false when the stream would exceed the cap; callers may delete
     * or ignore the partial file. Doesn't close [stream].
     */
    internal fun copyStreamToFileWithinCap(
        stream: java.io.InputStream,
        target: java.io.File,
        cap: Long,
    ): Boolean {
        require(cap >= 0L) { "cap must be non-negative" }
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        target.outputStream().use { out ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                val readLong = read.toLong()
                if (readLong > cap - total) return false
                out.write(chunk, 0, read)
                total += readLong
            }
        }
        return true
    }

    /** Read [source] into one exact-size ByteArray; avoids ByteArrayOutputStream's final copy. */
    internal fun readFileBytesExact(source: java.io.File): ByteArray {
        val length = source.length()
        if (length > Int.MAX_VALUE) throw java.io.IOException("file too large")
        val bytes = ByteArray(length.toInt())
        source.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw java.io.EOFException("file ended before expected length")
                offset += read
            }
            if (input.read() >= 0) throw java.io.IOException("file grew while reading")
        }
        return bytes
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
     * Returns null when the bytes can't be decoded. By default this treats the
     * bytes as display-ready pixels and ignores EXIF orientation, because most
     * callers use it for received attachment payloads whose senders may already
     * have baked rotation into pixels while leaving a stale Orientation tag.
     * Set [honorExifOrientation] only for trusted local source bytes whose EXIF
     * metadata should drive the preview.
     */
    fun decodeSampledBitmap(
        bytes: ByteArray,
        maxEdgePx: Int,
        honorExifOrientation: Boolean = false,
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        val orientation =
            if (honorExifOrientation) {
                readExifOrientation(bytes)
            } else {
                EXIF_ORIENTATION_NORMAL
            }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = orientedDecodeTarget(bounds.outWidth, bounds.outHeight, maxEdgePx, orientation) ?: return null
        val opts =
            BitmapFactory.Options().apply {
                inSampleSize = target.sampleSize
            }
        // The power-of-two sample only lands the decode in [target, 2×target),
        // so a source just under 2× the cap decodes at up to ~4× the intended
        // area — on the viewer path that's a ~100 MB bitmap (OOM / decompression
        // bomb). Scale to the exact target to enforce the documented bound, and
        // guard OOM like readDownscaledJpeg rather than crashing. See #368.
        val decoded =
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: OutOfMemoryError) {
                null
            } ?: return null
        return scaleAndOrientBitmap(decoded, target.rawWidth, target.rawHeight, orientation)
    }

    /**
     * Decode the image at [uri] downscaled so the longer edge is ≈
     * [maxEdgePx], preserving the source format and alpha channel. Two-pass
     * (bounds, then sampled decode) so a 12MP source never inflates to a
     * ~50 MB ARGB_8888 bitmap, then an exact scale to enforce the cap.
     *
     * Unlike [readDownscaledJpeg] this does **no** JPEG re-encode: it returns
     * a `Bitmap` straight from the decoder. Used by the composer staging
     * preview, which only needs to *show* the picked image — recompressing to
     * JPEG first (then re-decoding those bytes at full resolution) both
     * flattened transparent PNGs onto white and doubled the decode work,
     * whose silent OOM/encode failures left the tile stuck on a spinner that
     * never resolved (see #387). Mirrors [decodeSampledBitmap]'s sampling and
     * OOM guards, sourcing from a `ContentResolver` stream instead of bytes.
     *
     * JPEG EXIF orientation is applied to the returned pixels so the staging
     * sheet shows portrait camera captures upright instead of following the raw
     * sensor buffer. Returns null on any I/O, security, decode, or OOM failure
     * so the caller can fall back to a placeholder rather than crash.
     */
    fun decodeSampledFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        maxEdgePx: Int = THUMBNAIL_MAX_EDGE_PX,
    ): Bitmap? =
        try {
            val orientation = readExifOrientation(contentResolver, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { stream ->
                stream ?: return null
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) {
                null
            } else {
                val target = orientedDecodeTarget(srcW, srcH, maxEdgePx, orientation)
                if (target == null) {
                    null
                } else {
                    val opts =
                        BitmapFactory.Options().apply {
                            inSampleSize = target.sampleSize
                        }
                    val decoded =
                        contentResolver.openInputStream(uri).use { stream ->
                            if (stream == null) {
                                null
                            } else {
                                try {
                                    BitmapFactory.decodeStream(stream, null, opts)
                                } catch (_: OutOfMemoryError) {
                                    null
                                }
                            }
                        }
                    decoded?.let { scaleAndOrientBitmap(it, target.rawWidth, target.rawHeight, orientation) }
                }
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }

    private data class OrientedDecodeTarget(
        val rawWidth: Int,
        val rawHeight: Int,
        val sampleSize: Int,
    )

    internal const val EXIF_ORIENTATION_NORMAL = 1
    internal const val EXIF_ORIENTATION_FLIP_HORIZONTAL = 2
    internal const val EXIF_ORIENTATION_ROTATE_180 = 3
    internal const val EXIF_ORIENTATION_FLIP_VERTICAL = 4
    internal const val EXIF_ORIENTATION_TRANSPOSE = 5
    internal const val EXIF_ORIENTATION_ROTATE_90 = 6
    internal const val EXIF_ORIENTATION_TRANSVERSE = 7
    internal const val EXIF_ORIENTATION_ROTATE_270 = 8

    internal fun normalizeExifOrientation(orientation: Int?): Int =
        when (orientation) {
            EXIF_ORIENTATION_FLIP_HORIZONTAL,
            EXIF_ORIENTATION_ROTATE_180,
            EXIF_ORIENTATION_FLIP_VERTICAL,
            EXIF_ORIENTATION_TRANSPOSE,
            EXIF_ORIENTATION_ROTATE_90,
            EXIF_ORIENTATION_TRANSVERSE,
            EXIF_ORIENTATION_ROTATE_270,
            -> orientation
            else -> EXIF_ORIENTATION_NORMAL
        }

    internal fun exifOrientationRequiresPixelTransform(orientation: Int?): Boolean = normalizeExifOrientation(orientation) != EXIF_ORIENTATION_NORMAL

    internal fun exifOrientationSwapsDimensions(orientation: Int?): Boolean =
        when (normalizeExifOrientation(orientation)) {
            EXIF_ORIENTATION_TRANSPOSE,
            EXIF_ORIENTATION_ROTATE_90,
            EXIF_ORIENTATION_TRANSVERSE,
            EXIF_ORIENTATION_ROTATE_270,
            -> true
            else -> false
        }

    internal fun orientedSourceDimensions(
        srcWidth: Int,
        srcHeight: Int,
        orientation: Int?,
    ): Pair<Int, Int> =
        if (exifOrientationSwapsDimensions(orientation)) {
            srcHeight to srcWidth
        } else {
            srcWidth to srcHeight
        }

    internal fun targetDimensionsForExifOrientation(
        srcWidth: Int,
        srcHeight: Int,
        maxEdgePx: Int,
        orientation: Int?,
    ): Pair<Int, Int> {
        val (displaySrcW, displaySrcH) = orientedSourceDimensions(srcWidth, srcHeight, orientation)
        return targetDimensions(displaySrcW, displaySrcH, maxEdgePx)
    }

    private fun orientedDecodeTarget(
        srcWidth: Int,
        srcHeight: Int,
        maxEdgePx: Int,
        orientation: Int,
    ): OrientedDecodeTarget? {
        val (displayTargetW, displayTargetH) = targetDimensionsForExifOrientation(srcWidth, srcHeight, maxEdgePx, orientation)
        if (displayTargetW == 0 || displayTargetH == 0) return null
        val (rawTargetW, rawTargetH) =
            if (exifOrientationSwapsDimensions(orientation)) {
                displayTargetH to displayTargetW
            } else {
                displayTargetW to displayTargetH
            }
        return OrientedDecodeTarget(
            rawWidth = rawTargetW,
            rawHeight = rawTargetH,
            sampleSize = computeInSampleSize(srcWidth, srcHeight, rawTargetW, rawTargetH),
        )
    }

    private fun scaleAndOrientBitmap(
        decoded: Bitmap,
        rawTargetW: Int,
        rawTargetH: Int,
        orientation: Int,
    ): Bitmap? {
        var current = decoded
        try {
            if (decoded.width != rawTargetW || decoded.height != rawTargetH) {
                current = Bitmap.createScaledBitmap(decoded, rawTargetW, rawTargetH, true)
                if (current !== decoded) decoded.recycle()
            }
            val oriented = applyExifOrientation(current, orientation)
            if (oriented !== current) current.recycle()
            return oriented
        } catch (_: OutOfMemoryError) {
            if (!decoded.isRecycled) decoded.recycle()
            if (current !== decoded && !current.isRecycled) current.recycle()
            return null
        } catch (_: RuntimeException) {
            if (!decoded.isRecycled) decoded.recycle()
            if (current !== decoded && !current.isRecycled) current.recycle()
            return null
        }
    }

    private fun applyExifOrientation(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (normalizeExifOrientation(orientation)) {
            EXIF_ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            EXIF_ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            EXIF_ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            EXIF_ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            EXIF_ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            EXIF_ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            EXIF_ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun readExifOrientation(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Int =
        try {
            contentResolver.openInputStream(uri).use { stream ->
                stream ?: return EXIF_ORIENTATION_NORMAL
                normalizeExifOrientation(
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        EXIF_ORIENTATION_NORMAL,
                    ),
                )
            }
        } catch (_: java.io.IOException) {
            readExifOrientationFromUriPrefix(contentResolver, uri)
        } catch (_: SecurityException) {
            EXIF_ORIENTATION_NORMAL
        } catch (_: RuntimeException) {
            readExifOrientationFromUriPrefix(contentResolver, uri)
        }

    private fun readExifOrientationFromUriPrefix(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Int =
        try {
            contentResolver.openInputStream(uri).use { stream ->
                stream ?: return EXIF_ORIENTATION_NORMAL
                normalizeExifOrientation(
                    jpegExifOrientation(readPrefixBytes(stream, EXIF_FALLBACK_PREFIX_BYTES)),
                )
            }
        } catch (_: java.io.IOException) {
            EXIF_ORIENTATION_NORMAL
        } catch (_: SecurityException) {
            EXIF_ORIENTATION_NORMAL
        } catch (_: RuntimeException) {
            EXIF_ORIENTATION_NORMAL
        }

    private fun readPrefixBytes(
        stream: java.io.InputStream,
        cap: Int,
    ): ByteArray {
        require(cap >= 0) { "cap must be non-negative" }
        if (cap == 0) return ByteArray(0)
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(minOf(8 * 1024, cap))
        var remaining = cap
        while (remaining > 0) {
            val read = stream.read(chunk, 0, minOf(chunk.size, remaining))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return buffer.toByteArray()
    }

    private fun readExifOrientation(bytes: ByteArray): Int =
        try {
            bytes.inputStream().use { stream ->
                normalizeExifOrientation(
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        EXIF_ORIENTATION_NORMAL,
                    ),
                )
            }
        } catch (_: java.io.IOException) {
            normalizeExifOrientation(jpegExifOrientation(bytes))
        } catch (_: RuntimeException) {
            normalizeExifOrientation(jpegExifOrientation(bytes))
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

    /** Metadata-stripped source image bytes for "Original" sends. */
    data class OriginalImage(
        val bytes: ByteArray,
        val mediaType: String,
        val fileName: String,
        val dim: String,
        val thumbhash: String?,
    )

    sealed class OriginalImageReadResult {
        data class Success(
            val image: OriginalImage,
        ) : OriginalImageReadResult()

        data object TooLarge : OriginalImageReadResult()

        /** The container has no lossless metadata-stripper in this client. */
        data object Unsupported : OriginalImageReadResult()

        data object Failed : OriginalImageReadResult()
    }

    /**
     * Read an image for the "Original" quality level: preserve encoded pixel
     * bytes and strip identifying metadata without decoding/re-encoding. This
     * supports the containers whose metadata envelopes are safe to edit here
     * (JPEG APP1/APP13/comment, PNG textual/eXIf chunks, WebP EXIF/XMP chunks).
     * Unsupported containers return [OriginalImageReadResult.Unsupported] so the
     * caller can fall back to the JPEG privacy path instead of leaking metadata.
     */
    fun readOriginalImageForUpload(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Int,
    ): OriginalImageReadResult {
        if (maxBytes <= 0) return OriginalImageReadResult.TooLarge
        val sourceBytes =
            try {
                val stream = contentResolver.openInputStream(uri) ?: return OriginalImageReadResult.Failed
                stream.use { readBoundedBytes(it, maxBytes) } ?: return OriginalImageReadResult.TooLarge
            } catch (_: java.io.IOException) {
                return OriginalImageReadResult.Failed
            } catch (_: SecurityException) {
                return OriginalImageReadResult.Failed
            }
        val mediaType = originalImageMediaType(sourceBytes) ?: return OriginalImageReadResult.Unsupported
        // If the camera stored a raw landscape sensor buffer plus EXIF
        // Orientation=Rotate90/270, stripping APP1 would make "Original"
        // sends display sideways. Fall back to the JPEG privacy path so the
        // orientation is baked into pixels while still dropping metadata.
        if (exifOrientationRequiresPixelTransform(readExifOrientation(sourceBytes))) {
            return OriginalImageReadResult.Unsupported
        }
        val stripped = stripOriginalImageMetadata(sourceBytes) ?: return OriginalImageReadResult.Unsupported
        val dim = imageDimOrNull(stripped) ?: return OriginalImageReadResult.Failed
        val thumbhash =
            runCatching {
                val bitmap = decodeSampledBitmap(stripped, THUMBNAIL_MAX_EDGE_PX)
                try {
                    bitmap?.let { Thumbhash.encodeFromBitmap(it) }
                } finally {
                    bitmap?.recycle()
                }
            }.getOrNull()
        return OriginalImageReadResult.Success(
            OriginalImage(
                bytes = stripped,
                mediaType = mediaType,
                fileName = queryDisplayNameFromResolver(contentResolver, uri) ?: defaultOriginalImageFileName(mediaType),
                dim = dim,
                thumbhash = thumbhash,
            ),
        )
    }

    internal fun stripOriginalImageMetadata(bytes: ByteArray): ByteArray? =
        when (originalImageKind(bytes)) {
            OriginalImageKind.Jpeg -> stripJpegMetadata(bytes)
            OriginalImageKind.Png -> stripPngMetadata(bytes)
            OriginalImageKind.Webp -> stripWebpMetadata(bytes)
            null -> null
        }

    /**
     * Decode the image at [uri], downscale so the longer edge ≤ [maxEdgePx],
     * apply JPEG EXIF orientation to the pixels, and re-encode as JPEG at
     * [quality]. Returns null when the source can't be decoded (corrupt file,
     * unsupported format, unreadable Uri).
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
            val orientation = readExifOrientation(contentResolver, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { stream ->
                stream ?: return null
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            val target = orientedDecodeTarget(srcW, srcH, maxEdgePx, orientation) ?: return null

            val decoded: Bitmap =
                contentResolver.openInputStream(uri).use { stream ->
                    stream ?: return null
                    val opts = BitmapFactory.Options().apply { inSampleSize = target.sampleSize }
                    BitmapFactory.decodeStream(stream, null, opts) ?: return null
                }
            val scaled = scaleAndOrientBitmap(decoded, target.rawWidth, target.rawHeight, orientation) ?: return null

            // Flatten alpha onto opaque white before hashing AND compressing
            // so the thumbhash describes the exact pixels we ship. JPEG drops
            // alpha by white-compositing inside compress(); without this step,
            // a transparent PNG source hashes its alpha-blended pixels while
            // the JPEG bytes carry the white-composite version — receivers
            // would render a placeholder that doesn't match the final image.
            var opaque: Bitmap? = null
            try {
                opaque =
                    if (scaled.hasAlpha()) {
                        if (scaled.isMutable) {
                            Canvas(scaled).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                            scaled
                        } else {
                            Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                                Canvas(it).apply {
                                    drawColor(Color.WHITE)
                                    drawBitmap(scaled, 0f, 0f, null)
                                }
                            }
                        }
                    } else {
                        scaled
                    }
                val bitmap = opaque ?: return null
                val width = bitmap.width
                val height = bitmap.height
                // Failures degrade silently — a missing hash just means
                // receivers don't get a placeholder; it's not a reason to
                // drop the upload.
                val thumbhash = runCatching { Thumbhash.encodeFromBitmap(bitmap) }.getOrNull()
                ByteArrayOutputStream().use { out ->
                    // compress() returns false on failure — don't ship partial bytes.
                    if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                        DownscaledJpeg(out.toByteArray(), width, height, thumbhash)
                    } else {
                        null
                    }
                }
            } finally {
                if (opaque !== null && opaque !== scaled) opaque.recycle()
                scaled.recycle()
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: OutOfMemoryError) {
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

    private enum class OriginalImageKind { Jpeg, Png, Webp }

    private fun originalImageKind(bytes: ByteArray): OriginalImageKind? =
        when {
            isJpeg(bytes) -> OriginalImageKind.Jpeg
            isPng(bytes) -> OriginalImageKind.Png
            isWebp(bytes) -> OriginalImageKind.Webp
            else -> null
        }

    private fun originalImageMediaType(bytes: ByteArray): String? =
        when (originalImageKind(bytes)) {
            OriginalImageKind.Jpeg -> "image/jpeg"
            OriginalImageKind.Png -> "image/png"
            OriginalImageKind.Webp -> "image/webp"
            null -> null
        }

    private fun defaultOriginalImageFileName(mediaType: String): String =
        when (mediaType) {
            "image/png" -> "image.png"
            "image/webp" -> "image.webp"
            else -> "image.jpg"
        }

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 2 && u8(bytes, 0) == 0xff && u8(bytes, 1) == 0xd8

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private fun isWebp(bytes: ByteArray): Boolean = bytes.size >= 12 && asciiEquals(bytes, 0, "RIFF") && asciiEquals(bytes, 8, "WEBP")

    internal fun jpegExifOrientation(bytes: ByteArray): Int? {
        if (!isJpeg(bytes)) return null
        var pos = 2
        while (pos + 4 <= bytes.size) {
            if (u8(bytes, pos) != 0xff) return null
            while (pos < bytes.size && u8(bytes, pos) == 0xff) pos++
            if (pos >= bytes.size) return null
            val marker = u8(bytes, pos++)
            if (marker == 0xda || marker == 0xd9) return null
            if (marker == 0x00 || marker == 0x01 || marker in 0xd0..0xd7) continue
            if (pos + 2 > bytes.size) return null
            val length = u16be(bytes, pos)
            if (length < 2 || pos + length > bytes.size) return null
            val payloadStart = pos + 2
            val payloadLength = length - 2
            if (marker == 0xe1 && payloadLength >= EXIF_PREAMBLE.size) {
                val isExif = EXIF_PREAMBLE.indices.all { bytes[payloadStart + it] == EXIF_PREAMBLE[it] }
                if (isExif) {
                    parseTiffOrientation(
                        bytes = bytes,
                        tiffStart = payloadStart + EXIF_PREAMBLE.size,
                        tiffLength = payloadLength - EXIF_PREAMBLE.size,
                    )?.let { return it }
                }
            }
            pos += length
        }
        return null
    }

    private fun parseTiffOrientation(
        bytes: ByteArray,
        tiffStart: Int,
        tiffLength: Int,
    ): Int? {
        if (tiffLength < 8) return null
        val tiffEnd = tiffStart + tiffLength
        if (tiffStart < 0 || tiffEnd > bytes.size) return null
        val littleEndian =
            when {
                bytes[tiffStart] == 'I'.code.toByte() && bytes[tiffStart + 1] == 'I'.code.toByte() -> true
                bytes[tiffStart] == 'M'.code.toByte() && bytes[tiffStart + 1] == 'M'.code.toByte() -> false
                else -> return null
            }
        if (u16(bytes, tiffStart + 2, littleEndian) != 42) return null
        val ifdOffset = u32(bytes, tiffStart + 4, littleEndian)
        if (ifdOffset > Int.MAX_VALUE) return null
        val ifdStart = tiffStart + ifdOffset.toInt()
        if (ifdStart < tiffStart || ifdStart + 2 > tiffEnd) return null
        val entryCount = u16(bytes, ifdStart, littleEndian)
        val entriesStart = ifdStart + 2
        val entriesBytes = entryCount.toLong() * TIFF_IFD_ENTRY_SIZE.toLong()
        if (entriesBytes > Int.MAX_VALUE || entriesStart + entriesBytes.toInt() > tiffEnd) return null
        repeat(entryCount) { index ->
            val entry = entriesStart + index * TIFF_IFD_ENTRY_SIZE
            val tag = u16(bytes, entry, littleEndian)
            if (tag != EXIF_ORIENTATION_TAG) return@repeat
            val type = u16(bytes, entry + 2, littleEndian)
            val count = u32(bytes, entry + 4, littleEndian)
            if (type != TIFF_TYPE_SHORT || count < 1L) return null
            val value =
                if (count == 1L) {
                    u16(bytes, entry + 8, littleEndian)
                } else {
                    val valueOffset = u32(bytes, entry + 8, littleEndian)
                    if (valueOffset > Int.MAX_VALUE) return null
                    val valuePos = tiffStart + valueOffset.toInt()
                    if (valuePos < tiffStart || valuePos + 2 > tiffEnd) return null
                    u16(bytes, valuePos, littleEndian)
                }
            return normalizeExifOrientation(value).takeIf { it == value }
        }
        return null
    }

    private fun stripJpegMetadata(bytes: ByteArray): ByteArray? {
        if (!isJpeg(bytes)) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2)
        var pos = 2
        while (pos < bytes.size) {
            val markerStart = pos
            if (u8(bytes, pos) != 0xff) return null
            while (pos < bytes.size && u8(bytes, pos) == 0xff) pos++
            if (pos >= bytes.size) return null
            val marker = u8(bytes, pos++)
            if (marker == 0x00) return null
            if (marker == 0xd9 || marker in 0xd0..0xd7 || marker == 0x01) {
                out.write(bytes, markerStart, pos - markerStart)
                if (marker == 0xd9) return out.toByteArray()
                continue
            }
            if (pos + 2 > bytes.size) return null
            val length = u16be(bytes, pos)
            if (length < 2 || pos + length > bytes.size) return null
            val segmentEnd = pos + length
            if (marker == 0xda) {
                out.write(bytes, markerStart, segmentEnd - markerStart)
                var scanPos = segmentEnd
                var nextMarkerStart = -1
                while (scanPos < bytes.size) {
                    if (u8(bytes, scanPos) != 0xff) {
                        scanPos++
                        continue
                    }
                    val candidateStart = scanPos
                    while (scanPos < bytes.size && u8(bytes, scanPos) == 0xff) scanPos++
                    if (scanPos >= bytes.size) return null
                    val candidate = u8(bytes, scanPos)
                    if (candidate == 0x00) {
                        scanPos++ // Stuffed 0xff byte inside entropy-coded data.
                    } else if (candidate in 0xd0..0xd7) {
                        scanPos++ // Restart markers are part of the scan stream.
                    } else {
                        nextMarkerStart = candidateStart
                        break
                    }
                }
                if (nextMarkerStart < 0) return null
                out.write(bytes, segmentEnd, nextMarkerStart - segmentEnd)
                pos = nextMarkerStart
                continue
            }
            if (!isJpegMetadataMarker(marker)) {
                out.write(bytes, markerStart, segmentEnd - markerStart)
            }
            pos = segmentEnd
        }
        return null
    }

    private fun isJpegMetadataMarker(marker: Int): Boolean =
        marker == 0xe1 ||
            // EXIF and XMP APP1 payloads.
            marker == 0xed ||
            // Photoshop/IPTC APP13 payloads.
            marker == 0xfe // User comments can carry device/location notes.

    private fun stripPngMetadata(bytes: ByteArray): ByteArray? {
        if (!isPng(bytes)) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, PNG_SIGNATURE.size)
        var pos = PNG_SIGNATURE.size
        while (pos + PNG_CHUNK_OVERHEAD <= bytes.size) {
            val length = u32be(bytes, pos)
            val dataStart = pos + 8
            val chunkEndLong = dataStart.toLong() + length + 4L
            if (chunkEndLong > bytes.size.toLong()) return null
            val chunkEnd = chunkEndLong.toInt()
            val type = ascii(bytes, pos + 4, 4)
            if (!PNG_METADATA_CHUNKS.contains(type)) {
                out.write(bytes, pos, chunkEnd - pos)
            }
            pos = chunkEnd
            if (type == "IEND") return out.toByteArray()
        }
        return null
    }

    private fun stripWebpMetadata(bytes: ByteArray): ByteArray? {
        if (!isWebp(bytes)) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 4) // RIFF
        writeU32le(out, 0) // patched after chunk filtering
        out.write(bytes, 8, 4) // WEBP
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkType = ascii(bytes, pos, 4)
            val chunkSize = u32le(bytes, pos + 4)
            val dataStart = pos + 8
            val dataEndLong = dataStart.toLong() + chunkSize
            val paddedEndLong = dataEndLong + (chunkSize and 1L)
            if (paddedEndLong > bytes.size.toLong()) return null
            val paddedEnd = paddedEndLong.toInt()
            when (chunkType) {
                "EXIF", "XMP " -> Unit
                "VP8X" -> {
                    val chunk = bytes.copyOfRange(pos, paddedEnd)
                    if (chunkSize > 0) {
                        // Clear the EXIF and XMP presence bits after dropping those chunks.
                        chunk[8] = (chunk[8].toInt() and 0xf3).toByte()
                    }
                    out.write(chunk, 0, chunk.size)
                }
                else -> out.write(bytes, pos, paddedEnd - pos)
            }
            pos = paddedEnd
        }
        if (pos != bytes.size) return null
        val result = out.toByteArray()
        writeU32le(result, 4, result.size - 8)
        return result
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private const val PNG_CHUNK_OVERHEAD = 12
    private val PNG_METADATA_CHUNKS = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")
    private val EXIF_PREAMBLE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\u0000\u0000"
    private const val EXIF_FALLBACK_PREFIX_BYTES = 256 * 1024
    private const val EXIF_ORIENTATION_TAG = 0x0112
    private const val TIFF_IFD_ENTRY_SIZE = 12
    private const val TIFF_TYPE_SHORT = 3

    private fun u8(
        bytes: ByteArray,
        offset: Int,
    ): Int = bytes[offset].toInt() and 0xff

    private fun u16be(
        bytes: ByteArray,
        offset: Int,
    ): Int = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private fun u16le(
        bytes: ByteArray,
        offset: Int,
    ): Int = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    private fun u16(
        bytes: ByteArray,
        offset: Int,
        littleEndian: Boolean,
    ): Int = if (littleEndian) u16le(bytes, offset) else u16be(bytes, offset)

    private fun u32be(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (u8(bytes, offset).toLong() shl 24) or
            (u8(bytes, offset + 1).toLong() shl 16) or
            (u8(bytes, offset + 2).toLong() shl 8) or
            u8(bytes, offset + 3).toLong()

    private fun u32le(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        u8(bytes, offset).toLong() or
            (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or
            (u8(bytes, offset + 3).toLong() shl 24)

    private fun u32(
        bytes: ByteArray,
        offset: Int,
        littleEndian: Boolean,
    ): Long = if (littleEndian) u32le(bytes, offset) else u32be(bytes, offset)

    private fun ascii(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String = String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun asciiEquals(
        bytes: ByteArray,
        offset: Int,
        value: String,
    ): Boolean = offset + value.length <= bytes.size && ascii(bytes, offset, value.length) == value

    private fun writeU32le(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    private fun writeU32le(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
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

    /** 50 MB per video — matches the shipped iOS cap. */
    const val VIDEO_MAX_BYTES: Long = 50L * 1024L * 1024L

    data class VideoForUpload(
        val bytes: ByteArray,
        val mediaType: String,
        val fileName: String,
        val width: Int,
        val height: Int,
        val thumbhash: String?,
    )

    sealed class VideoReadResult {
        data class Success(
            val video: VideoForUpload,
        ) : VideoReadResult()

        data object TooLarge : VideoReadResult()

        data object Failed : VideoReadResult()
    }

    /**
     * Read the picked video URI as-is — no transcoding, matching the
     * White Noise iOS and desktop behavior. Extracts dimensions
     * and a thumbhash from the poster frame so receivers paint a blurred
     * preview before the full payload downloads. Returns a typed result so
     * callers can surface "too large" vs "decode failed" as distinct
     * toasts: [VideoReadResult.TooLarge] fires when the source exceeds
     * [VIDEO_MAX_BYTES] OR the caller's remaining album budget, while
     * [VideoReadResult.Failed] is for I/O / metadata extraction errors.
     */
    fun readVideoForUpload(
        context: android.content.Context,
        uri: Uri,
        remainingBytes: Long = VIDEO_MAX_BYTES,
    ): VideoReadResult {
        val resolver = context.contentResolver
        val mime =
            resolver.getType(uri)?.takeIf { it.startsWith("video/", ignoreCase = true) }
                ?: return VideoReadResult.Failed
        val displayName = queryDisplayNameFromResolver(resolver, uri) ?: "video.mp4"

        val perFileCap = minOf(VIDEO_MAX_BYTES, remainingBytes.coerceAtLeast(0L))
        if (perFileCap <= 0L) return VideoReadResult.TooLarge

        // MediaMetadataRetriever needs a seekable source. Stream the picked
        // video straight into a temp file under the wipe-covered video cache
        // instead of first growing a ByteArrayOutputStream and then copying it
        // into the file; the final upload ByteArray is allocated once from the
        // finished file below. If the process dies before the `finally` delete,
        // sign-out wipe and the startup janitor still reclaim the plaintext.
        val tmp = createVideoMetadataTempFile(context.cacheDir) ?: return VideoReadResult.Failed
        try {
            val copiedWithinCap =
                runCatching {
                    resolver.openInputStream(uri)?.use { stream ->
                        copyStreamToFileWithinCap(stream, tmp, perFileCap)
                    }
                }.getOrNull()
            when (copiedWithinCap) {
                true -> Unit
                false -> return VideoReadResult.TooLarge
                null -> return VideoReadResult.Failed
            }

            val mmr = android.media.MediaMetadataRetriever()
            try {
                mmr.setDataSource(tmp.absolutePath)
                val width =
                    mmr
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                val height =
                    mmr
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                // Thumbhash output is tiny (~25 bytes), so we just need a
                // representative downscaled frame — don't pull a 4K bitmap
                // through to encode it.
                val poster =
                    mmr.getScaledFrameAtTime(
                        0L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        THUMBNAIL_MAX_EDGE_PX,
                        THUMBNAIL_MAX_EDGE_PX,
                    )
                val thumbhash = posterThumbhash(poster)
                val bytes = runCatching { readFileBytesExact(tmp) }.getOrElse { return VideoReadResult.Failed }
                return VideoReadResult.Success(
                    VideoForUpload(
                        bytes = bytes,
                        mediaType = mime,
                        fileName = displayName,
                        width = width,
                        height = height,
                        thumbhash = thumbhash,
                    ),
                )
            } finally {
                runCatching { mmr.release() }
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun posterThumbhash(poster: Bitmap?): String? {
        if (poster == null) return null
        return try {
            // ThumbHash needs an opaque RGB bitmap; matches the image-pipeline
            // convention. Keep the allocation inside the guard: some codecs ignore
            // getScaledFrameAtTime's cap and can hand back a very large frame.
            var opaque: Bitmap? = null
            try {
                opaque = Bitmap.createBitmap(poster.width, poster.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(opaque)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(poster, 0f, 0f, null)
                Thumbhash.encodeFromBitmap(opaque)
            } finally {
                opaque?.recycle()
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            poster.recycle()
        }
    }

    private fun queryDisplayNameFromResolver(
        resolver: ContentResolver,
        uri: Uri,
    ): String? =
        runCatching {
            resolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        }.getOrNull()

    internal fun createVideoMetadataTempFile(cacheDir: java.io.File): java.io.File? =
        runCatching {
            val dir = java.io.File(cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }
            java.io.File.createTempFile("vidmeta-", null, dir)
        }.getOrNull()
}
