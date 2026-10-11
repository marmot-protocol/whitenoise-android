@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.FileUploadProgress
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.fileUploadProgress
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPendingPlaceholder
import dev.ipf.whitenoise.android.ui.conversation.media.PendingFilePill
import dev.ipf.whitenoise.android.ui.conversation.media.StagedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.VideoUploadIndicator
import dev.ipf.whitenoise.android.ui.conversation.media.readStagedDocument
import dev.ipf.whitenoise.android.ui.conversation.media.ringFraction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

private const val MIB = 1024L * 1024L
private const val FILE_BYTES = 8 * MIB

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h1100dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PendingUploadProgressScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    @get:Rule
    val temporary = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = fileFooterAppState(context)
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )

    /** Releases the controller's work after each case. */
    @After
    fun tearDownController() {
        controller.onCleared()
    }

    /** Each upload step names its bytes on a light card, and a failed card drops them for Retry. */
    @Test
    fun uploadStepsLight() = captureSteps("light")

    /** The same steps stay readable in dark mode. */
    @Test
    fun uploadStepsDark() = captureSteps("dark", dark = true)

    /** Large type keeps the step and bytes inside the card on a narrow RTL surface. */
    @Test
    fun uploadStepsLargeRtl() = captureSteps("large-rtl", rtl = true, fontScale = 1.6f)

    /** The same large RTL steps stay readable in dark mode. */
    @Test
    fun uploadStepsDarkLargeRtl() = captureSteps("dark-large-rtl", dark = true, rtl = true, fontScale = 1.6f)

    /**
     * Through the real bubble and controller, a pending single-file send shows the bytes its retained
     * upload reports, and a failed send shows Retry without them.
     */
    @Test
    fun realBubbleFollowsTheRetainedUploadUntilItFails() {
        val content = ByteArray(FILE_BYTES.toInt())
        val read = readStagedDocument(temporary.root, FILE_BYTES) { ByteArrayInputStream(content) }
        val source = (read as StagedDocumentRead.Success).source
        val attachment = PendingAttachment(ByteArray(0), "application/pdf", "report.pdf", sourceFile = source)
        val pending = queueFileBacked(attachment)
        val retained =
            requireNotNull(appState.retainedMediaUploads(appState.activeAccountRef, group().groupIdHex).get(pending.id))
        retained.reportTransferProgress(2 * (FILE_BYTES + 16) + 4 * MIB)
        val bubble = androidx.compose.runtime.mutableStateOf(pending)
        composeRule.setContent { WhiteNoiseTheme { Box(Modifier.fillMaxSize()) { FileMessage(bubble.value) } } }

        composeRule.onNodeWithText("4.0 MB of 8.0 MB").assertExists()
        composeRule.onNodeWithContentDescription("Uploading 4.0 MB of 8.0 MB").assertExists()

        composeRule.runOnIdle {
            retained.clearTransferProgress()
            bubble.value = pending.copy(status = MessageStatus.Failed)
        }
        composeRule.onAllNodesWithText("4.0 MB of 8.0 MB").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Tap to retry").assertExists()
        source.close()
    }

    /** Through the real bubble, a pending single large video's play ring speaks the bytes its upload reports. */
    @Test
    fun realVideoBubbleFollowsTheRetainedUpload() {
        val content = ByteArray(FILE_BYTES.toInt())
        val read = readStagedDocument(temporary.root, FILE_BYTES) { ByteArrayInputStream(content) }
        val source = (read as StagedDocumentRead.Success).source
        val video = PendingAttachment(ByteArray(0), "video/mp4", "clip.mp4", "1280x720", sourceFile = source)
        val pending = queueFileBacked(video)
        val retained =
            requireNotNull(appState.retainedMediaUploads(appState.activeAccountRef, group().groupIdHex).get(pending.id))
        retained.reportTransferProgress(2 * (FILE_BYTES + 16) + 4 * MIB)
        composeRule.setContent { WhiteNoiseTheme { Box(Modifier.fillMaxSize()) { FileMessage(pending) } } }

        composeRule.onNodeWithContentDescription("Uploading 4.0 MB of 8.0 MB").assertExists()
        source.close()
    }

    /** Byte progress describes one file, so two pending documents keep their spinning rings without bytes. */
    @Test
    fun twoPendingDocumentsShowNoBytes() {
        val documents =
            listOf(
                PendingAttachment(ByteArray(4), "application/pdf", "first.pdf"),
                PendingAttachment(ByteArray(4), "application/pdf", "second.pdf"),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaPendingPlaceholder(documents, failed = false, uploadProgress = { progressAt(4 * MIB) })
            }
        }

        composeRule.onAllNodesWithText("Preparing").assertCountEquals(0)
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(2)
    }

    /** Renders the card's steps and the video ring, asserts their text and rings, then captures them. */
    private fun captureSteps(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) { StepGallery() }
            }
        }
        composeRule.onNodeWithText("Preparing").assertExists()
        composeRule.onNodeWithContentDescription("Preparing 2.0 MB of 8.0 MB").assertExists()
        composeRule.onNodeWithText("Encrypting").assertExists()
        composeRule.onNodeWithContentDescription("Encrypting 6.0 MB of 8.0 MB").assertExists()
        composeRule.onNodeWithText("4.0 MB of 8.0 MB").assertExists()
        // The card's control and the video's play disc both speak the same step and bytes.
        composeRule.onAllNodesWithContentDescription("Uploading 4.0 MB of 8.0 MB").assertCountEquals(2)
        composeRule.onNodeWithText("Sending").assertExists()
        composeRule.onAllNodesWithText("Uploading…").assertCountEquals(0)
        composeRule.onNodeWithText("Upload failed").assertExists()
        val uploading = requireNotNull(progressAt(2 * (FILE_BYTES + 16) + 4 * MIB).ringFraction)
        composeRule
            .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(uploading, 0f..1f)))
            .assertCountEquals(2)
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(2)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/pending-upload-progress-$name.png")
    }

    /** One card per upload step, a failed card that had progress, and the video ring with and without bytes. */
    @Composable
    private fun StepGallery() {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp),
        ) {
            listOf(2 * MIB, FILE_BYTES + 6 * MIB, 2 * (FILE_BYTES + 16) + 4 * MIB, 3 * (FILE_BYTES + 16))
                .forEachIndexed { index, processed ->
                    PendingFilePill(
                        fileName = "report-$index.pdf",
                        mediaType = "application/pdf",
                        sizeBytes = FILE_BYTES,
                        failed = false,
                        statusLabel = "Uploading…",
                        uploadProgress = progressAt(processed),
                    )
                }
            PendingFilePill(
                fileName = "failed.pdf",
                mediaType = "application/pdf",
                sizeBytes = FILE_BYTES,
                failed = true,
                statusLabel = "Upload failed",
                onRetry = {},
                uploadProgress = progressAt(4 * MIB),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoDisc(progressAt(2 * (FILE_BYTES + 16) + 4 * MIB))
                VideoDisc(progressAt(3 * (FILE_BYTES + 16)))
            }
        }
    }

    /** The video tile's play disc around [VideoUploadIndicator], as the pending video bubble draws it. */
    @Composable
    private fun VideoDisc(progress: FileUploadProgress) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = CircleShape,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                VideoUploadIndicator { progress }
            }
        }
    }

    /** MDK's counter at [processed] for an 8 MiB file. */
    private fun progressAt(processed: Long): FileUploadProgress {
        val progress = fileUploadProgress(processed, FILE_BYTES)
        return requireNotNull(progress)
    }

    /** Queues [attachment] as an optimistic pending send through the real controller. */
    private fun queueFileBacked(attachment: PendingAttachment): TimelineMessage =
        runBlocking {
            controller.retryMembers()
            val queued = requireNotNull(controller.queueAttachments(attachments = listOf(attachment), caption = null))
            TimelineMessage(
                id = queued.key,
                record = queued.optimistic,
                status = MessageStatus.Pending,
                timelineOrder = queued.optimisticOrder,
            )
        }

    /** Composes the real message bubble for [item]. */
    @Composable
    private fun FileMessage(item: TimelineMessage) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = ComposerTextState(TextFieldValue("")),
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            inviteMutationInFlight = false,
            onJoinInvite = {},
            onDeclineInvite = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderAvatar = false,
            parseMarkdown = ::markdown,
        )
    }
}
