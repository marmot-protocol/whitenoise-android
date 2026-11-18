package dev.ipf.whitenoise.android.core

/** Decoded pixels are ARGB_8888, so a target's memory cost is four bytes each. */
private const val DECODED_BYTES_PER_PIXEL = 4

/** No single decode may exceed this, whatever aspect ratio the source has. */
internal const val MAX_PROFILE_IMAGE_DECODED_BYTES: Int = 8 * 1024 * 1024

/**
 * The largest long-edge target at or below [maxDimension] whose decode fits the byte budget.
 *
 * The dimension cap alone bounds a 2:1 banner, but a near-square source at the same long edge costs
 * twice as much, so the budget halves the target until the decode is affordable. For an avatar's
 * 512px cap the budget never binds, which keeps avatar memory exactly where it was.
 */
internal fun boundedDecodeDimension(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxDecodedBytes: Int = MAX_PROFILE_IMAGE_DECODED_BYTES,
): Int {
    var limit = maxDimension
    while (limit > 1) {
        val (scaledWidth, scaledHeight) = avatarScaledDimensions(width, height, limit)
        val decodedBytes = scaledWidth.toLong() * scaledHeight * DECODED_BYTES_PER_PIXEL
        if (decodedBytes <= maxDecodedBytes) return limit
        limit /= 2
    }
    return 1
}

internal fun scaleAvatarBitmapToMaxDimension(
    bitmap: android.graphics.Bitmap,
    maxDimension: Int,
): android.graphics.Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val (targetWidth, targetHeight) = avatarScaledDimensions(width, height, maxDimension)
    if (targetWidth == width && targetHeight == height) return bitmap
    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return scaled
}

internal fun avatarScaledDimensions(
    width: Int,
    height: Int,
    maxDimension: Int,
): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxDimension) return width to height
    return if (width >= height) {
        maxDimension to ((height.toLong() * maxDimension) / width).toInt().coerceAtLeast(1)
    } else {
        ((width.toLong() * maxDimension) / height).toInt().coerceAtLeast(1) to maxDimension
    }
}

/**
 * Bounded long-edge target for a banner rendered [requestedWidthPx] wide.
 *
 * Rounding up to a shared bucket means two surfaces whose measured widths differ by a few pixels
 * agree on one decode — and therefore one cache entry and one in-flight fetch — instead of racing
 * two. The floor is the avatar cap, so a banner is never decoded smaller than it already was, and
 * the ceiling keeps an implausibly wide window from asking for an unbounded bitmap.
 */
internal fun profileBannerDecodeDimension(
    requestedWidthPx: Int,
    bucketPx: Int = PROFILE_BANNER_TARGET_BUCKET_PX,
    minimumPx: Int = PROFILE_AVATAR_MAX_DIMENSION,
    maximumPx: Int = PROFILE_BANNER_MAX_DIMENSION,
): Int {
    val buckets = (requestedWidthPx.coerceAtLeast(0) + bucketPx - 1) / bucketPx
    return (buckets * bucketPx).coerceIn(minimumPx, maximumPx)
}

/** Mirrors the loader's own avatar cap, so a banner can never be decoded below it. */
internal const val PROFILE_AVATAR_MAX_DIMENSION: Int = 512

/** Hard upper bound on a banner's decoded long edge, whatever width a window reports. */
internal const val PROFILE_BANNER_MAX_DIMENSION: Int = 1536

/** Rendered widths round up to this bucket so neighbouring surfaces coalesce onto one decode. */
internal const val PROFILE_BANNER_TARGET_BUCKET_PX: Int = 256

internal fun avatarDecodeSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    if (width <= maxDimension && height <= maxDimension) return 1
    var sampleSize = 1
    while ((width / sampleSize) > maxDimension || (height / sampleSize) > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/** A transient decode may exceed the final budget while it is being scaled, but only this far. */
private const val TRANSIENT_DECODE_BUDGET_MULTIPLIER = 2

/**
 * Power-of-two sample whose decode still covers [targetDimension] on the long edge.
 *
 * [avatarDecodeSampleSize] samples until both edges are at or below its cap, which is right for a
 * ceiling and wrong for a target: a 4000px source sampled down to 1000px for a 1280px banner is then
 * upscaled again by the very surface that asked for 1280, which is the blur this avoids. Keeping one
 * sampling step in hand lets [scaleAvatarBitmapToMaxDimension] land on the target exactly. The
 * transient decode that step buys is itself bounded, so a very large source steps back down instead.
 */
internal fun bannerDecodeSampleSize(
    width: Int,
    height: Int,
    targetDimension: Int,
    maxTransientBytes: Int = MAX_PROFILE_IMAGE_DECODED_BYTES * TRANSIENT_DECODE_BUDGET_MULTIPLIER,
): Int {
    val longEdge = maxOf(width, height)
    if (longEdge <= 0 || targetDimension <= 0) return 1
    var sampleSize = 1
    while (longEdge / (sampleSize * 2) >= targetDimension) sampleSize *= 2
    while (sampleSize < longEdge && sampledDecodedBytes(width, height, sampleSize) > maxTransientBytes) {
        sampleSize *= 2
    }
    return sampleSize
}

/** Decoded ARGB cost of [width] x [height] sampled by [sampleSize]. */
private fun sampledDecodedBytes(
    width: Int,
    height: Int,
    sampleSize: Int,
): Long = (width / sampleSize).toLong() * (height / sampleSize) * DECODED_BYTES_PER_PIXEL

/** Each variant's sampling policy: an avatar honours a ceiling, a banner covers its target. */
internal fun profileDecodeSampleSize(
    width: Int,
    height: Int,
    variant: ProfileImageVariant,
    targetDimension: Int,
): Int =
    when (variant) {
        ProfileImageVariant.AVATAR -> avatarDecodeSampleSize(width, height, targetDimension)
        ProfileImageVariant.BANNER -> bannerDecodeSampleSize(width, height, targetDimension)
    }
