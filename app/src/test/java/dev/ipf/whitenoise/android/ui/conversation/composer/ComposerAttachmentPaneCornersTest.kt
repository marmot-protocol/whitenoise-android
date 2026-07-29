package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerAttachmentPaneCornersTest {
    @Test
    fun absentRoundedCornersKeepThePaneSquare() {
        assertEquals(
            ComposerAttachmentPaneBottomCorners(start = 0.dp, end = 0.dp),
            composerAttachmentPaneBottomCorners(
                bottomLeftRadiusPx = 0,
                bottomRightRadiusPx = 0,
                density = 3f,
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
    }

    @Test
    fun equalRoundedCornersConvertFromPixelsToDp() {
        assertEquals(
            ComposerAttachmentPaneBottomCorners(start = 24.dp, end = 24.dp),
            composerAttachmentPaneBottomCorners(
                bottomLeftRadiusPx = 48,
                bottomRightRadiusPx = 48,
                density = 2f,
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
    }

    @Test
    fun asymmetricRoundedCornersFollowPhysicalSidesInLtr() {
        assertEquals(
            ComposerAttachmentPaneBottomCorners(start = 20.dp, end = 12.dp),
            composerAttachmentPaneBottomCorners(
                bottomLeftRadiusPx = 40,
                bottomRightRadiusPx = 24,
                density = 2f,
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
    }

    @Test
    fun asymmetricRoundedCornersMapPhysicalSidesToRtl() {
        assertEquals(
            ComposerAttachmentPaneBottomCorners(start = 12.dp, end = 20.dp),
            composerAttachmentPaneBottomCorners(
                bottomLeftRadiusPx = 40,
                bottomRightRadiusPx = 24,
                density = 2f,
                layoutDirection = LayoutDirection.Rtl,
            ),
        )
    }
}
