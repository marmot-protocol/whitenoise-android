package dev.ipf.whitenoise.android.media

// Android-free content admission for sources that could reach the native animated
// decoder. The JVM fuzz module compiles this file, AnimationSourceGif.kt,
// AnimationSourceWebp.kt and ImageContainerBytes.kt verbatim, so keep these
// sources free of Android APIs, pixel decoding and payload copies.

/** Largest accepted canvas edge for a native animation source. */
internal const val MAX_ANIMATION_SOURCE_EDGE_PX: Int = 4096

/** Largest accepted canvas area (4 Mi pixels) for a native animation source. */
internal const val MAX_ANIMATION_SOURCE_CANVAS_PIXELS: Long = 4L * 1024L * 1024L

/** Largest accepted frame count for a native animation source. */
internal const val MAX_ANIMATION_SOURCE_FRAMES: Int = 1000

/**
 * Provisional aggregate compositing budget (32 Mi pixels): canvas area times frame
 * count. Every frame is charged the whole canvas because a partial frame still
 * composites onto it. This is a work bound, not a peak-memory claim, and remains
 * pending ordinary-corpus and native qualification (docs/animation-source-admission.md).
 */
internal const val MAX_ANIMATION_SOURCE_WORK_PIXELS: Long = 32L * 1024L * 1024L

/** Container families that may reach the native animated decoder. */
internal enum class AnimationSourceKind {
    Gif,
    Webp,
}

/** Why a recognized GIF or WebP source may reach neither native decoder. */
internal enum class AnimationSourceRefusal {
    /** The recognized container is truncated, inconsistent or structurally invalid. */
    Malformed,

    /** The canvas exceeds [MAX_ANIMATION_SOURCE_EDGE_PX] or [MAX_ANIMATION_SOURCE_CANVAS_PIXELS]. */
    CanvasLimit,

    /** The source declares more than [MAX_ANIMATION_SOURCE_FRAMES] frames. */
    FrameLimit,

    /** Canvas area times frame count exceeds [MAX_ANIMATION_SOURCE_WORK_PIXELS]. */
    WorkLimit,
}

/** Content-derived decision about which decoder, if any, may see a source. */
internal sealed interface AnimationSourceAdmission {
    /** Neither GIF nor animated WebP: a non-GIF/WebP signature or a fully walked still WebP. */
    data object NotAnimation : AnimationSourceAdmission

    /** A fully walked GIF or animated WebP inside every canvas, frame and work limit. */
    data class Admitted(
        val kind: AnimationSourceKind,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val frameCount: Int,
    ) : AnimationSourceAdmission

    /** A recognized GIF/WebP source that no decoder may receive. */
    data class Refused(
        val reason: AnimationSourceRefusal,
    ) : AnimationSourceAdmission
}

/**
 * Classifies [bytes] from content alone, so an advertised MIME type can neither
 * enable nor bypass animation admission. Every recognized GIF or WebP is walked
 * completely; malformed recognized bytes are [AnimationSourceAdmission.Refused],
 * never [AnimationSourceAdmission.NotAnimation].
 */
internal fun admitAnimationSource(bytes: ByteArray): AnimationSourceAdmission =
    when {
        isGif(bytes) -> admitGifAnimationSource(bytes)
        isWebp(bytes) -> admitWebpAnimationSource(bytes)
        else -> AnimationSourceAdmission.NotAnimation
    }

/** Shared refusal for structurally invalid recognized containers. */
internal val malformedAnimationSource: AnimationSourceAdmission =
    AnimationSourceAdmission.Refused(AnimationSourceRefusal.Malformed)

/** True when [length] bytes starting at [offset] lie inside [bytes]; overflow-safe. */
internal fun hasAnimationSourceRange(
    bytes: ByteArray,
    offset: Long,
    length: Long,
): Boolean = offset >= 0L && length >= 0L && offset <= bytes.size.toLong() - length

/** Charges each discovered frame the full canvas and stops the walk at the first exceeded limit. */
internal class AnimationSourceBudget(
    val canvasWidth: Int,
    val canvasHeight: Int,
) {
    private val canvasPixels = canvasWidth.toLong() * canvasHeight.toLong()

    /** Non-null when the canvas itself is empty or exceeds a per-canvas limit. */
    val canvasRefusal: AnimationSourceRefusal? =
        when {
            canvasWidth <= 0 || canvasHeight <= 0 -> AnimationSourceRefusal.Malformed
            canvasWidth > MAX_ANIMATION_SOURCE_EDGE_PX ||
                canvasHeight > MAX_ANIMATION_SOURCE_EDGE_PX ||
                canvasPixels > MAX_ANIMATION_SOURCE_CANVAS_PIXELS -> AnimationSourceRefusal.CanvasLimit
            else -> null
        }

    /** Frames charged so far. */
    var frames: Int = 0
        private set

    /** Charges one more frame; returns the limit it exceeded, if any. */
    fun addFrame(): AnimationSourceRefusal? {
        frames++
        // canvasPixels <= 2^48 and frames <= MAX + 1, so the product cannot overflow a Long.
        return when {
            frames > MAX_ANIMATION_SOURCE_FRAMES -> AnimationSourceRefusal.FrameLimit
            canvasPixels * frames > MAX_ANIMATION_SOURCE_WORK_PIXELS -> AnimationSourceRefusal.WorkLimit
            else -> null
        }
    }

    /** The admission for a completely walked source of [kind]. */
    fun admitted(kind: AnimationSourceKind): AnimationSourceAdmission =
        AnimationSourceAdmission.Admitted(kind, canvasWidth, canvasHeight, frames)
}
