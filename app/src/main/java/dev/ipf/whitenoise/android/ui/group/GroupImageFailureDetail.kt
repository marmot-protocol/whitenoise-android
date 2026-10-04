package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.ImageUploadPreparationException
import dev.ipf.whitenoise.android.state.AppText

/** Selected-file failures keep existing copy except for the newly supported, restricted SVG format. */
internal fun groupImageFailureDetail(error: Throwable): AppText =
    if (
        generateSequence(error) { it.cause }
            .take(MAX_IMAGE_FAILURE_CAUSES)
            .any { it is ImageUploadPreparationException.UnsupportedSvg }
    ) {
        AppText.Resource(R.string.group_svg_rejected_detail)
    } else {
        AppText.Resource(R.string.error_try_again)
    }

private const val MAX_IMAGE_FAILURE_CAUSES = 16
