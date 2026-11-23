package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.R

internal val DocumentReadFailure.messageResource: Int
    get() =
        when (this) {
            DocumentReadFailure.TOO_LARGE -> R.string.media_file_too_large
            DocumentReadFailure.UNREADABLE -> R.string.media_file_unreadable
            DocumentReadFailure.EMPTY -> R.string.media_file_empty
            DocumentReadFailure.UNPROCESSABLE_IMAGE -> R.string.toast_couldnt_decode_image
            DocumentReadFailure.STORAGE -> R.string.share_import_storage
            DocumentReadFailure.VIDEO_METADATA -> R.string.media_file_video_metadata_not_removable
            DocumentReadFailure.PDF_METADATA -> R.string.media_file_pdf_metadata_kept
        }
