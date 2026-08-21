@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
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
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1100dp-mdpi")
class MessageBubbleFileAttachmentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = appState()
    private val controller = ConversationController(appState = appState, initialGroup = group())
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    @Test
    fun realMessageBubblePathKeepsFileCardsReadableAcrossParentVariants() {
        val incoming = fileTimelineMessage(index = 1, fileName = INCOMING_FILE)
        val outgoing = fileTimelineMessage(index = 2, fileName = OUTGOING_FILE, mine = true)
        val captionedReply =
            fileTimelineMessage(
                index = 3,
                fileName = CAPTIONED_REPLY_FILE,
                caption = "Updated release notes",
                hasReply = true,
            )
        val largeFont = fileTimelineMessage(index = 4, fileName = LARGE_FONT_FILE)
        val constrained = fileTimelineMessage(index = 5, fileName = CONSTRAINED_FILE)
        val multiple =
            fileTimelineMessage(
                index = 6,
                fileName = MULTI_FIRST_FILE,
                fileNames = listOf(MULTI_FIRST_FILE, MULTI_SECOND_FILE),
            )

        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.width(360.dp).testTag(GALLERY_TAG)) {
                    FileMessage(incoming)
                    FileMessage(outgoing)
                    FileMessage(captionedReply)
                    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.6f)) {
                        FileMessage(largeFont)
                    }
                    Column(Modifier.width(300.dp)) {
                        FileMessage(constrained, showSenderAvatar = true)
                    }
                    FileMessage(multiple)
                }
            }
        }

        assertFileCardWidth(INCOMING_FILE, 240f)
        assertFileCardWidth(OUTGOING_FILE, 240f)
        assertFileCardWidth(CAPTIONED_REPLY_FILE, 240f)
        composeRule.onNodeWithText("Updated release notes").assertExists()
        assertFileCardWidth(LARGE_FONT_FILE, 240f)
        // 300 dp host - 48 dp opposite gutter - 40 dp incoming avatar slot.
        assertFileCardWidth(CONSTRAINED_FILE, 212f)
        assertFileCardWidth(MULTI_FIRST_FILE, 240f)
        assertFileCardWidth(MULTI_SECOND_FILE, 240f)
        composeRule.onNodeWithTag(GALLERY_TAG).captureRoboImage(SNAPSHOT_PATH)
    }

    private fun assertFileCardWidth(
        fileName: String,
        expectedWidth: Float,
    ) {
        val bounds = composeRule.onNodeWithText(fileName).getUnclippedBoundsInRoot()
        assertEquals(expectedWidth, (bounds.right - bounds.left).value, 1f)
    }

    @Composable
    @Suppress("LongMethod") // Exercises the real MessageBubble interaction and layout contract.
    private fun FileMessage(
        item: TimelineMessage,
        showSenderAvatar: Boolean = false,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
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
    ): TimelineMessage {
        val messageId = index.toString(16).padStart(2, '0') + "00".repeat(31)
        val sender = if (mine) ACCOUNT_ID else SENDER_ID
        val direction = if (mine) "sent" else "received"
        val mediaTags =
            fileNames.map { name ->
                MessageTagFfi(listOf("imeta", "m application/pdf", "filename $name"))
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
                recordedAt = index.toULong(),
                receivedAt = index.toULong(),
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
                timelineAt = index.toULong(),
                receivedAt = index.toULong(),
                replyToMessageIdHex = PARENT_MESSAGE_ID.takeIf { hasReply },
                replyPreview = replyPreview().takeIf { hasReply },
                mediaJson = null,
                media = fileNames.map(::fileReference),
                agentTextStreamJson = null,
                groupSystem = null,
                reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
                deleted = false,
                deletedByMessageIdHex = null,
                invalidationStatus = null,
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

    private fun fileReference(fileName: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "application/pdf",
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
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val PARENT_MESSAGE_ID = "06" + "00".repeat(31)
        const val INCOMING_FILE = "incoming-release-notes.pdf"
        const val OUTGOING_FILE = "outgoing-build.pdf"
        const val CAPTIONED_REPLY_FILE = "captioned-reply.pdf"
        const val LARGE_FONT_FILE = "large-font-accessibility-report.pdf"
        const val CONSTRAINED_FILE = "constrained-width-file.pdf"
        const val MULTI_FIRST_FILE = "multiple-first.pdf"
        const val MULTI_SECOND_FILE = "multiple-second.pdf"
        const val GALLERY_TAG = "message-bubble-file-width-gallery"
        const val SNAPSHOT_PATH = "src/test/snapshots/message_bubble_file_attachment_width.png"
    }
}
