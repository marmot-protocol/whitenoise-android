package dev.ipf.darkmatter.media

import android.graphics.Bitmap
import android.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compact perceptual hash for thumbnail placeholders. Port of the reference
 * thumbhash encoder (https://evanw.github.io/thumbhash/) — given a small
 * image (≤100×100), produces a ~20-25 byte hash that decodes to a tiny
 * blurred preview a receiver can render before the full bytes arrive.
 *
 * Marmot's NIP-92 `imeta` accepts a base64 thumbhash field; this encoder
 * matches the canonical wire format so other clients can decode it.
 */
object Thumbhash {
    /**
     * Encode [bitmap] to a base64-no-padding thumbhash string, or null when
     * the bitmap can't be sampled (too small, recycled, or so dim the
     * algorithm degenerates). Caller is responsible for downscaling the
     * source to ≤100×100 — call [encodeFromBitmap] for the convenience
     * wrapper that does the downscale.
     */
    fun encodeBase64(bitmap: Bitmap): String? {
        val raw = encode(bitmap) ?: return null
        return Base64.encodeToString(raw, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Downscale [source] so the longer edge is ≤ [MAX_EDGE_PX], encode to a
     * thumbhash, base64 the result. Returns null when [source] is blank,
     * recycled, or any intermediate step fails. The downscaled bitmap is
     * recycled before return; the caller's source bitmap is untouched.
     */
    fun encodeFromBitmap(source: Bitmap): String? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val (w, h) = scaledDimensions(source.width, source.height)
        val sampled =
            if (w == source.width && h == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, w, h, true)
            }
        return try {
            encodeBase64(sampled)
        } finally {
            if (sampled !== source) sampled.recycle()
        }
    }

    internal const val MAX_EDGE_PX: Int = 100

    /**
     * Decode a base64 thumbhash string into a small ARGB_8888 bitmap
     * suitable for a blurred placeholder. Inverse of [encodeBase64]. Caller
     * is expected to scale the result to fit the destination box (e.g.
     * via `Image(... contentScale = ContentScale.Crop)`). Returns null
     * when the input can't be base64-decoded, is too short, or doesn't
     * produce a sensible decoded bitmap.
     */
    fun decodeToBitmap(base64Hash: String): Bitmap? {
        val bytes =
            runCatching {
                Base64.decode(base64Hash, Base64.NO_PADDING or Base64.NO_WRAP)
            }.getOrNull() ?: return null
        val decoded = runCatching { decodeRgba(bytes) }.getOrNull() ?: return null
        return Bitmap.createBitmap(decoded.pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Decoded thumbhash as an ARGB pixel grid. Width/height are the
     * algorithm's natural output (≤32 on each axis); callers scale to fit.
     */
    internal data class DecodedRgba(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    internal fun decodeRgba(hash: ByteArray): DecodedRgba? {
        if (hash.size < 5) return null
        val h0 = hash[0].toInt() and 0xFF
        val h1 = hash[1].toInt() and 0xFF
        val h2 = hash[2].toInt() and 0xFF
        val h3 = hash[3].toInt() and 0xFF
        val h4 = hash[4].toInt() and 0xFF
        val header24 = h0 or (h1 shl 8) or (h2 shl 16)
        val header16 = h3 or (h4 shl 8)
        val lDc = (header24 and 63) / 63.0
        val pDc = ((header24 shr 6) and 63) / 31.5 - 1.0
        val qDc = ((header24 shr 12) and 63) / 31.5 - 1.0
        val lScale = ((header24 shr 18) and 31) / 31.0
        val hasAlpha = (header24 shr 23) and 1 == 1
        val pScale = ((header16 shr 3) and 63) / 63.0
        val qScale = ((header16 shr 9) and 63) / 63.0
        val isLandscape = (header16 shr 15) and 1 == 1
        val lMaxBits = header16 and 7
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else lMaxBits)
        val ly = max(3, if (isLandscape) lMaxBits else (if (hasAlpha) 5 else 7))

        val aDc: Double
        val aScale: Double
        val acStart: Int
        if (hasAlpha) {
            if (hash.size < 6) return null
            val h5 = hash[5].toInt() and 0xFF
            aDc = (h5 and 15) / 15.0
            aScale = ((h5 shr 4) and 15) / 15.0
            acStart = 6
        } else {
            aDc = 1.0
            aScale = 0.0
            acStart = 5
        }

        var acIndex = 0

        fun decodeChannel(
            nx: Int,
            ny: Int,
            scale: Double,
        ): DoubleArray {
            val ac = ArrayList<Double>(nx * ny)
            for (cy in 0 until ny) {
                var cx = if (cy == 0) 1 else 0
                while (cx * ny < nx * (ny - cy)) {
                    val slot = acStart + (acIndex shr 1)
                    val nibble =
                        if (slot < hash.size) {
                            (hash[slot].toInt() shr ((acIndex and 1) shl 2)) and 15
                        } else {
                            // Truncated hash — pad with neutral nibble so the
                            // decoder degrades gracefully rather than throwing.
                            7
                        }
                    ac.add((nibble / 7.5 - 1.0) * scale)
                    acIndex += 1
                    cx += 1
                }
            }
            return ac.toDoubleArray()
        }

        val lAc = decodeChannel(lx, ly, lScale)
        val pAc = decodeChannel(3, 3, pScale * 1.25)
        val qAc = decodeChannel(3, 3, qScale * 1.25)
        val aAc = if (hasAlpha) decodeChannel(5, 5, aScale) else DoubleArray(0)

        val ratio = lx.toDouble() / ly.toDouble()
        val w = if (ratio > 1.0) 32 else (32 * ratio).roundToInt().coerceAtLeast(1)
        val h = if (ratio > 1.0) (32 / ratio).roundToInt().coerceAtLeast(1) else 32
        val pixels = IntArray(w * h)
        val cxStop = max(lx, if (hasAlpha) 5 else 3)
        val cyStop = max(ly, if (hasAlpha) 5 else 3)
        val fxBuf = DoubleArray(cxStop)
        val fyBuf = DoubleArray(cyStop)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var l = lDc
                var p = pDc
                var q = qDc
                var a = aDc
                for (cx in 0 until cxStop) fxBuf[cx] = cos(PI / w * (x + 0.5) * cx)
                for (cy in 0 until cyStop) fyBuf[cy] = cos(PI / h * (y + 0.5) * cy)

                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy == 0) 1 else 0
                    val fy2 = fyBuf[cy] * 2.0
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAc[j] * fxBuf[cx] * fy2
                        j += 1
                        cx += 1
                    }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy == 0) 1 else 0
                    val fy2 = fyBuf[cy] * 2.0
                    while (cx < 3 - cy) {
                        val f = fxBuf[cx] * fy2
                        if (j < pAc.size) p += pAc[j] * f
                        if (j < qAc.size) q += qAc[j] * f
                        j += 1
                        cx += 1
                    }
                }
                if (hasAlpha) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy == 0) 1 else 0
                        val fy2 = fyBuf[cy] * 2.0
                        while (cx < 5 - cy) {
                            if (j < aAc.size) a += aAc[j] * fxBuf[cx] * fy2
                            j += 1
                            cx += 1
                        }
                    }
                }

                val b = l - 2.0 / 3.0 * p
                val r = (3.0 * l - b + q) / 2.0
                val g = r - q
                val red = (255.0 * r.coerceIn(0.0, 1.0)).toInt()
                val green = (255.0 * g.coerceIn(0.0, 1.0)).toInt()
                val blue = (255.0 * b.coerceIn(0.0, 1.0)).toInt()
                val alpha = (255.0 * a.coerceIn(0.0, 1.0)).toInt()
                pixels[x + y * w] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return DecodedRgba(w, h, pixels)
    }

    internal fun scaledDimensions(
        srcW: Int,
        srcH: Int,
    ): Pair<Int, Int> {
        if (srcW <= MAX_EDGE_PX && srcH <= MAX_EDGE_PX) return srcW to srcH
        val longerEdge = max(srcW, srcH)
        val scale = MAX_EDGE_PX.toDouble() / longerEdge.toDouble()
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0 || w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return encodeFromRgba(w, h, pixels)
    }

    /**
     * Pure-pixel-input encoder core. Used both by the Bitmap-coupled
     * [encode] path and by unit tests running on plain JVM (where
     * `Bitmap.getPixels` is a stub). Pixels are ARGB_8888 packed ints
     * (alpha << 24 | red << 16 | green << 8 | blue).
     */
    internal fun encodeFromRgba(
        w: Int,
        h: Int,
        pixels: IntArray,
    ): ByteArray? {
        if (w <= 0 || h <= 0 || w > MAX_EDGE_PX || h > MAX_EDGE_PX) return null
        if (pixels.size != w * h) return null

        var avgR = 0.0
        var avgG = 0.0
        var avgB = 0.0
        var avgA = 0.0
        for (px in pixels) {
            val a = ((px shr 24) and 0xFF) / 255.0
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            avgR += a / 255.0 * r
            avgG += a / 255.0 * g
            avgB += a / 255.0 * b
            avgA += a
        }
        if (avgA > 0.0) {
            avgR /= avgA
            avgG /= avgA
            avgB /= avgA
        }

        val hasAlpha = avgA < w * h
        val lLimit = if (hasAlpha) 5 else 7
        val maxEdge = max(w, h)
        val lx = max(1, ((lLimit * w).toDouble() / maxEdge).roundToInt())
        val ly = max(1, ((lLimit * h).toDouble() / maxEdge).roundToInt())

        val lCh = DoubleArray(w * h)
        val pCh = DoubleArray(w * h)
        val qCh = DoubleArray(w * h)
        val aCh = DoubleArray(w * h)
        for (i in 0 until w * h) {
            val px = pixels[i]
            val alpha = ((px shr 24) and 0xFF) / 255.0
            val r = avgR * (1.0 - alpha) + alpha / 255.0 * ((px shr 16) and 0xFF)
            val g = avgG * (1.0 - alpha) + alpha / 255.0 * ((px shr 8) and 0xFF)
            val b = avgB * (1.0 - alpha) + alpha / 255.0 * (px and 0xFF)
            lCh[i] = (r + g + b) / 3.0
            pCh[i] = (r + g) / 2.0 - b
            qCh[i] = r - g
            aCh[i] = alpha
        }

        val (lDc, lAc, lScale) = encodeChannel(lCh, w, h, max(3, lx), max(3, ly))
        val (pDc, pAc, pScale) = encodeChannel(pCh, w, h, 3, 3)
        val (qDc, qAc, qScale) = encodeChannel(qCh, w, h, 3, 3)
        val (aDc, aAc, aScale) =
            if (hasAlpha) encodeChannel(aCh, w, h, 5, 5) else Triple(0.0, DoubleArray(0), 0.0)

        val isLandscape = w > h
        val header24 =
            (63.0 * lDc).roundToInt() or
                ((31.5 + 31.5 * pDc).roundToInt() shl 6) or
                ((31.5 + 31.5 * qDc).roundToInt() shl 12) or
                ((31.0 * lScale).roundToInt() shl 18) or
                ((if (hasAlpha) 1 else 0) shl 23)
        val lLengthBits = if (isLandscape) ly else lx
        val header16 =
            lLengthBits or
                ((63.0 * pScale).roundToInt() shl 3) or
                ((63.0 * qScale).roundToInt() shl 9) or
                ((if (isLandscape) 1 else 0) shl 15)
        val acStart = if (hasAlpha) 6 else 5
        val acCount = lAc.size + pAc.size + qAc.size + aAc.size
        val hash = ByteArray(acStart + (acCount + 1) / 2)
        hash[0] = (header24 and 0xFF).toByte()
        hash[1] = ((header24 shr 8) and 0xFF).toByte()
        hash[2] = ((header24 shr 16) and 0xFF).toByte()
        hash[3] = (header16 and 0xFF).toByte()
        hash[4] = ((header16 shr 8) and 0xFF).toByte()
        if (hasAlpha) {
            hash[5] = ((15.0 * aDc).roundToInt() or ((15.0 * aScale).roundToInt() shl 4)).toByte()
        }
        var acIndex = 0
        val acGroups = if (hasAlpha) arrayOf(lAc, pAc, qAc, aAc) else arrayOf(lAc, pAc, qAc)
        for (group in acGroups) {
            for (f in group) {
                val nibble = (15.0 * f).roundToInt() and 0x0F
                val slot = acStart + (acIndex shr 1)
                val shift = (acIndex and 1) shl 2
                hash[slot] = ((hash[slot].toInt() and 0xFF) or (nibble shl shift)).toByte()
                acIndex += 1
            }
        }
        return hash
    }

    private fun encodeChannel(
        channel: DoubleArray,
        w: Int,
        h: Int,
        nx: Int,
        ny: Int,
    ): Triple<Double, DoubleArray, Double> {
        var dc = 0.0
        val ac = ArrayList<Double>(nx * ny)
        var scale = 0.0
        val fx = DoubleArray(w)
        for (cy in 0 until ny) {
            var cx = 0
            while (cx * ny < nx * (ny - cy)) {
                for (x in 0 until w) fx[x] = cos(PI / w * cx * (x + 0.5))
                var f = 0.0
                for (y in 0 until h) {
                    val fy = cos(PI / h * cy * (y + 0.5))
                    for (x in 0 until w) f += channel[x + y * w] * fx[x] * fy
                }
                f /= (w * h).toDouble()
                if (cx > 0 || cy > 0) {
                    // Store raw f. The decoder applies the DCT-II
                    // reconstruction factor of 2 during synthesis, so
                    // doubling here would clamp high-amplitude chroma
                    // to the wrong sign after nibble quantization.
                    ac.add(f)
                    if (kotlin.math.abs(f) > scale) scale = kotlin.math.abs(f)
                } else {
                    dc = f
                }
                cx += 1
            }
        }
        val acArr = DoubleArray(ac.size) { ac[it] }
        if (scale > 0.0) {
            for (i in acArr.indices) acArr[i] = 0.5 + 0.5 / scale * acArr[i]
        }
        return Triple(dc, acArr, scale)
    }
}
