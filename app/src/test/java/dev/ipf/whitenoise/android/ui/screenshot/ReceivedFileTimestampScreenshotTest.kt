@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubbleContent
import dev.ipf.whitenoise.android.ui.conversation.media.PendingFilePill
import dev.ipf.whitenoise.android.ui.conversation.media.fileBubbleWidth
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageInlineFooter
import dev.ipf.whitenoise.android.ui.conversation.messages.RetentionIndicatorInput
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ReceivedFileTimestampScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sendingAndReceivedFileCardsKeepTheSameFootprint() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    Box(Modifier.testTag(SENDING_CARD_TAG)) {
                        PendingFilePill(
                            fileName = "release-notes.pdf",
                            mediaType = "application/pdf",
                            sizeBytes = 48_213L,
                            failed = false,
                            statusLabel = "Uploading",
                            timestampText = SINGLE_TIME,
                            showStatus = true,
                            status = MessageStatus.Pending,
                            reserveRetentionSpace = true,
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fileBubbleWidth().testTag(RECEIVED_CARD_TAG),
                    ) {
                        val file = reference("release-notes.pdf", "application/pdf")
                        MediaFileBubbleContent(
                            reference = file,
                            presentation = resolveAttachmentPresentation(file.mediaType, file.fileName),
                            transferState = AttachmentTransferState.Remote,
                            timestampText = SINGLE_TIME,
                            showStatus = true,
                            status = MessageStatus.Sent,
                            retention = retentionInput("confirmed-file"),
                            retentionClockMillis = { RETENTION_NOW_MILLIS },
                        )
                    }
                }
            }
        }

        val sendingBounds = composeRule.onNodeWithTag(SENDING_CARD_TAG).getUnclippedBoundsInRoot()
        val receivedBounds = composeRule.onNodeWithTag(RECEIVED_CARD_TAG).getUnclippedBoundsInRoot()
        assertEquals(sendingBounds.right - sendingBounds.left, receivedBounds.right - receivedBounds.left)
        assertEquals(sendingBounds.bottom - sendingBounds.top, receivedBounds.bottom - receivedBounds.top)
        composeRule
            .onAllNodesWithContentDescription("Uploading", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule
            .onAllNodesWithContentDescription("Sending", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule
            .onAllNodesWithContentDescription("Tap to download", useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun receivedFileTimestampGallery() {
        composeRule.setContent {
            ReceivedFileTimestampGallery()
        }

        composeRule.onAllNodesWithText(SINGLE_TIME).assertCountEquals(1)
        composeRule.onAllNodesWithText(SENT_TIME).assertCountEquals(1)
        composeRule.onAllNodesWithText(CAPTION_TIME).assertCountEquals(1)
        composeRule.onAllNodesWithText(GROUP_TIME).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Tap to download").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Preparing download").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Downloading").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Tap to retry").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sent").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Disappearing message").assertCountEquals(1)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/received_file_timestamp_light.png")
    }

    @Composable
    private fun ReceivedFileTimestampGallery() {
        WhiteNoiseTheme {
            Surface {
                Column(
                    modifier = Modifier.width(360.dp).padding(16.dp).testTag(TAG),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("File", style = MaterialTheme.typography.labelMedium)
                    FileCard(
                        reference = reference("release-notes.pdf", "application/pdf"),
                        transferState = AttachmentTransferState.Remote,
                        timestampText = SINGLE_TIME,
                    )
                    Text("Sent file", style = MaterialTheme.typography.labelMedium)
                    FileCard(
                        reference = reference("design-spec.pdf", "application/pdf"),
                        transferState = AttachmentTransferState.Available,
                        timestampText = SENT_TIME,
                        showStatus = true,
                        status = MessageStatus.Sent,
                        retention = retentionInput("sent-file"),
                    )
                    Text("File with caption", style = MaterialTheme.typography.labelMedium)
                    CaptionedFileCard()
                    Text("File group", style = MaterialTheme.typography.labelMedium)
                    FileGroup()
                }
            }
        }
    }

    @Composable
    private fun CaptionedFileCard() {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fileBubbleWidth(),
        ) {
            Column {
                MediaFileBubbleContent(
                    reference = reference("roadmap.xlsx", SPREADSHEET_MIME),
                    presentation = resolveAttachmentPresentation(SPREADSHEET_MIME, "roadmap.xlsx"),
                    transferState = AttachmentTransferState.Downloading,
                    timestampText = CAPTION_TIME,
                )
                Text(
                    text = "Updated milestones",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    MessageInlineFooter(
                        timeText = CAPTION_TIME,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        showStatus = false,
                        status = MessageStatus.Received,
                        editedLabel = "edited",
                        onEditedClick = null,
                        showTime = false,
                    )
                }
            }
        }
    }

    @Composable
    private fun FileGroup() {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FileCard(
                reference = reference("resolving-manifest.json", "application/json"),
                transferState = AttachmentTransferState.Resolving,
                timestampText = null,
            )
            FileCard(
                reference = reference("expired-cache.bin", "application/octet-stream"),
                transferState = AttachmentTransferState.NotRetained,
                timestampText = null,
            )
            FileCard(
                reference = reference("failed-export.zip", "application/zip"),
                transferState = AttachmentTransferState.Failed,
                timestampText = null,
            )
            FileCard(
                reference = reference("available-notes.txt", "text/plain"),
                transferState = AttachmentTransferState.Available,
                timestampText = GROUP_TIME,
            )
        }
    }

    @Test
    fun receivedFileTimestampLargeRtl() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.6f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface(modifier = Modifier.padding(16.dp).testTag(RTL_TAG)) {
                        FileCard(
                            reference =
                                reference(
                                    "quarterly-roadmap-with-a-very-long-filename.pdf",
                                    "application/pdf",
                                ),
                            transferState = AttachmentTransferState.Remote,
                            timestampText = RTL_TIME,
                        )
                    }
                }
            }
        }

        composeRule.onAllNodesWithText(RTL_TIME).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Tap to download").assertCountEquals(1)
        composeRule
            .onNodeWithTag(RTL_TAG)
            .captureRoboImage("src/test/snapshots/received_file_timestamp_dark_large_rtl.png")
    }

    @Test
    fun sentFileTimestampLargeRtl() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.6f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    Surface(modifier = Modifier.padding(16.dp).testTag(SENT_RTL_TAG)) {
                        FileCard(
                            reference =
                                reference(
                                    "quarterly-roadmap-with-a-very-long-filename.pdf",
                                    "application/pdf",
                                ),
                            transferState = AttachmentTransferState.Available,
                            timestampText = SENT_RTL_TIME,
                            showStatus = true,
                            status = MessageStatus.Sent,
                            retention = retentionInput("sent-file-rtl"),
                        )
                    }
                }
            }
        }

        composeRule.onAllNodesWithText(SENT_RTL_TIME).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sent").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Disappearing message").assertCountEquals(1)
        composeRule
            .onNodeWithTag(SENT_RTL_TAG)
            .captureRoboImage("src/test/snapshots/sent_file_timestamp_dark_large_rtl.png")
    }

    @Test
    fun receivedApkOpeningShowsVisiblePendingState() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.padding(16.dp).testTag(APK_OPENING_TAG)) {
                    FileCard(
                        reference = reference("whitenoise-pr.apk", "application/vnd.android.package-archive"),
                        transferState = AttachmentTransferState.Available,
                        timestampText = APK_OPENING_TIME,
                        openPending = true,
                    )
                }
            }
        }

        composeRule.onAllNodesWithContentDescription("Opening").assertCountEquals(1)
        composeRule
            .onNodeWithTag(APK_OPENING_TAG)
            .captureRoboImage("src/test/snapshots/received_apk_opening_light.png")
    }

    /** Captures unconfirmed generic, text, and APK footers in the default light theme. */
    @Test
    fun unconfirmedFileFooterLightGallery() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(
                        modifier = Modifier.width(360.dp).padding(16.dp).testTag(UNCONFIRMED_LIGHT_TAG),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FileCard(
                            reference = reference("release-notes.pdf", "application/pdf"),
                            transferState = AttachmentTransferState.Available,
                            timestampText = UNCONFIRMED_GENERIC_TIME,
                            footerWarningText = DELIVERY_NOT_CONFIRMED,
                        )
                        FileCard(
                            reference = reference("incident-notes.txt", "text/plain"),
                            transferState = AttachmentTransferState.Available,
                            timestampText = UNCONFIRMED_TEXT_TIME,
                            footerWarningText = DELIVERY_NOT_CONFIRMED,
                        )
                        FileCard(
                            reference = reference("white-noise-dev.apk", ANDROID_PACKAGE_MIME),
                            transferState = AttachmentTransferState.Available,
                            timestampText = UNCONFIRMED_APK_TIME,
                            footerWarningText = DELIVERY_NOT_CONFIRMED,
                        )
                    }
                }
            }
        }

        composeRule.onAllNodesWithText(DELIVERY_NOT_CONFIRMED, useUnmergedTree = true).assertCountEquals(3)
        composeRule.onAllNodesWithText(UNCONFIRMED_GENERIC_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(UNCONFIRMED_TEXT_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(UNCONFIRMED_APK_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule
            .onNodeWithTag(UNCONFIRMED_LIGHT_TAG)
            .captureRoboImage("src/test/snapshots/file_message_footer_unconfirmed_light.png")
    }

    /** Keeps an unconfirmed warning and timestamp inside a narrow card at large RTL text. */
    @Test
    fun unconfirmedFileFooterDarkLargeRtlStaysInsideNarrowCard() {
        captureUnconfirmedFileFooter(
            tag = UNCONFIRMED_DARK_LARGE_RTL_TAG,
            snapshotPath = "src/test/snapshots/file_message_footer_unconfirmed_dark_large_rtl.png",
            darkTheme = true,
            amoled = false,
            layoutDirection = LayoutDirection.Rtl,
            fontScale = 1.6f,
            width = 280,
            fileName = "quarterly-roadmap-with-a-very-long-filename.pdf",
            timestamp = UNCONFIRMED_RTL_TIME,
        )
    }

    /** Confirms the warning/footer hierarchy remains legible against the AMOLED surface. */
    @Test
    fun unconfirmedFileFooterAmoledKeepsReadableTrailingMetadata() {
        captureUnconfirmedFileFooter(
            tag = UNCONFIRMED_AMOLED_TAG,
            snapshotPath = "src/test/snapshots/file_message_footer_unconfirmed_amoled.png",
            darkTheme = true,
            amoled = true,
            layoutDirection = LayoutDirection.Ltr,
            fontScale = 1f,
            width = 360,
            fileName = "white-noise-release.apk",
            mediaType = ANDROID_PACKAGE_MIME,
            timestamp = UNCONFIRMED_AMOLED_TIME,
        )
    }

    /** Captures one parameterized unconfirmed card after proving warning and time containment. */
    private fun captureUnconfirmedFileFooter(
        tag: String,
        snapshotPath: String,
        darkTheme: Boolean,
        amoled: Boolean,
        layoutDirection: LayoutDirection,
        fontScale: Float,
        width: Int,
        fileName: String,
        timestamp: String,
        mediaType: String = "application/pdf",
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.width(width.dp).padding(16.dp).testTag(tag)) {
                        FileCard(
                            reference = reference(fileName, mediaType),
                            transferState = AttachmentTransferState.Available,
                            timestampText = timestamp,
                            footerWarningText = DELIVERY_NOT_CONFIRMED,
                            cardTag = "$tag-card",
                        )
                    }
                }
            }
        }

        val cardBounds = composeRule.onNodeWithTag("$tag-card").getUnclippedBoundsInRoot()
        val warningBounds =
            composeRule.onNodeWithText(DELIVERY_NOT_CONFIRMED, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val timestampBounds = composeRule.onNodeWithText(timestamp, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(warningBounds.left >= cardBounds.left)
        assertTrue(warningBounds.top >= cardBounds.top)
        assertTrue(warningBounds.right <= cardBounds.right)
        assertTrue(warningBounds.bottom <= cardBounds.bottom)
        assertTrue(timestampBounds.left >= cardBounds.left)
        assertTrue(timestampBounds.top >= cardBounds.top)
        assertTrue(timestampBounds.right <= cardBounds.right)
        assertTrue(timestampBounds.bottom <= cardBounds.bottom)
        assertTrue(warningBounds.bottom <= timestampBounds.top)
        composeRule.onNodeWithTag(tag).captureRoboImage(snapshotPath)
    }

    /** Hosts the shared file-card renderer with optional footer-warning and geometry tags. */
    @Composable
    private fun FileCard(
        reference: MediaAttachmentReferenceFfi,
        transferState: AttachmentTransferState,
        timestampText: String?,
        showStatus: Boolean = false,
        status: MessageStatus = MessageStatus.Received,
        retention: RetentionIndicatorInput? = null,
        openPending: Boolean = false,
        footerWarningText: String? = null,
        cardTag: String? = null,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier =
                Modifier
                    .fileBubbleWidth()
                    .then(if (cardTag != null) Modifier.testTag(cardTag) else Modifier),
        ) {
            MediaFileBubbleContent(
                reference = reference,
                presentation = resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                transferState = transferState,
                timestampText = timestampText,
                showStatus = showStatus,
                status = status,
                retention = retention,
                footerWarningText = footerWarningText,
                retentionClockMillis = { RETENTION_NOW_MILLIS },
                openPending = openPending,
            )
        }
    }

    private fun reference(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "",
        plaintextSha256 = "",
        nonceHex = "",
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 7uL,
        dim = null,
        thumbhash = null,
    )

    private fun retentionInput(messageIdHex: String) =
        RetentionIndicatorInput(
            controllerKey = screenshotControllerKey,
            accountRef = "personal",
            groupIdHex = "group",
            messageIdHex = messageIdHex,
            sourceEpoch = 7uL,
            durationSeconds = 100uL,
            expiresAtEpochSeconds = 200uL,
        )

    private companion object {
        const val TAG = "received-file-timestamp-gallery"
        const val SINGLE_TIME = "10:01 AM"
        const val SENT_TIME = "10:02 AM"
        const val CAPTION_TIME = "10:03 AM"
        const val GROUP_TIME = "10:04 AM"
        const val RTL_TAG = "received-file-timestamp-rtl"
        const val RTL_TIME = "10:05 AM"
        const val SENT_RTL_TAG = "sent-file-timestamp-rtl"
        const val SENT_RTL_TIME = "10:06 AM"
        const val APK_OPENING_TAG = "received-apk-opening"
        const val APK_OPENING_TIME = "10:07 AM"
        const val DELIVERY_NOT_CONFIRMED = "Delivery not confirmed"
        const val UNCONFIRMED_GENERIC_TIME = "10:08 AM"
        const val UNCONFIRMED_TEXT_TIME = "10:09 AM"
        const val UNCONFIRMED_APK_TIME = "10:10 AM"
        const val UNCONFIRMED_RTL_TIME = "10:11 AM"
        const val UNCONFIRMED_AMOLED_TIME = "10:12 AM"
        const val UNCONFIRMED_LIGHT_TAG = "unconfirmed-file-footer-light"
        const val UNCONFIRMED_DARK_LARGE_RTL_TAG = "unconfirmed-file-footer-dark-large-rtl"
        const val UNCONFIRMED_AMOLED_TAG = "unconfirmed-file-footer-amoled"
        const val ANDROID_PACKAGE_MIME = "application/vnd.android.package-archive"
        const val SPREADSHEET_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val SENDING_CARD_TAG = "sending-file-card"
        const val RECEIVED_CARD_TAG = "received-file-card"
        const val RETENTION_NOW_MILLIS = 150_000L
        val screenshotControllerKey = Any()
    }
}
