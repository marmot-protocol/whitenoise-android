package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle

/** OPEN can widen only an explicit original-canvas request inside the existing admission limits. */
internal fun readViewerAnimationOutput(data: Bundle): ViewerAnimationOutput? =
    if (data.getBoolean("viewerOriginal")) {
        requireNotNull(ViewerAnimationOutput.fromDimensions(data.getInt("viewerWidth"), data.getInt("viewerHeight")))
    } else {
        require(!data.containsKey("viewerWidth") && !data.containsKey("viewerHeight"))
        null
    }
