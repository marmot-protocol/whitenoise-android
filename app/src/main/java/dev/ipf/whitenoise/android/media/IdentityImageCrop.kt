package dev.ipf.whitenoise.android.media

import android.content.ContentResolver
import android.net.Uri
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.NormalizedRect
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorInspectResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Whether an identity image is presented as an avatar circle or a group's rounded square. */
internal enum class IdentityImageCropShape { Circle, RoundedSquare }

/** How far an identity image may be enlarged before the crop stops shrinking. */
internal const val IDENTITY_IMAGE_MAX_ZOOM = 8f

/** The largest source this crop will read; the renderer applies its own pixel limits after that. */
internal const val IDENTITY_IMAGE_SOURCE_MAX_BYTES = 16 * 1024 * 1024

/**
 * Which square of a chosen picture becomes someone's avatar or a group's image.
 *
 * This is the whole contract shared by the profile, onboarding and group entry points, and it is
 * deliberately not an editing session: there is no draft, no store and no history, because an
 * identity image is published on its own rather than staged alongside a message. It describes a
 * focal point and a zoom rather than a rectangle so the same value survives a source whose
 * dimensions are not known until it is decoded.
 *
 * The rectangle is derived in oriented-source space, which is where [PhotoEditRecipe] applies a crop
 * before it rotates, so a square stays square under any quarter turn.
 */
internal data class IdentityImageCrop(
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
    val quarterTurnsClockwise: Int = 0,
) {
    init {
        require(focusX.isFinite() && focusY.isFinite()) { "Identity crop focus must be finite" }
        require(zoom.isFinite() && zoom >= 1f) { "Identity crop zoom must be at least one" }
        require(quarterTurnsClockwise in 0 until QUARTER_TURNS) { "Identity crop quarter turns must be canonical" }
    }

    /**
     * The square this crop selects from a source of [oriented] pixels.
     *
     * The square is the shorter edge divided by the zoom, so zoom 1 always keeps the largest square
     * the picture can give. The focus is clamped to keep that square on the image, which is what
     * stops a pan from sliding past an edge and leaving a band of nothing in the avatar.
     */
    fun rectFor(oriented: EditorPixelSize): NormalizedRect {
        val (halfWidth, halfHeight) = halfExtents(oriented)
        val centerX = focusX.coerceIn(halfWidth, 1f - halfWidth)
        val centerY = focusY.coerceIn(halfHeight, 1f - halfHeight)
        return NormalizedRect(
            left = centerX - halfWidth,
            top = centerY - halfHeight,
            right = centerX + halfWidth,
            bottom = centerY + halfHeight,
        )
    }

    /** Half the selected square, as a share of each source edge. */
    fun halfExtents(oriented: EditorPixelSize): Pair<Float, Float> {
        val effectiveZoom = zoom.coerceIn(1f, IDENTITY_IMAGE_MAX_ZOOM)
        val sidePx = min(oriented.width, oriented.height) / effectiveZoom
        val halfSidePx = sidePx * HALF_EXTENT_CEILING
        return Pair(
            (halfSidePx / oriented.width).coerceIn(MIN_HALF_EXTENT, HALF_EXTENT_CEILING),
            (halfSidePx / oriented.height).coerceIn(MIN_HALF_EXTENT, HALF_EXTENT_CEILING),
        )
    }

    /**
     * The same crop after the picture is dragged by ([dragXPx], [dragYPx]) inside a [viewportPx] mask.
     *
     * Dragging the picture right shows what was to its left, so the focus moves the other way. The
     * result is clamped rather than the drag, so a gesture that runs past an edge slides along it
     * instead of stopping dead, and the stored focus never drifts outside the picture.
     */
    fun pannedBy(
        dragXPx: Float,
        dragYPx: Float,
        viewportPx: Float,
        oriented: EditorPixelSize,
    ): IdentityImageCrop {
        if (viewportPx <= 0f || !dragXPx.isFinite() || !dragYPx.isFinite()) return this
        val (halfWidth, halfHeight) = halfExtents(oriented)
        val movedX = focusX - (dragXPx / viewportPx) * (halfWidth * 2f)
        val movedY = focusY - (dragYPx / viewportPx) * (halfHeight * 2f)
        return copy(
            focusX = movedX.coerceIn(halfWidth, 1f - halfWidth),
            focusY = movedY.coerceIn(halfHeight, 1f - halfHeight),
        )
    }

    /** The same crop after a pinch of [factor], held inside the supported zoom range. */
    fun zoomedBy(factor: Float): IdentityImageCrop {
        if (!factor.isFinite() || factor <= 0f) return this
        return copy(zoom = (zoom * factor).coerceIn(1f, IDENTITY_IMAGE_MAX_ZOOM))
    }

    /** The same crop turned a quarter turn clockwise. */
    fun rotatedClockwise(): IdentityImageCrop {
        val turns = (quarterTurnsClockwise + 1) % QUARTER_TURNS
        return copy(quarterTurnsClockwise = turns)
    }

    /** The recipe that turns a source into exactly the pixels this crop selects. */
    fun recipeFor(oriented: EditorPixelSize): PhotoEditRecipe {
        val turns = quarterTurnsClockwise
        return PhotoEditRecipe(crop = rectFor(oriented), quarterTurnsClockwise = turns)
    }

    internal companion object {
        /** Keeps a crop from collapsing to nothing on a source with an extreme aspect ratio. */
        private const val MIN_HALF_EXTENT = 0.000_5f
        private const val QUARTER_TURNS = 4

        /** Half of a whole edge: the largest a crop can ever be in either direction. */
        private const val HALF_EXTENT_CEILING = 0.5f

        val Centered = IdentityImageCrop()
    }
}

/**
 * Renders [crop] against [sourceBytes] and returns the bytes that will be published.
 *
 * Only the cropped pixels leave this function. Nothing records where the crop was taken from, so no
 * focal metadata is invented for a protocol that has no field for it.
 */
internal suspend fun renderIdentityImageDraft(
    sourceBytes: ByteArray,
    crop: IdentityImageCrop,
    quality: MediaQuality,
    renderer: PhotoEditorRenderer = PhotoEditorRenderer(),
): ImageUploadDraft {
    val inspected = renderer.inspect(sourceBytes)
    val oriented =
        (inspected as? PhotoEditorInspectResult.Success)?.source?.orientedSize
            ?: throw ImageUploadPreparationException.UnsupportedImage
    val rendered = renderer.render(sourceBytes, crop.recipeFor(oriented), quality)
    if (rendered !is PhotoEditorRenderResult.Success) throw rendered.asPreparationFailure()
    return rendered.image.asUploadDraft()
}

/** Why a render that did not succeed cannot produce an identity image. */
private fun PhotoEditorRenderResult.asPreparationFailure(): ImageUploadPreparationException =
    when (this) {
        PhotoEditorRenderResult.MemoryLimit -> ImageUploadPreparationException.PreparedImageTooLarge
        else -> ImageUploadPreparationException.UnsupportedImage
    }

/** Converts rendered pixels into the draft every identity upload path already understands. */
private fun MediaPipeline.FinalizedImage.asUploadDraft(): ImageUploadDraft {
    if (bytes.size > REMOTE_PROFILE_IMAGE_MAX_BYTES) throw ImageUploadPreparationException.PreparedImageTooLarge
    return ImageUploadDraft(
        plaintext = bytes,
        mediaType = mediaType,
        sourceUrl = null,
        dim = "${width}x$height",
        thumbhash = thumbhash,
    )
}

/**
 * Reads a picked picture whole so it can be cropped from its own pixels.
 *
 * The existing identity path downscales while reading, which is right when the centre is taken as
 * given but would throw away the detail a zoomed crop needs, so this keeps the source until the
 * crop is known.
 */
internal suspend fun readIdentityImageSource(
    contentResolver: ContentResolver,
    uri: Uri,
    maxBytes: Int = IDENTITY_IMAGE_SOURCE_MAX_BYTES,
): ByteArray =
    withContext(Dispatchers.IO) {
        val stream =
            runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                ?: throw ImageUploadPreparationException.UnsupportedImage
        stream.use { MediaPipeline.readBoundedBytes(it, maxBytes) }
            ?: throw ImageUploadPreparationException.PreparedImageTooLarge
    }
