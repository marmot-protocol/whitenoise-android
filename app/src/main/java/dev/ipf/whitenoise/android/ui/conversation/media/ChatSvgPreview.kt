package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgDecoder
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgRegion

/** Chat-only still presentation; public profile/event raster acquisition is deliberately unchanged. */
internal fun decodeChatAttachmentStill(
    bytes: ByteArray,
    maxEdgePx: Int,
): Bitmap? {
    if (maxEdgePx <= 0 ||
        dev.ipf.whitenoise.android.media
            .admitAnimationSource(bytes) is dev.ipf.whitenoise.android.media.AnimationSourceAdmission.Refused
    ) {
        return null
    }
    MediaPipeline.decodeSampledBitmap(bytes, maxEdgePx)?.let { return it }
    // A failed raster is not SVG admission: the permissionless parser must positively validate it.
    val info = IsolatedMediaDecoder.svgInspect(bytes) ?: return null
    return IsolatedMediaDecoder.svgRegion(
        bytes,
        ViewerSvgRegion(0.0, 0.0, info.width.toDouble(), info.height.toDouble()),
        maxEdgePx.coerceAtMost(ViewerSvgDecoder.MAX_VIEWPORT_EDGE),
    )
}
