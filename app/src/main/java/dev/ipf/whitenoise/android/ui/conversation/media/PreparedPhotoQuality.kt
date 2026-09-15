package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.state.MediaQuality

internal data class PreparedPhotoQuality(
    val selectedQuality: MediaQuality,
    val standardDimensions: String?,
    val hdDimensions: String?,
)

/** The two tiers a staged photo is actually re-encoded into, regardless of the level the user picked. */
private enum class PhotoSendQualityTier {
    Standard,
    Hd,
}

/** Keep the selected tier truthful when Low or Original bytes are retained unchanged. */
internal fun photoApprovalOutputQuality(
    selectedQuality: MediaQuality,
    optionQuality: MediaQuality,
): MediaQuality =
    if (selectedQuality.sendQualityTier() == optionQuality.sendQualityTier()) {
        selectedQuality
    } else {
        optionQuality
    }

/** Collapses the four offered levels onto the two artifacts the staging pipeline actually prepares. */
internal fun MediaQuality.selectablePhotoQuality(): MediaQuality =
    when (this) {
        MediaQuality.Low,
        MediaQuality.Standard,
        -> MediaQuality.Standard
        MediaQuality.High,
        MediaQuality.Original,
        -> MediaQuality.High
    }

/** Maps the selectable quality to the send tier. */
private fun MediaQuality.sendQualityTier(): PhotoSendQualityTier =
    if (selectablePhotoQuality() == MediaQuality.Standard) {
        PhotoSendQualityTier.Standard
    } else {
        PhotoSendQualityTier.Hd
    }
