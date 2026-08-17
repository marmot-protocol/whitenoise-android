package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.rememberedMessageBubbleTime
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleLongPressDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Suppress("LongMethod") // The real MessageBubble host requires its full interaction contract.
    fun receivedUncaptionedFileUsesOnlyTheFileCardTimestamp() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        val item = receivedFileTimelineMessage()
        val composerTextState = ComposerTextState(TextFieldValue(""))
        var timestampText = ""

        composeRule.setContent {
            WhiteNoiseTheme {
                timestampText = rememberedMessageBubbleTime(item.record.recordedAt)
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
                )
            }
        }

        composeRule.runOnIdle { assertTrue(timestampText.isNotBlank()) }
        composeRule
            .onAllNodesWithText(timestampText, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    @Suppress("LongMethod") // The real MessageBubble host requires its full interaction contract.
    fun stationaryLongPressOpensActionsAtThresholdBeforePointerUp() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        val item = timelineMessage()
        val composerTextState = ComposerTextState(TextFieldValue(""))
        var bubbleVisible by mutableStateOf(true)
        var actionMenuOpen by mutableStateOf(false)
        var actionOpens = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                if (bubbleVisible) {
                    Box(Modifier.fillMaxWidth().testTag(MESSAGE_HOLD_HOST_TAG)) {
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
                            isActionMenuOpen = actionMenuOpen,
                            onActionMenuOpenChange = { open ->
                                if (open && !actionMenuOpen) actionOpens++
                                actionMenuOpen = open
                            },
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

        val messageBubble = composeRule.onNodeWithTag(MESSAGE_HOLD_HOST_TAG)
        messageBubble.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(center)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(MESSAGE_ACTION_MENU_TEST_TAG).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(actionMenuOpen)
            assertEquals(1, actionOpens)
        }

        messageBubble.performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(actionMenuOpen)
            assertEquals(1, actionOpens)
        }

        composeRule.runOnIdle { bubbleVisible = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, actionMenuOpen) }
    }

    @Test
    @Suppress("LongMethod") // The real MessageBubble host requires its full interaction contract.
    fun longPressDragDismissesThresholdActionsAndEntersRange() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        val item = timelineMessage()
        val composerTextState = ComposerTextState(TextFieldValue(""))
        var selectionMode by mutableStateOf(false)
        var rangeDragActive by mutableStateOf(false)
        var actionMenuOpen by mutableStateOf(false)
        var actionOpens = 0
        var dragStarts = 0
        var dragMoves = 0
        var dragEnds = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().testTag(MESSAGE_DRAG_HOST_TAG)) {
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
                        selected = selectionMode,
                        onToggleSelection = {},
                        rangeDragActive = rangeDragActive,
                        onDragSelectionStart = {
                            rangeDragActive = true
                            selectionMode = true
                            dragStarts++
                        },
                        onDragSelection = {
                            dragMoves++
                            true
                        },
                        onDragSelectionEnd = {
                            rangeDragActive = false
                            dragEnds++
                        },
                        onDragSelectionCancel = { rangeDragActive = false },
                        quickReactionEmojis = emptyList(),
                        recentEmojis = emptyList(),
                        onEmojiUsed = {},
                        isActionMenuOpen = actionMenuOpen,
                        onActionMenuOpenChange = { open ->
                            if (open && !actionMenuOpen) actionOpens++
                            actionMenuOpen = open
                        },
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

        val messageBubble = composeRule.onNodeWithTag(MESSAGE_DRAG_HOST_TAG)
        messageBubble.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
        }
        composeRule.waitForIdle()

        // The first range update enters selection mode and rewrites the row's
        // visual/input chrome. The same physical pointer must remain owned by
        // the range detector across that recomposition.
        messageBubble.performTouchInput {
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 48f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, actionOpens)
        assertEquals(false, actionMenuOpen)
        assertEquals(1, dragStarts)
        assertTrue(dragMoves >= 2)
        assertEquals(1, dragEnds)
    }

    @Test
    @Suppress("LongMethod") // Exercises four real MessageBubble rows through their public gesture contract.
    fun longPressDragSelectsFourMessageRowsWithoutLifting() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        val items = List(4, ::timelineMessage)
        val composerTextState = ComposerTextState(TextFieldValue(""))
        val rowBounds = arrayOfNulls<Rect>(items.size)
        var selectedIds by mutableStateOf(emptySet<String>())
        var anchorIndex by mutableIntStateOf(-1)
        var dragEnds = 0
        var dragCancels = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.testTag(MESSAGE_RANGE_HOST_TAG)) {
                    items.forEachIndexed { index, item ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .testTag("message-range-row-$index")
                                .onGloballyPositioned { rowBounds[index] = it.boundsInWindow() },
                        ) {
                            MessageBubble(
                                item = item,
                                controller = controller,
                                appState = appState,
                                composerTextState = composerTextState,
                                highlighted = false,
                                selectionMode = selectedIds.isNotEmpty(),
                                textSelectionMode = false,
                                onTextSelectionModeChange = {},
                                onTextSelectionBoundsChange = {},
                                batchSelectable = true,
                                selected = item.record.messageIdHex in selectedIds,
                                onToggleSelection = {},
                                rangeDragActive = anchorIndex == index,
                                onDragSelectionStart = { anchorIndex = index },
                                onDragSelection = { pointerWindowY ->
                                    val endpoint =
                                        rowBounds.indices.minByOrNull { candidate ->
                                            abs(pointerWindowY - (rowBounds[candidate]?.center?.y ?: Float.MAX_VALUE))
                                        }
                                    if (endpoint == null || anchorIndex < 0) {
                                        false
                                    } else {
                                        selectedIds =
                                            (minOf(anchorIndex, endpoint)..maxOf(anchorIndex, endpoint))
                                                .mapTo(linkedSetOf()) { items[it].record.messageIdHex }
                                        true
                                    }
                                },
                                onDragSelectionEnd = {
                                    dragEnds++
                                    anchorIndex = -1
                                },
                                onDragSelectionCancel = {
                                    dragCancels++
                                    anchorIndex = -1
                                    selectedIds = emptySet()
                                },
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
        }

        val start =
            composeRule
                .onNodeWithTag("message-range-row-0")
                .fetchSemanticsNode()
                .boundsInRoot.center
        val second =
            composeRule
                .onNodeWithTag("message-range-row-1")
                .fetchSemanticsNode()
                .boundsInRoot.center
        val fourth =
            composeRule
                .onNodeWithTag("message-range-row-3")
                .fetchSemanticsNode()
                .boundsInRoot.center
        val root = composeRule.onRoot()
        root.performTouchInput {
            down(start)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(second)
        }
        composeRule.runOnIdle { assertEquals(2, selectedIds.size) }
        root.performTouchInput {
            updatePointerTo(0, fourth)
            move(100)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(4, selectedIds.size)
            assertEquals(1, dragEnds)
            assertEquals(0, dragCancels)
        }
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

    private fun timelineMessage(index: Int = 0): TimelineMessage {
        val messageId = (5 + index).toString(16).padStart(2, '0') + "00".repeat(31)
        return TimelineMessage(
            id = "msg:$messageId",
            record =
                AppMessageRecordFfi(
                    messageIdHex = messageId,
                    direction = "received",
                    groupIdHex = GROUP_ID,
                    sender = SENDER_ID,
                    plaintext = "$MESSAGE_BODY $index",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = byteArrayOf(),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
    }

    private fun receivedFileTimelineMessage(): TimelineMessage {
        val record =
            timelineMessage().record.copy(
                plaintext = "",
                sourceEpoch = 1uL,
            )
        val media =
            MediaAttachmentReferenceFfi(
                locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/release-notes.pdf")),
                ciphertextSha256 = "a".repeat(64),
                plaintextSha256 = "b".repeat(64),
                nonceHex = "c".repeat(24),
                fileName = "release-notes.pdf",
                mediaType = "application/pdf",
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            )
        val projected =
            TimelineMessageRecordFfi(
                messageIdHex = record.messageIdHex,
                sourceMessageIdHex = record.messageIdHex,
                direction = record.direction,
                groupIdHex = record.groupIdHex,
                sender = record.sender,
                plaintext = record.plaintext,
                contentTokens = record.contentTokens,
                kind = record.kind,
                tags = record.tags,
                timelineAt = record.recordedAt,
                receivedAt = record.receivedAt,
                replyToMessageIdHex = null,
                replyPreview = null,
                mediaJson = null,
                media = listOf(media),
                agentTextStreamJson = null,
                groupSystem = null,
                reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
                deleted = false,
                deletedByMessageIdHex = null,
                invalidationStatus = null,
                sourceEpoch = record.sourceEpoch,
                retentionSeconds = null,
                retentionExpiresAt = null,
            )
        return TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = record,
            status = MessageStatus.Received,
            projected = projected,
        )
    }

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Gesture group",
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
        const val MESSAGE_BODY = "Message drag host"
        const val MESSAGE_HOLD_HOST_TAG = "message-hold-host"
        const val MESSAGE_DRAG_HOST_TAG = "message-drag-host"
        const val MESSAGE_RANGE_HOST_TAG = "message-range-host"
    }
}
