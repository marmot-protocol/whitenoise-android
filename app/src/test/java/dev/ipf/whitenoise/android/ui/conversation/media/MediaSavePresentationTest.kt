package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.NoticeTier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isForConversation
import dev.ipf.whitenoise.android.ui.common.snackbarShowsCopyAffordance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaSavePresentationTest {
    @Test
    fun completeAttachmentSaveUsesConversationConfirmationSurface() {
        val state = appState()

        state.presentAttachmentSaveOutcome(
            context = context(),
            summary = MessageAttachmentSaveSummary(savedCount = 2, totalCount = 2),
            conversation = ConversationNoticeDestination("account-a", "group-a"),
        )

        assertNull(state.toast)
        assertEquals(AppText.Resource(R.string.shared_media_saved), state.transientNotice?.title)
        assertTrue(state.transientNotice?.isForConversation("account-a", "group-a") == true)
    }

    @Test
    fun partialAttachmentSaveRemainsDistinctAndUsesConfirmationSurface() {
        val state = appState()

        state.presentAttachmentSaveOutcome(
            context = context(),
            summary =
                MessageAttachmentSaveSummary(
                    savedCount = 1,
                    totalCount = 2,
                    firstFailure = IOException("private-name.png"),
                ),
            conversation = ConversationNoticeDestination("account-a", "group-a"),
        )

        assertNull(state.toast)
        assertEquals(AppText.Resource(R.string.shared_media_saved), state.transientNotice?.title)
        assertEquals(AppText.Plain("1/2"), state.transientNotice?.detail)
    }

    @Test
    fun failedAttachmentSaveUsesActionableCopyableReport() {
        val state = appState()
        val visibleFailure = context().getString(R.string.shared_media_save_failed)

        state.presentAttachmentSaveOutcome(
            context = context(),
            summary =
                MessageAttachmentSaveSummary(
                    savedCount = 0,
                    totalCount = 1,
                    firstFailure = IOException("content://private/path/private-name.png?token=secret"),
                ),
        )

        val toast = state.toast
        assertNotNull(toast)
        assertNull(state.transientNotice)
        assertEquals(NoticeTier.ActionableError, toast?.tier)
        assertTrue(toast?.copyable == true)
        assertFalse(toast?.diagnosticReport.isNullOrBlank())
        assertTrue(toast?.diagnosticReport?.contains("operation=MESSAGE_ATTACHMENT_SAVE") == true)
        assertFalse(toast?.diagnosticReport.orEmpty().contains(visibleFailure))
        assertFalse(toast?.diagnosticReport.orEmpty().contains("private-name"))
        assertFalse(toast?.diagnosticReport.orEmpty().contains("content://"))
        assertFalse(toast?.diagnosticReport.orEmpty().contains("secret"))
    }

    @Test
    fun genericMediaSaveSuccessUsesGlobalConfirmationSurface() {
        val state = appState()

        state.presentMediaSaveOutcome(
            outcome = Result.success(Unit),
            successTitleRes = R.string.shared_media_saved,
            failureTitleRes = R.string.shared_media_save_failed,
            operationCode = "MEDIA_LIBRARY_SAVE",
        )

        assertNull(state.toast)
        assertEquals(AppText.Resource(R.string.shared_media_saved), state.transientNotice?.title)
        assertNull(state.transientNotice?.conversation)
    }

    @Test
    fun viewerLocalSaveVisualsSplitConfirmationFromActionableError() {
        val success =
            mediaSaveSnackbarVisuals(
                context = context(),
                outcome = Result.success(Unit),
                successTitleRes = R.string.media_saved,
                failureTitleRes = R.string.media_save_failed,
                operationCode = "MEDIA_VIEWER_SAVE",
            )
        val failure =
            mediaSaveSnackbarVisuals(
                context = context(),
                outcome = Result.failure(IOException("avatar.jpg at content://private/path")),
                successTitleRes = R.string.media_saved,
                failureTitleRes = R.string.media_save_failed,
                operationCode = "MEDIA_VIEWER_SAVE",
            )

        assertEquals(SnackbarDuration.Short, success.duration)
        assertFalse(snackbarShowsCopyAffordance(success))
        assertEquals(SnackbarDuration.Indefinite, failure.duration)
        assertTrue(failure.withDismissAction)
        assertTrue(snackbarShowsCopyAffordance(failure))
        assertTrue(failure.copyText?.contains("operation=MEDIA_VIEWER_SAVE") == true)
        assertFalse(failure.copyText.orEmpty().contains("avatar.jpg"))
        assertFalse(failure.copyText.orEmpty().contains("content://"))
        assertFalse(failure.copyText.orEmpty().contains(failure.message))
    }

    @Test
    fun saveJourneysUseSharedPresentationContract() {
        val mediaLibrarySource = source("src/main/java/dev/ipf/whitenoise/android/ui/medialibrary/MediaLibrary.kt")
        val mediaViewerSource = source("src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaViewer.kt")
        val avatarViewerSource = source("src/main/java/dev/ipf/whitenoise/android/ui/profile/AvatarViewer.kt")

        assertTrue(mediaLibrarySource.contains("presentMediaSaveOutcome("))
        assertTrue(mediaLibrarySource.contains("operationCode = \"MEDIA_LIBRARY_FILE_SAVE\""))
        assertTrue(mediaViewerSource.contains("mediaSaveSnackbarVisuals("))
        assertTrue(mediaViewerSource.contains("operationCode = \"MEDIA_VIEWER_SAVE\""))
        assertTrue(avatarViewerSource.contains("mediaSaveSnackbarVisuals("))
        assertTrue(avatarViewerSource.contains("operationCode = \"AVATAR_VIEWER_SAVE\""))

        val combined = mediaLibrarySource + mediaViewerSource + avatarViewerSource
        assertFalse(Regex("copyable\\s*=\\s*!saved").containsMatchIn(combined))
        assertFalse(Regex("showSnackbar\\(if \\(ok\\)").containsMatchIn(combined))
    }

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context(),
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "",
        )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun source(path: String): String =
        sequenceOf(
            java.io.File(path),
            java.io.File("app/$path"),
        ).first(java.io.File::exists).readText()

    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
