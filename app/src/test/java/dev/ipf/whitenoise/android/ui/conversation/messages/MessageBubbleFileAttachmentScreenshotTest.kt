@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import dev.ipf.whitenoise.android.ui.conversation.media.fileAttachmentCardTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [36], qualifiers = "w360dp-h1100dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleFileAttachmentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = appState()
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    @Before
    fun setDeterministicTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(ORIGINAL_TIME_ZONE)
    }

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

    private fun assertFileGalleryStates(gallery: GalleryFixtures) {
        assertFileCardAndBubbleWidth(gallery.incoming, expectedWidth = 240f)
        assertFileCardAndBubbleWidth(gallery.downloadedApk, expectedWidth = 240f)
        assertFileCardAndBubbleWidth(gallery.outgoing, expectedWidth = 240f)
        // Captioned incoming group messages prefer 320 dp, then clamp to the
        // 272 dp left after the opposite gutter and sender-avatar slot.
        assertFileCardAndBubbleWidth(gallery.captionedReply, expectedWidth = 272f)
        composeRule.onNodeWithText("Updated release notes").assertExists()
        assertFileCardAndBubbleWidth(gallery.largeFont, expectedWidth = 240f)
        // 300 dp host - 48 dp opposite gutter - 40 dp incoming avatar slot.
        assertFileCardAndBubbleWidth(gallery.constrained, expectedWidth = 212f)
        assertFileCardAndBubbleWidth(gallery.multiple, attachmentIndex = 0, expectedWidth = 240f)
        assertFileCardAndBubbleWidth(gallery.multiple, attachmentIndex = 1, expectedWidth = 240f)
        assertPendingFileCard(gallery.pending, PENDING_GENERIC_FILE, "Uploading…", 240f)
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
        assertFileCardAndBubbleWidth(gallery.rtl, expectedWidth = 240f)
    }

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
            pending = pendingFileTimelineMessage(index = 7),
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
        val controlBounds =
            composeRule
                .onNodeWithContentDescription(transferStateDescription)
                .getUnclippedBoundsInRoot()
        val fileNameBounds = composeRule.onNodeWithText(fileName).getUnclippedBoundsInRoot()
        val cardWidth =
            (fileNameBounds.right + FILE_CARD_CONTENT_PADDING) -
                (controlBounds.left - FILE_CARD_CONTENT_PADDING)
        val bubbleBounds =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(item.record.messageIdHex), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(expectedWidth, cardWidth.value, 1f)
        assertEquals(expectedWidth, (bubbleBounds.right - bubbleBounds.left).value, 1f)
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

    private fun appState() =
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

    private fun fileTimelineMessage(
        index: Int,
        fileName: String,
        mine: Boolean = false,
        caption: String = "",
        hasReply: Boolean = false,
        fileNames: List<String> = listOf(fileName),
        mediaType: String = "application/pdf",
        invalidationStatus: String? = null,
    ): TimelineMessage {
        val messageId = index.toString(16).padStart(2, '0') + "00".repeat(31)
        val sender = if (mine) ACCOUNT_ID else SENDER_ID
        val direction = if (mine) "sent" else "received"
        val mediaTags =
            fileNames.map { name ->
                MessageTagFfi(listOf("imeta", "m $mediaType", "filename $name"))
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
                retentionSeconds = null,
                retentionExpiresAt = null,
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
                media = fileNames.map { name -> fileReference(name, mediaType) },
                agentTextStreamJson = null,
                groupSystem = null,
                reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
                deleted = false,
                deletedByMessageIdHex = null,
                invalidationStatus = invalidationStatus,
                sourceEpoch = 1uL,
                retentionSeconds = null,
                retentionExpiresAt = null,
            )
        return TimelineMessage(
            id = "msg:$messageId",
            record = record,
            status = if (mine) MessageStatus.Sent else MessageStatus.Received,
            projected = projected,
        )
    }

    private fun pendingFileTimelineMessage(index: Int): TimelineMessage =
        runBlocking {
            controller.retryMembers()
            val queued =
                requireNotNull(
                    controller.queueAttachments(
                        attachments =
                            listOf(
                                PendingAttachment(
                                    plaintextBytes = byteArrayOf(1, 2, 3, 4),
                                    mediaType = "application/zip",
                                    fileName = PENDING_GENERIC_FILE,
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
                status = MessageStatus.Pending,
                timelineOrder = queued.optimisticOrder,
            )
        }

    private fun fileReference(
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

    private fun replyPreview() =
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

    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
                    local = true,
                ),
            ),
        )

    private fun authoritativeRoster() =
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

    private fun group() =
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

    private fun markdown(text: String) =
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

    private companion object {
        val ORIGINAL_TIME_ZONE: TimeZone = TimeZone.getDefault()
        const val ONE_AM_UTC_EPOCH_SECONDS = 3_600
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val PARENT_MESSAGE_ID = "06" + "00".repeat(31)
        const val INCOMING_FILE = "incoming-release.apk"
        const val DOWNLOADED_APK_FILE = "downloaded-master-build.apk"
        const val OUTGOING_FILE = "outgoing-build.pdf"
        const val CAPTIONED_REPLY_FILE = "captioned-reply.apk"
        const val LARGE_FONT_FILE = "large-font-accessibility-report.pdf"
        const val CONSTRAINED_FILE = "constrained-width-file.pdf"
        const val MULTI_FIRST_FILE = "multiple-first.pdf"
        const val MULTI_SECOND_FILE = "multiple-second.pdf"
        const val PENDING_GENERIC_FILE = "pending-generic-archive.zip"
        const val PERSISTED_FAILURE_FILE = "persisted-failure.pdf"
        const val PERSISTED_FAILURE_STATUS = "FutureEngineFailure"
        const val SELECTED_FILE = "selected-captioned.pdf"
        const val SELECTED_CAPTION = "Selected constrained attachment"
        const val RTL_FILE = "rtl-layout-document.pdf"
        val FILE_CARD_CONTENT_PADDING = 10.dp
        const val SNAPSHOT_PATH = "src/test/snapshots/message_bubble_file_attachment_width.png"
    }
}
