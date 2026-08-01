package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import dev.ipf.marmotkit.SelfMembershipFfi
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleLongPressDragTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Suppress("LongMethod") // The real MessageBubble host requires its full interaction contract.
    fun longPressDragEntersRangeWithoutOpeningMessageActions() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        val item = timelineMessage()
        val composerTextState = ComposerTextState(TextFieldValue(""))
        var selectionMode by mutableStateOf(false)
        var rangeDragActive by mutableStateOf(false)
        var actionOpens = 0
        var dragStarts = 0
        var dragMoves = 0
        var dragEnds = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
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
                        isActionMenuOpen = false,
                        onActionMenuOpenChange = { if (it) actionOpens++ },
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

        composeRule.onNodeWithText(MESSAGE_BODY).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(center.x, center.y + viewConfiguration.touchSlop + 24f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, actionOpens)
        assertEquals(1, dragStarts)
        assertTrue(dragMoves >= 1)
        assertEquals(1, dragEnds)
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

    private fun timelineMessage() =
        TimelineMessage(
            id = "msg:$MESSAGE_ID",
            record =
                AppMessageRecordFfi(
                    messageIdHex = MESSAGE_ID,
                    direction = "received",
                    groupIdHex = GROUP_ID,
                    sender = SENDER_ID,
                    plaintext = MESSAGE_BODY,
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
        val MESSAGE_ID = "05" + "00".repeat(31)
        const val MESSAGE_BODY = "Message drag host"
    }
}
