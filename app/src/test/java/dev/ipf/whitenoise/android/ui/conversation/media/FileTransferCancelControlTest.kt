package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the per-file cancel affordance from
 * [#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045):
 * a queued or downloading generic file exposes a labelled cancel target of at
 * least 48 dp, and every other state keeps inert chrome so the card stays the
 * single open/download target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h960dp-xhdpi")
class FileTransferCancelControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun aQueuedTransferExposesAnAccessibleCancelTarget() {
        assertCancellable(AttachmentTransferState.Resolving, R.string.media_preparing_download)
    }

    @Test
    fun aRunningTransferExposesAnAccessibleCancelTarget() {
        assertCancellable(AttachmentTransferState.Downloading, R.string.media_downloading)
    }

    @Test
    fun aTapToOpenDownloadStaysCancellableWhileItReportsOpening() {
        var cancels = 0
        setControl(
            AttachmentTransferState.Downloading,
            openPending = true,
            onCancelTransfer = { cancels += 1 },
        )

        val control = composeRule.onNodeWithContentDescription(resources.getString(R.string.media_opening))
        control.assertHasClickAction()
        control.assert(hasCancelClickLabel())
        control.performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun aLaunchingViewerIsNotCancellable() {
        setControl(AttachmentTransferState.Available, openPending = true, onCancelTransfer = {})

        composeRule
            .onNodeWithContentDescription(resources.getString(R.string.media_opening))
            .assertHasNoClickAction()
    }

    @Test
    fun aCancelledTransferReadsAsDownloadAgainWithoutItsOwnClickTarget() {
        setControl(AttachmentTransferState.Cancelled, onCancelTransfer = {})

        composeRule
            .onNodeWithContentDescription(resources.getString(R.string.media_download_cancelled))
            .assertHasNoClickAction()
    }

    @Test
    fun settledStatesKeepInertChrome() {
        val state = mutableStateOf(AttachmentTransferState.Remote)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                FileTransferControl(
                    presentation = resolveAttachmentPresentation("application/pdf", "notes.pdf"),
                    transferState = state.value,
                    onCancelTransfer = {},
                )
            }
        }

        listOf(
            AttachmentTransferState.Remote to R.string.media_tap_to_download,
            AttachmentTransferState.NotRetained to R.string.media_tap_to_download,
            AttachmentTransferState.Failed to R.string.media_tap_to_retry,
            AttachmentTransferState.Available to R.string.attachment_type_pdf_description,
        ).forEach { (settled, description) ->
            composeRule.runOnUiThread { state.value = settled }
            composeRule.waitForIdle()

            composeRule
                .onNodeWithContentDescription(resources.getString(description))
                .assertHasNoClickAction()
        }
    }

    @Test
    fun uploadChromeReusingTheControlStaysInert() {
        setControl(
            AttachmentTransferState.Downloading,
            direction = FileTransferDirection.Upload,
            onCancelTransfer = {},
        )

        composeRule
            .onNodeWithContentDescription(resources.getString(R.string.media_downloading))
            .assertHasNoClickAction()
    }

    @Test
    fun aReuseSiteWithoutACancelHandlerStaysInert() {
        setControl(AttachmentTransferState.Downloading, onCancelTransfer = null)

        composeRule
            .onNodeWithContentDescription(resources.getString(R.string.media_downloading))
            .assertHasNoClickAction()
    }

    private fun assertCancellable(
        state: AttachmentTransferState,
        descriptionRes: Int,
    ) {
        var cancels = 0
        setControl(state, onCancelTransfer = { cancels += 1 })

        val control = composeRule.onNodeWithContentDescription(resources.getString(descriptionRes))
        control.assertHasClickAction()
        control.assertWidthIsAtLeast(MINIMUM_TOUCH_TARGET)
        control.assertHeightIsAtLeast(MINIMUM_TOUCH_TARGET)
        control.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        control.assert(hasCancelClickLabel())

        control.performClick()

        assertEquals("$state must cancel exactly once per tap", 1, cancels)
    }

    private fun setControl(
        state: AttachmentTransferState,
        direction: FileTransferDirection = FileTransferDirection.Download,
        openPending: Boolean = false,
        onCancelTransfer: (() -> Unit)?,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = false) {
                FileTransferControl(
                    presentation = resolveAttachmentPresentation("application/pdf", "notes.pdf"),
                    transferState = state,
                    direction = direction,
                    openPending = openPending,
                    onCancelTransfer = onCancelTransfer,
                )
            }
        }
    }

    private fun hasCancelClickLabel(): SemanticsMatcher =
        SemanticsMatcher("click action is labelled for cancelling the download") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label ==
                resources.getString(R.string.media_cancel_download)
        }

    private companion object {
        val MINIMUM_TOUCH_TARGET = 48.dp
    }
}
