package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.ImageUploadPreparationException
import dev.ipf.whitenoise.android.state.AppText
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupImageFailureDetailTest {
    @Test
    fun rejectedSvgHasFormatSpecificCopyEvenWhenUploadStageWrapsIt() {
        assertEquals(
            AppText.Resource(R.string.group_svg_rejected_detail),
            groupImageFailureDetail(
                GroupAvatarUploadFailure(
                    GroupAvatarUploadStage.Prepare,
                    3L,
                    ImageUploadPreparationException.UnsupportedSvg,
                ),
            ),
        )
    }

    @Test
    fun otherFailuresKeepExistingCopy() {
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            groupImageFailureDetail(IllegalStateException("private detail")),
        )
    }
}
