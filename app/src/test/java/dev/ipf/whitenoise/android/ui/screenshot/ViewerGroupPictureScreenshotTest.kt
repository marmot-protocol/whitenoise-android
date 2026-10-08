package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Viewer group-picture entry, progress and local-failure baselines. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ViewerGroupPictureScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Group admins can discover the explicit crop entry; pending work disables duplicate submission. */
    @Test
    fun groupPictureMenuLargeRtl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var choices = 0
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true, fontScale = 2f) {
                    MediaViewerFrame(
                        senderLabel = "Alex",
                        recordedAtLabel = "Oct 7, 2026",
                        onDismiss = {},
                        onSave = {},
                        onShare = {},
                        snackbarHostState = remember { SnackbarHostState() },
                        onSetGroupPicture = { choices++ },
                    ) {}
                }
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.media_set_group_picture))
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule
            .onNode(isPopup())
            .captureRoboImage("src/test/snapshots/media_viewer_group_picture_menu_dark_large_rtl.png")
        composeRule.onNodeWithText(context.getString(R.string.media_set_group_picture)).performClick()
        assertEquals(1, choices)
    }

    /** A busy mutation remains visible while its menu action cannot be submitted twice. */
    @Test
    fun groupPictureProgressLight() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                MediaViewerFrame(
                    senderLabel = "Alex",
                    recordedAtLabel = "Oct 7, 2026",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    onSetGroupPicture = { error("duplicate submission") },
                    groupPictureBusy = true,
                ) {}
            }
        }
        composeRule.onNodeWithTag("conversation.media.viewer.group-picture-progress").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_group_picture_progress_light.png")
        composeRule.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.media_set_group_picture)).assertIsNotEnabled()
    }

    /** Source preparation failures remain readable in the fullscreen viewer rather than the covered Activity. */
    @Test
    fun groupPictureFailureInViewerWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val presentation =
            dev.ipf.whitenoise.android.state.privacySafeErrorPresentation(
                "VIEWER_GROUP_IMAGE",
                dev.ipf.whitenoise.android.media.ImageUploadPreparationException.UnsupportedSvg,
                dev.ipf.whitenoise.android.ui.group.groupImageFailureDetail(
                    dev.ipf.whitenoise.android.media.ImageUploadPreparationException.UnsupportedSvg,
                ),
            )
        val notice =
            dev.ipf.whitenoise.android.state.ToastMessage(
                dev.ipf.whitenoise.android.state.AppText
                    .Resource(R.string.group_photo_error),
                presentation.message,
                copyable = true,
                diagnosticReport = presentation.report,
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                val host = remember { SnackbarHostState() }
                dev.ipf.whitenoise.android.ui.conversation.media
                    .ViewerGroupPictureFailureNotice(notice, host)
                MediaViewerFrame(
                    senderLabel = "Alex",
                    recordedAtLabel = "Oct 7, 2026",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    snackbarHostState = host,
                ) {}
            }
        }
        composeRule
            .onNodeWithText(context.getString(R.string.group_svg_rejected_detail), substring = true)
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_group_picture_failure_dark.png")
    }
}
