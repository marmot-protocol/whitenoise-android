@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.media.ANDROID_PACKAGE_MIME
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubbleContent
import dev.ipf.whitenoise.android.ui.conversation.media.fileAttachmentCardTestTag
import dev.ipf.whitenoise.android.ui.conversation.media.fileAttachmentFirstFrameVisibility
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1100dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleFileAttachmentScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = fileFooterAppState(context)
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val composerTextState = ComposerTextState(TextFieldValue(""))
    private var originalTimeFormat: String? = null

    /** Pins the 12-hour and UTC clock inputs; each Robolectric config supplies the fixture locale. */
    @Before
    fun setDeterministicClockPreferences() {
        originalTimeFormat =
            Settings.System.getString(
                context.contentResolver,
                Settings.System.TIME_12_24,
            )
        Settings.System.putString(
            context.contentResolver,
            Settings.System.TIME_12_24,
            "12",
        )
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Releases controller work and restores every process-level clock preference changed by setup. */
    @After
    fun tearDownFixture() {
        try {
            controller.onCleared()
        } finally {
            try {
                Settings.System.putString(
                    context.contentResolver,
                    Settings.System.TIME_12_24,
                    originalTimeFormat,
                )
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }
    }

    /** Captures real message bubbles across incoming, outgoing, captioned, pending, and selected layouts. */
    @Test
    fun realMessageBubblePathKeepsFileCardsReadableAcrossParentVariants() {
        val gallery = fileGallery()
        composeRule.setContent {
            WhiteNoiseTheme {
                FileGallery(gallery)
            }
        }
        composeRule.runOnIdle {
            appState.cacheMediaPlaintext(
                mediaCacheKey(
                    ACCOUNT_REF,
                    GROUP_ID,
                    gallery.downloadedApk.record.messageIdHex,
                    0,
                ),
                byteArrayOf(1, 2, 3),
            )
        }
        assertFileGalleryStates(gallery)
        // Capture the fixed-size root rather than a cropped semantics node. Cropped native-graphics
        // capture can fail in Skia's PNG stream encoder on Linux even after all layout assertions pass.
        composeRule.onRoot().captureRoboImage(SNAPSHOT_PATH)
    }

    /** Proves the received-file card stays hidden until first-frame cache resolution completes. */
    @Test
    @Config(sdk = [36], qualifiers = "en-rUS-w360dp-h112dp-mdpi")
    fun receivedFileCardHasDeterministicUnresolvedAndResolvedFirstFrames() {
        var resolved by mutableStateOf(false)
        val reference = fileReference("cached-release.pdf", "application/pdf")
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .fileAttachmentFirstFrameVisibility(resolved)
                                .testTag(FIRST_FRAME_CARD_TAG),
                    ) {
                        MediaFileBubbleContent(
                            reference = reference,
                            presentation = resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                            transferState = AttachmentTransferState.Remote,
                        )
                    }
                }
            }
        }

        composeRule.onRoot().captureRoboImage(FIRST_FRAME_UNRESOLVED_SNAPSHOT_PATH)
        composeRule.runOnIdle { resolved = true }
        composeRule.onNodeWithText("cached-release.pdf").assertExists()
        composeRule.onRoot().captureRoboImage(FIRST_FRAME_RESOLVED_SNAPSHOT_PATH)
    }

    /** Keeps an unconfirmed warning and timestamp inside the last file card under dense RTL layout. */
    @Test
    @Config(sdk = [36], qualifiers = "en-rUS-w320dp-h360dp-mdpi")
    fun localPublishFailureKeepsWarningAndTimestampInsideTheLastFileCardAtLargeRtl() {
        val item =
            fileTimelineMessage(
                index = 12,
                fileName = "unconfirmed-release-notes.pdf",
                fileNames = listOf("unconfirmed-cover-letter.txt", "unconfirmed-release-notes.pdf"),
                mine = true,
                invalidationStatus = "local_publish_failed",
            )
        cacheCanonicalOwnFiles(item)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 1.6f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(vertical = 12.dp),
                    ) {
                        FileMessage(item)
                    }
                }
            }
        }

        composeRule.onAllNodesWithText(DELIVERY_NOT_CONFIRMED, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(ONE_AM_TIMESTAMP, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sending", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Tap to retry", useUnmergedTree = true).assertCountEquals(0)

        val cardBounds =
            composeRule
                .onNodeWithTag(fileAttachmentCardTestTag(item.record.messageIdHex, 1), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val warningBounds =
            composeRule
                .onNodeWithText(DELIVERY_NOT_CONFIRMED, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val timestampBounds =
            composeRule
                .onNodeWithText(ONE_AM_TIMESTAMP, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()

        assertTrue(warningBounds.left >= cardBounds.left)
        assertTrue(warningBounds.top >= cardBounds.top)
        assertTrue(warningBounds.right <= cardBounds.right)
        assertTrue(warningBounds.bottom <= cardBounds.bottom)
        assertTrue(timestampBounds.left >= cardBounds.left)
        assertTrue(timestampBounds.top >= cardBounds.top)
        assertTrue(timestampBounds.right <= cardBounds.right)
        assertTrue(timestampBounds.bottom <= cardBounds.bottom)
        assertTrue(warningBounds.bottom <= timestampBounds.top)
        composeRule.onRoot().captureRoboImage(UNCONFIRMED_FILE_SNAPSHOT_PATH)
    }

    /** Covers the real MessageBubble footer contract across captioned, mixed, and failed files. */
    @Test
    fun realMessageBubbleFooterMatrixKeepsMetadataOnOneAccessibleFileCard() {
        val fixtures = footerMatrixFixtures()

        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.width(360.dp)) {
                    FileMessage(fixtures.captionedUnconfirmed)
                    FileMessage(fixtures.mixedDelivered)
                    FileMessage(fixtures.confirmedFailed)
                }
            }
        }

        assertFooterMatrix(fixtures)
    }

    /** Builds the three production rows whose footer owners differ by message shape and state. */
    private fun footerMatrixFixtures(): FooterMatrixFixtures =
        FooterMatrixFixtures(
            captionedUnconfirmed =
                fileTimelineMessage(
                    index = 60,
                    fileName = CAPTIONED_UNCONFIRMED_FILE,
                    mine = true,
                    caption = CAPTIONED_UNCONFIRMED_TEXT,
                    invalidationStatus = "local_publish_failed",
                    retentionSeconds = 60uL,
                    retentionExpiresAt = FAR_FUTURE_EPOCH_SECONDS,
                ),
            mixedDelivered =
                fileTimelineMessage(
                    index = 120,
                    fileName = MIXED_FILE,
                    mine = true,
                    attachments =
                        listOf(
                            MIXED_IMAGE to "image/jpeg",
                            MIXED_FILE to "application/pdf",
                        ),
                ),
            confirmedFailed =
                fileTimelineMessage(
                    index = 180,
                    fileName = CONFIRMED_FAILED_FILE,
                    mine = true,
                    status = MessageStatus.Failed,
                ),
        )

    /** Verifies each production row exposes one accessible footer within its owning file card. */
    private fun assertFooterMatrix(fixtures: FooterMatrixFixtures) {
        composeRule.onAllNodesWithText(DELIVERY_NOT_CONFIRMED, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(CAPTIONED_UNCONFIRMED_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(MIXED_DELIVERED_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(CONFIRMED_FAILED_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule
            .onAllNodesWithContentDescription("Disappearing message", useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sending", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Sent", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Send failed", useUnmergedTree = true).assertCountEquals(1)

        val captionedCardTag = fileAttachmentCardTestTag(fixtures.captionedUnconfirmed.record.messageIdHex, 0)
        val mixedCardTag = fileAttachmentCardTestTag(fixtures.mixedDelivered.record.messageIdHex, 1)
        val failedCardTag = fileAttachmentCardTestTag(fixtures.confirmedFailed.record.messageIdHex, 0)
        assertAccessibleFileCardText(
            cardTag = captionedCardTag,
            CAPTIONED_UNCONFIRMED_FILE,
            DELIVERY_NOT_CONFIRMED,
            CAPTIONED_UNCONFIRMED_TIME,
        )
        assertAccessibleFileCardDescriptions(captionedCardTag, "Disappearing message")
        assertNodeInsideCard(captionedCardTag, text = DELIVERY_NOT_CONFIRMED)
        assertNodeInsideCard(captionedCardTag, text = CAPTIONED_UNCONFIRMED_TIME)
        assertDescriptionInsideCard(captionedCardTag, description = "Disappearing message")
        assertAccessibleFileCardText(mixedCardTag, MIXED_FILE, MIXED_DELIVERED_TIME)
        assertAccessibleFileCardDescriptions(mixedCardTag, "Sent")
        assertNodeInsideCard(mixedCardTag, text = MIXED_DELIVERED_TIME)
        assertDescriptionInsideCard(mixedCardTag, description = "Sent")
        assertAccessibleFileCardText(failedCardTag, CONFIRMED_FAILED_FILE, CONFIRMED_FAILED_TIME)
        assertAccessibleFileCardDescriptions(failedCardTag, "Send failed")
        assertNodeInsideCard(failedCardTag, text = CONFIRMED_FAILED_TIME)
        assertDescriptionInsideCard(failedCardTag, description = "Send failed")

        val warningBounds =
            composeRule.onNodeWithText(DELIVERY_NOT_CONFIRMED, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val timestampBounds =
            composeRule.onNodeWithText(CAPTIONED_UNCONFIRMED_TIME, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(warningBounds.bottom <= timestampBounds.top)
    }

    private data class FooterMatrixFixtures(
        val captionedUnconfirmed: TimelineMessage,
        val mixedDelivered: TimelineMessage,
        val confirmedFailed: TimelineMessage,
    )

    /** Replaces one optimistic file row with its canonical projection while retaining display order. */
    @Test
    @Config(sdk = [36], qualifiers = "en-rUS-w360dp-h180dp-mdpi")
    fun optimisticFileKeepsOneFooterOwnerAcrossPendingFailedAndConfirmedRenderers() {
        val optimistic = controller.pendingFileTimelineMessage(index = 12, fileName = PENDING_FAILED_FILE)
        val confirmed =
            fileTimelineMessage(
                index = 12,
                fileName = PENDING_FAILED_FILE,
                mine = true,
            ).copy(
                timelineOrder = optimistic.timelineOrder,
            )
        assertTrue("the protocol projection replaces the temporary upload id", confirmed.id != optimistic.id)
        assertEquals(optimistic.timelineOrder, confirmed.timelineOrder)
        var presentedItem by mutableStateOf(optimistic)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 12.dp),
                ) {
                    FileMessage(presentedItem)
                }
            }
        }

        composeRule.onAllNodesWithText(ONE_AM_TIMESTAMP, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sending", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Uploading…").assertExists()

        composeRule.runOnIdle { presentedItem = optimistic.copy(status = MessageStatus.Failed) }
        composeRule.onNodeWithText(PENDING_FAILED_FILE).assertExists()
        composeRule.onNodeWithText("Upload failed").assertExists()
        composeRule.onNodeWithContentDescription("Tap to retry").assertExists()
        composeRule.onAllNodesWithText(ONE_AM_TIMESTAMP, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sending", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Send failed", useUnmergedTree = true).assertCountEquals(1)
        val timestampBounds =
            composeRule.onNodeWithText(ONE_AM_TIMESTAMP, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val retryBounds =
            composeRule.onNodeWithContentDescription("Retry", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(timestampBounds.bottom <= retryBounds.top)
        composeRule.onRoot().captureRoboImage(PENDING_FAILED_FILE_SNAPSHOT_PATH)

        cacheCanonicalOwnFiles(confirmed)
        composeRule.runOnIdle { presentedItem = confirmed }
        val confirmedCardTag = fileAttachmentCardTestTag(confirmed.record.messageIdHex, 0)
        composeRule.onNodeWithTag(confirmedCardTag, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Upload failed").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Tap to retry").assertDoesNotExist()
        composeRule.onAllNodesWithText(ONE_AM_TIMESTAMP, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sending", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Send failed", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Sent", useUnmergedTree = true).assertCountEquals(1)
        assertAccessibleFileCardText(confirmedCardTag, PENDING_FAILED_FILE, ONE_AM_TIMESTAMP)
        assertAccessibleFileCardDescriptions(confirmedCardTag, "Sent")
        assertNodeInsideCard(confirmedCardTag, text = ONE_AM_TIMESTAMP)
    }

    /** Mirrors the successful upload handoff that publishes every own attachment before its canonical bridge. */
    private fun cacheCanonicalOwnFiles(item: TimelineMessage) {
        val attachmentCount = requireNotNull(item.projected).media.size
        composeRule.runOnIdle {
            repeat(attachmentCount) { attachmentIndex ->
                assertTrue(
                    "canonical own file must be retained before projection",
                    appState.cacheMediaPlaintext(
                        mediaCacheKey(ACCOUNT_REF, GROUP_ID, item.record.messageIdHex, attachmentIndex),
                        byteArrayOf(1, 2, 3, 4),
                    ),
                )
            }
        }
    }

    /** Verifies transfer, sizing, semantics, and selection invariants for every gallery row. */
    private fun assertFileGalleryStates(gallery: GalleryFixtures) {
        assertFileCardAndBubbleWidth(gallery.incoming, expectedWidth = 272f)
        assertFileCardAndBubbleWidth(gallery.downloadedApk, expectedWidth = 272f)
        assertFileCardAndBubbleWidth(gallery.outgoing, expectedWidth = 312f)
        // Incoming group messages use the 272 dp left after the opposite gutter
        // and sender-avatar slot, regardless of whether they carry a caption.
        assertFileCardAndBubbleWidth(gallery.captionedReply, expectedWidth = 272f)
        composeRule.onNodeWithText("Updated release notes").assertExists()
        assertFileCardAndBubbleWidth(gallery.largeFont, expectedWidth = 272f)
        // 300 dp host - 48 dp opposite gutter - 40 dp incoming avatar slot.
        assertFileCardAndBubbleWidth(gallery.constrained, expectedWidth = 212f)
        assertFileCardAndBubbleWidth(gallery.multiple, attachmentIndex = 0, expectedWidth = 272f)
        assertFileCardAndBubbleWidth(gallery.multiple, attachmentIndex = 1, expectedWidth = 272f)
        assertPendingFileCard(gallery.pending, PENDING_GENERIC_FILE, "Uploading…", 312f)
        composeRule.onNodeWithText("4 B").assertExists()
        composeRule.onNodeWithText(PERSISTED_FAILURE_FILE).assertDoesNotExist()
        composeRule.onNodeWithText("This message didn't reach the group").assertExists()
        val selectedRowDescription = "${appState.displayName(SENDER_ID)}, $SELECTED_CAPTION"
        val selectedRow = composeRule.onNodeWithContentDescription(selectedRowDescription)
        selectedRow.assertIsSelected()
        val selectedRowBounds = selectedRow.getUnclippedBoundsInRoot()
        assertEquals(360f, (selectedRowBounds.right - selectedRowBounds.left).value, 1f)
        // The 40 dp selection control fits within the existing 48 dp row
        // reserve, so selection does not shrink this captioned file.
        assertEquals(
            272.dp,
            messageBubbleColumnMaxWidth(360.dp, messageBubbleSelectionGutterWidth, 40.dp),
        )
        assertFileCardAndBubbleWidth(gallery.selectedCaptioned, expectedWidth = 272f)
        assertFileCardAndBubbleWidth(gallery.rtl, expectedWidth = 272f)
    }

    /** Renders every parent-layout variant through the production message bubble. */
    @Composable
    private fun FileGallery(gallery: GalleryFixtures) {
        Column(Modifier.width(360.dp)) {
            FileMessage(gallery.incoming)
            FileMessage(gallery.downloadedApk)
            FileMessage(gallery.outgoing)
            FileMessage(gallery.captionedReply)
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.6f)) {
                FileMessage(gallery.largeFont)
            }
            Column(Modifier.width(300.dp)) {
                FileMessage(gallery.constrained, showSenderAvatar = true)
            }
            FileMessage(gallery.multiple)
            FileMessage(gallery.pending)
            FileMessage(gallery.persistedFailure)
            FileMessage(
                gallery.selectedCaptioned,
                selectionMode = true,
                selected = true,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                FileMessage(gallery.rtl)
            }
        }
    }

    /** Builds the incoming, outgoing, pending, failure, selection, and RTL gallery records. */
    private fun fileGallery() =
        GalleryFixtures(
            incoming =
                fileTimelineMessage(
                    index = 1,
                    fileName = INCOMING_FILE,
                    mediaType = ANDROID_PACKAGE_MIME,
                ),
            downloadedApk =
                fileTimelineMessage(
                    index = 11,
                    fileName = DOWNLOADED_APK_FILE,
                    mediaType = ANDROID_PACKAGE_MIME,
                ),
            outgoing = fileTimelineMessage(index = 2, fileName = OUTGOING_FILE, mine = true),
            captionedReply =
                fileTimelineMessage(
                    index = 3,
                    fileName = CAPTIONED_REPLY_FILE,
                    caption = "Updated release notes",
                    hasReply = true,
                    mediaType = ANDROID_PACKAGE_MIME,
                ),
            largeFont = fileTimelineMessage(index = 4, fileName = LARGE_FONT_FILE),
            constrained = fileTimelineMessage(index = 5, fileName = CONSTRAINED_FILE),
            multiple =
                fileTimelineMessage(
                    index = 6,
                    fileName = MULTI_FIRST_FILE,
                    fileNames = listOf(MULTI_FIRST_FILE, MULTI_SECOND_FILE),
                ),
            pending = controller.pendingFileTimelineMessage(index = 7),
            persistedFailure =
                fileTimelineMessage(
                    index = 8,
                    fileName = PERSISTED_FAILURE_FILE,
                    invalidationStatus = PERSISTED_FAILURE_STATUS,
                ),
            selectedCaptioned =
                fileTimelineMessage(
                    index = 9,
                    fileName = SELECTED_FILE,
                    caption = SELECTED_CAPTION,
                ),
            rtl = fileTimelineMessage(index = 10, fileName = RTL_FILE),
        )

    private data class GalleryFixtures(
        val incoming: TimelineMessage,
        val downloadedApk: TimelineMessage,
        val outgoing: TimelineMessage,
        val captionedReply: TimelineMessage,
        val largeFont: TimelineMessage,
        val constrained: TimelineMessage,
        val multiple: TimelineMessage,
        val pending: TimelineMessage,
        val persistedFailure: TimelineMessage,
        val selectedCaptioned: TimelineMessage,
        val rtl: TimelineMessage,
    )

    private fun assertFileCardAndBubbleWidth(
        item: TimelineMessage,
        attachmentIndex: Int = 0,
        expectedWidth: Float,
    ) {
        val messageIdHex = item.record.messageIdHex
        val cardBounds =
            composeRule
                .onNodeWithTag(fileAttachmentCardTestTag(messageIdHex, attachmentIndex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val bubbleBounds =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(messageIdHex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(expectedWidth, (cardBounds.right - cardBounds.left).value, 1f)
        assertEquals(expectedWidth, (bubbleBounds.right - bubbleBounds.left).value, 1f)
    }

    private fun assertPendingFileCard(
        item: TimelineMessage,
        fileName: String,
        transferStateDescription: String,
        expectedWidth: Float,
    ) {
        composeRule.onNodeWithContentDescription(transferStateDescription).assertExists()
        composeRule.onNodeWithText(fileName).assertExists()
        val bubbleBounds =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(item.record.messageIdHex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(expectedWidth, (bubbleBounds.right - bubbleBounds.left).value, 1f)
    }

    /** Asserts merged TalkBack text has each requested field once and in visual reading order. */
    private fun assertAccessibleFileCardText(
        cardTag: String,
        vararg expectedText: String,
    ) {
        val mergedText =
            composeRule
                .onNodeWithTag(cardTag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        val indices =
            expectedText.map { text ->
                assertEquals("Expected one merged semantic text value for '$text'", 1, mergedText.count { it == text })
                mergedText.indexOf(text)
            }
        assertTrue(
            "Expected semantic text in visual order: ${expectedText.toList()}",
            indices.zipWithNext().all { it.first < it.second },
        )
    }

    /** Asserts merged TalkBack descriptions do not duplicate status or retention announcements. */
    private fun assertAccessibleFileCardDescriptions(
        cardTag: String,
        vararg expectedDescriptions: String,
    ) {
        val mergedDescriptions =
            composeRule
                .onNodeWithTag(cardTag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
        expectedDescriptions.forEach { description ->
            assertEquals(
                "Expected one merged semantic description for '$description'",
                1,
                mergedDescriptions.count { it == description },
            )
        }
    }

    /** Verifies visible warning or timestamp bounds remain within the owning file card. */
    private fun assertNodeInsideCard(
        cardTag: String,
        text: String,
    ) {
        val cardBounds =
            composeRule.onNodeWithTag(cardTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val nodeBounds = composeRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertBoundsInside(cardBounds, nodeBounds)
    }

    /** Verifies an icon announcement is spatially contained by the owning file card. */
    private fun assertDescriptionInsideCard(
        cardTag: String,
        description: String,
    ) {
        val cardBounds =
            composeRule.onNodeWithTag(cardTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val nodeBounds =
            composeRule.onNodeWithContentDescription(description, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertBoundsInside(cardBounds, nodeBounds)
    }

    /** Applies the inclusive containment contract shared by file-footer geometry checks. */
    private fun assertBoundsInside(
        outer: androidx.compose.ui.unit.DpRect,
        inner: androidx.compose.ui.unit.DpRect,
    ) {
        assertTrue(inner.left >= outer.left)
        assertTrue(inner.top >= outer.top)
        assertTrue(inner.right <= outer.right)
        assertTrue(inner.bottom <= outer.bottom)
    }

    @Composable
    @Suppress("LongMethod") // Exercises the real MessageBubble interaction and layout contract.
    private fun FileMessage(
        item: TimelineMessage,
        showSenderAvatar: Boolean = false,
        selectionMode: Boolean = false,
        selected: Boolean = false,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = selectionMode,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = selected,
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
            onQuickReactionsReset = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            inviteMutationInFlight = false,
            onJoinInvite = {},
            onDeclineInvite = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderAvatar = showSenderAvatar,
            parseMarkdown = ::markdown,
        )
    }
}

/** Owns deterministic message, account, roster, and media fixtures shared by the screenshot cases. */
open class MessageBubbleFileAttachmentFixtures {
    /** Creates a single-account app state without persistent draft side effects. */
    protected fun fileFooterAppState(context: android.content.Context) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    /** Builds a deterministic confirmed-message fixture with a canonical protocol id. */
    protected fun fileTimelineMessage(
        index: Int,
        fileName: String,
        mine: Boolean = false,
        caption: String = "",
        hasReply: Boolean = false,
        fileNames: List<String> = listOf(fileName),
        mediaType: String = "application/pdf",
        attachments: List<Pair<String, String>> = fileNames.map { it to mediaType },
        invalidationStatus: String? = null,
        status: MessageStatus = if (mine) MessageStatus.Sent else MessageStatus.Received,
        retentionSeconds: ULong? = null,
        retentionExpiresAt: ULong? = null,
    ): TimelineMessage {
        val messageId = index.toString(16).padStart(2, '0') + "00".repeat(31)
        val (sender, direction) = if (mine) ACCOUNT_ID to "sent" else SENDER_ID to "received"
        val mediaTags =
            attachments.map { (name, attachmentMediaType) ->
                MessageTagFfi(listOf("imeta", "m $attachmentMediaType", "filename $name"))
            }
        val record =
            AppMessageRecordFfi(
                messageIdHex = messageId,
                direction = direction,
                groupIdHex = GROUP_ID,
                sender = sender,
                plaintext = caption,
                contentTokens = markdown(caption),
                kind = 9uL,
                tags = mediaTags,
                sourceEpoch = 1uL,
                retentionSeconds = retentionSeconds,
                retentionExpiresAt = retentionExpiresAt,
                recordedAt = (ONE_AM_UTC_EPOCH_SECONDS + index).toULong(),
                receivedAt = (ONE_AM_UTC_EPOCH_SECONDS + index).toULong(),
            )
        val projected =
            TimelineMessageRecordFfi(
                messageIdHex = messageId,
                sourceMessageIdHex = messageId,
                direction = direction,
                groupIdHex = GROUP_ID,
                sender = sender,
                plaintext = caption,
                contentTokens = markdown(caption),
                kind = 9uL,
                tags = mediaTags,
                timelineAt = (ONE_AM_UTC_EPOCH_SECONDS + index).toULong(),
                receivedAt = (ONE_AM_UTC_EPOCH_SECONDS + index).toULong(),
                replyToMessageIdHex = PARENT_MESSAGE_ID.takeIf { hasReply },
                replyPreview = replyPreview().takeIf { hasReply },
                mediaJson = null,
                media =
                    attachments.map { (name, attachmentMediaType) ->
                        fileReference(name, attachmentMediaType)
                    },
                agentTextStreamJson = null,
                groupSystem = null,
                reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
                deleted = false,
                deletedByMessageIdHex = null,
                invalidationStatus = invalidationStatus,
                sourceEpoch = 1uL,
                retentionSeconds = retentionSeconds,
                retentionExpiresAt = retentionExpiresAt,
            )
        return TimelineMessage(
            id = "msg:$messageId",
            record = record,
            status = status,
            projected = projected,
        )
    }

    /** Queues a real optimistic attachment and normalizes only its clock for stable assertions. */
    protected fun ConversationController.pendingFileTimelineMessage(
        index: Int,
        fileName: String = PENDING_GENERIC_FILE,
        failed: Boolean = false,
    ): TimelineMessage =
        runBlocking {
            retryMembers()
            val queued =
                requireNotNull(
                    queueAttachments(
                        attachments =
                            listOf(
                                PendingAttachment(
                                    plaintextBytes = byteArrayOf(1, 2, 3, 4),
                                    mediaType = "application/zip",
                                    fileName = fileName,
                                ),
                            ),
                        caption = null,
                    ),
                )
            val deterministicTimestamp = (ONE_AM_UTC_EPOCH_SECONDS + index).toULong()
            TimelineMessage(
                id = queued.key,
                record =
                    queued.optimistic.copy(
                        recordedAt = deterministicTimestamp,
                        receivedAt = deterministicTimestamp,
                    ),
                status = if (failed) MessageStatus.Failed else MessageStatus.Pending,
                timelineOrder = queued.optimisticOrder,
            )
        }

    /** Creates a canonical encrypted-media reference without network access. */
    protected fun fileReference(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$fileName")),
        ciphertextSha256 = "a".repeat(64),
        plaintextSha256 = "b".repeat(64),
        nonceHex = "c".repeat(24),
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

    /** Provides a deterministic parent projection for the captioned reply case. */
    protected fun replyPreview() =
        TimelineReplyPreviewFfi(
            messageIdHex = PARENT_MESSAGE_ID,
            sender = SENDER_ID,
            plaintext = "Parent message",
            contentTokens = markdown("Parent message"),
            kind = 9uL,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            deleted = false,
            invalidationStatus = null,
        )

    /** Seeds the matching local member used before authoritative roster hydration. */
    protected fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
                    local = true,
                ),
            ),
        )

    /** Returns the stable authoritative self roster required by optimistic attachment queuing. */
    protected fun authoritativeRoster() =
        GroupRosterFfi(
            groupIdHex = GROUP_ID,
            members =
                listOf(
                    GroupMemberDetailsFfi(
                        memberIdHex = ACCOUNT_ID,
                        account = ACCOUNT_REF,
                        local = true,
                        isAdmin = true,
                        isSelf = true,
                        npub = "npub-$ACCOUNT_ID",
                        displayName = null,
                    ),
                ),
            epoch = 1uL,
            rosterRevision = 1uL,
            selfMembership = SelfMembershipFfi.MEMBER,
            memberCount = 1u,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
        )

    /** Builds the deterministic encrypted-media group shared across renderer cases. */
    protected fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "File width group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    /** Projects fixture captions through the same Markdown document shape consumed by the bubble. */
    protected fun markdown(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks =
                text
                    .takeIf(String::isNotBlank)
                    ?.let { content ->
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines = listOf(MarkdownInlineFfi.Text(content)),
                            ),
                        )
                    }.orEmpty(),
            blankLinesBefore = byteArrayOf(),
        )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}

private val originalTimeZone: TimeZone = TimeZone.getDefault()
private const val ONE_AM_UTC_EPOCH_SECONDS = 3_600
private const val ACCOUNT_REF = "personal"
private const val ACCOUNT_ID = "0100000000000000000000000000000000000000000000000000000000000000"
private const val SENDER_ID = "0200000000000000000000000000000000000000000000000000000000000000"
private const val GROUP_ID = "0400000000000000000000000000000000000000000000000000000000000000"
private const val PARENT_MESSAGE_ID = "0600000000000000000000000000000000000000000000000000000000000000"
private const val INCOMING_FILE = "incoming-release.apk"
private const val DOWNLOADED_APK_FILE = "downloaded-master-build.apk"
private const val OUTGOING_FILE = "outgoing-build.pdf"
private const val CAPTIONED_REPLY_FILE = "captioned-reply.apk"
private const val LARGE_FONT_FILE = "large-font-accessibility-report.pdf"
private const val CONSTRAINED_FILE = "constrained-width-file.pdf"
private const val MULTI_FIRST_FILE = "multiple-first.pdf"
private const val MULTI_SECOND_FILE = "multiple-second.pdf"
private const val PENDING_GENERIC_FILE = "pending-generic-archive.zip"
private const val PENDING_FAILED_FILE = "failed-generic-archive.zip"
private const val PERSISTED_FAILURE_FILE = "persisted-failure.pdf"
private const val PERSISTED_FAILURE_STATUS = "FutureEngineFailure"
private const val DELIVERY_NOT_CONFIRMED = "Delivery not confirmed"
private const val ONE_AM_TIMESTAMP = "1:00\u202FAM"
private const val CAPTIONED_UNCONFIRMED_FILE = "captioned-unconfirmed.pdf"
private const val CAPTIONED_UNCONFIRMED_TEXT = "Release notes awaiting confirmation"
private const val CAPTIONED_UNCONFIRMED_TIME = "1:01\u202FAM"
private const val MIXED_IMAGE = "mixed-preview.jpg"
private const val MIXED_FILE = "mixed-document.pdf"
private const val MIXED_DELIVERED_TIME = "1:02\u202FAM"
private const val CONFIRMED_FAILED_FILE = "confirmed-failed.pdf"
private const val CONFIRMED_FAILED_TIME = "1:03\u202FAM"
private const val FAR_FUTURE_EPOCH_SECONDS = 4_102_444_800uL
private const val SELECTED_FILE = "selected-captioned.pdf"
private const val SELECTED_CAPTION = "Selected constrained attachment"
private const val RTL_FILE = "rtl-layout-document.pdf"
private const val SNAPSHOT_PATH = "src/test/snapshots/message_bubble_file_attachment_width.png"
private const val PENDING_FAILED_FILE_SNAPSHOT_PATH =
    "src/test/snapshots/message_bubble_file_pending_failed_light.png"
private const val UNCONFIRMED_FILE_SNAPSHOT_PATH =
    "src/test/snapshots/message_bubble_file_unconfirmed_dark_large_rtl.png"
private const val FIRST_FRAME_CARD_TAG = "received-file-first-frame-card"
private const val FIRST_FRAME_UNRESOLVED_SNAPSHOT_PATH =
    "src/test/snapshots/received_file_first_frame_unresolved_light.png"
private const val FIRST_FRAME_RESOLVED_SNAPSHOT_PATH =
    "src/test/snapshots/received_file_first_frame_resolved_light.png"
