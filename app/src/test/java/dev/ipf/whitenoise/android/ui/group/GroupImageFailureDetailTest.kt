package dev.ipf.whitenoise.android.ui.group

import dev.ipf.marmotkit.MarmotKitException
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
    fun uploadDestinationRefusalHasFixedRestrictionCopy() {
        assertEquals(
            AppText.Resource(R.string.group_image_destination_refused_detail),
            groupImageFailureDetail(
                GroupAvatarUploadFailure(
                    GroupAvatarUploadStage.Upload,
                    3L,
                    MarmotKitException.InvalidMediaReference(
                        "unsafe Blossom URL: https://private.invalid/?auth=secret",
                    ),
                ),
            ),
        )
    }

    @Test
    fun mediaReferenceErrorsOutsideUploadKeepExistingCopy() {
        GroupAvatarUploadStage.entries.filter { it != GroupAvatarUploadStage.Upload }.forEach { stage ->
            assertEquals(
                AppText.Resource(R.string.error_try_again),
                groupImageFailureDetail(
                    GroupAvatarUploadFailure(
                        stage,
                        3L,
                        MarmotKitException.InvalidMediaReference(
                            "private detail",
                        ),
                    ),
                ),
            )
        }
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            groupImageFailureDetail(
                MarmotKitException.InvalidMediaReference(
                    "private detail",
                ),
            ),
        )
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            groupImageFailureDetail(
                GroupAvatarUploadFailure(GroupAvatarUploadStage.Upload, 3L, IllegalStateException()),
            ),
        )
    }

    @Test
    fun uploadIntegrityFailureIsNotReportedAsUnsafeDestination() {
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            groupImageFailureDetail(
                GroupAvatarUploadFailure(
                    GroupAvatarUploadStage.Upload,
                    3L,
                    MarmotKitException.InvalidMediaReference(
                        "upload descriptor hash mismatch",
                    ),
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
