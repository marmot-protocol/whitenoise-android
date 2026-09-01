package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking

internal const val SWIPE_TEST_ACCOUNT_REF = "personal"
internal val SWIPE_TEST_ACCOUNT_ID = "01" + "00".repeat(31)
internal val SWIPE_TEST_SENDER_ID = "02" + "00".repeat(31)
internal val SWIPE_TEST_GROUP_ID = "04" + "00".repeat(31)
internal val SWIPE_TEST_MESSAGE_ID = "05" + "00".repeat(31)
internal const val SWIPE_TEST_MESSAGE_BODY = "Reacted swipe body"
internal const val SWIPE_TEST_FILE_NAME = "release-notes.pdf"
internal const val SWIPE_TEST_REACTION_EMOJI = "\ud83d\udc4d"
internal const val SWIPE_TEST_HOST_TAG = "swipe-translation-host"

/** One live controller plus its single reacted (or plain) timeline item. */
internal data class SwipeTestSurface(
    val appState: WhiteNoiseAppState,
    val controller: ConversationController,
    val item: TimelineMessage,
)

/** Builds the real controller-backed surface used by the swipe translation tests. */
internal fun swipeTestSurface(
    context: Context,
    reacted: Boolean,
    mine: Boolean,
    media: Boolean,
): SwipeTestSurface {
    val appState = swipeTestAppState(context)
    val controller = ConversationController(appState = appState, initialGroup = swipeTestGroup())
    runBlocking {
        controller.testRefreshCurrentTimeline(SWIPE_TEST_ACCOUNT_REF) {
            TimelinePageFfi(
                messages = listOf(swipeTestProjectedRecord(reacted = reacted, mine = mine, media = media)),
                hasMoreBefore = false,
                hasMoreAfter = false,
            )
        }
    }
    return SwipeTestSurface(appState, controller, controller.timeline.single())
}

/** Hosts one real [MessageBubble] inside the stationary full-width gesture row. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun SwipeTestBubbleHost(
    surface: SwipeTestSurface,
    rtl: Boolean = false,
    amoled: Boolean = false,
) {
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        WhiteNoiseTheme(darkTheme = true, amoled = amoled) {
            Box(Modifier.fillMaxWidth().testTag(SWIPE_TEST_HOST_TAG)) {
                MessageBubble(
                    item = surface.item,
                    controller = surface.controller,
                    appState = surface.appState,
                    showSenderAvatar = surface.item.record.direction != "sent",
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
                    onQuickReactionsReset = {},
                    onReplyPreviewClick = {},
                    composerGate = ComposerGate.COMPOSER,
                    inviteMutationInFlight = false,
                    onJoinInvite = {},
                    onDeclineInvite = {},
                    mentionCandidates = emptyList(),
                    mentionPickerEnabled = false,
                )
            }
        }
    }
}

/** One projected record with optional reaction summary and optional file media. */
internal fun swipeTestProjectedRecord(
    reacted: Boolean,
    mine: Boolean,
    media: Boolean,
): TimelineMessageRecordFfi {
    val reference =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$SWIPE_TEST_FILE_NAME")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = SWIPE_TEST_FILE_NAME,
            mediaType = "application/pdf",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )
    return TimelineMessageRecordFfi(
        messageIdHex = SWIPE_TEST_MESSAGE_ID,
        sourceMessageIdHex = SWIPE_TEST_MESSAGE_ID,
        direction = if (mine) "sent" else "received",
        groupIdHex = SWIPE_TEST_GROUP_ID,
        sender = if (mine) SWIPE_TEST_ACCOUNT_ID else SWIPE_TEST_SENDER_ID,
        plaintext = if (media) "" else SWIPE_TEST_MESSAGE_BODY,
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = byteArrayOf(),
            ),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = 1uL,
        receivedAt = 1uL,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = if (media) listOf(reference) else emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = swipeTestReactions(reacted),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = if (media) 1uL else null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )
}

/** One thumbs-up reaction summary, or the empty summary for the control case. */
private fun swipeTestReactions(reacted: Boolean): TimelineReactionSummaryFfi =
    if (reacted) {
        TimelineReactionSummaryFfi(
            byEmoji =
                listOf(
                    TimelineReactionEmojiFfi(SWIPE_TEST_REACTION_EMOJI, 1u, listOf(SWIPE_TEST_SENDER_ID)),
                ),
            userReactions =
                listOf(
                    TimelineUserReactionFfi(
                        reactionMessageIdHex = "06" + "00".repeat(31),
                        targetMessageIdHex = SWIPE_TEST_MESSAGE_ID,
                        sender = SWIPE_TEST_SENDER_ID,
                        emoji = SWIPE_TEST_REACTION_EMOJI,
                        reactedAt = 2uL,
                    ),
                ),
        )
    } else {
        TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList())
    }

/** Minimal single-account app state for bubble composition. */
private fun swipeTestAppState(context: Context) =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(SwipeTestDraftPersistence()),
        accountIdHexResolver = { null },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = SWIPE_TEST_ACCOUNT_REF,
                    accountIdHex = SWIPE_TEST_ACCOUNT_ID,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = SWIPE_TEST_ACCOUNT_REF,
    )

/** One stable member group hosting the test conversation. */
private fun swipeTestGroup() =
    AppGroupRecordFfi(
        groupIdHex = SWIPE_TEST_GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Swipe group",
        description = "",
        admins = listOf(SWIPE_TEST_ACCOUNT_ID),
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

private class SwipeTestDraftPersistence : DraftPersistence {
    /** No persisted drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Draft writes are dropped. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
