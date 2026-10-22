package dev.ipf.whitenoise.android.ui.group

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.ImageUploadPreparationException
import dev.ipf.whitenoise.android.state.AppText
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupImageFailureDetailTest {
    /** An SVG rejected at preparation keeps its format-specific copy even inside an upload failure. */
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

    /** A refused upload destination explains the restriction, never the photo, with no private detail. */
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

    /** A media-reference error outside the upload stage keeps the generic retry copy. */
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

    /**
     * A descriptor/integrity failure is MDK `BlobStore`, which crosses the FFI as `Runtime` at the pinned
     * MarmotKit, so it keeps generic copy rather than the destination-restriction explanation.
     */
    @Test
    fun uploadIntegrityFailureIsNotReportedAsUnsafeDestination() {
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            groupImageFailureDetail(
                GroupAvatarUploadFailure(
                    GroupAvatarUploadStage.Upload,
                    3L,
                    MarmotKitException.Runtime(
                        "upload descriptor hash mismatch",
                    ),
                ),
            ),
        )
    }

    /** The refusal copy follows the error type alone, so a reworded MDK detail cannot hide it (#2899). */
    @Test
    fun uploadDestinationRefusalDoesNotDependOnDetailWording() {
        listOf("", "reworded destination policy rejection").forEach { detail ->
            assertEquals(
                AppText.Resource(R.string.group_image_destination_refused_detail),
                groupImageFailureDetail(
                    GroupAvatarUploadFailure(
                        GroupAvatarUploadStage.Upload,
                        3L,
                        MarmotKitException.InvalidMediaReference(detail),
                    ),
                ),
            )
        }
    }

    /** Unrelated failures keep the generic retry copy. */
    @Test
    fun otherFailuresKeepExistingCopy() {
        assertEquals(
            AppText.Resource(R.string.error_try_again),
            groupImageFailureDetail(IllegalStateException("private detail")),
        )
    }
}
