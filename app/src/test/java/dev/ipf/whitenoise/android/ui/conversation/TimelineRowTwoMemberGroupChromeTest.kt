package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TimelineRowTwoMemberGroupChromeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val composerTextState = ComposerTextState(TextFieldValue())

    @Test
    fun mountedNamedGroupPreservesTwoMemberChromeAcrossInitialRefresh() {
        var rosterRead = 0
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                initialMemberSnapshot =
                    GroupMemberSnapshot(
                        listOf(
                            cachedMember(ACCOUNT_ID, local = true),
                            cachedMember(SENDER_ID),
                        ),
                    ),
                groupRosterReader = { _, _ ->
                    when (rosterRead++) {
                        0 -> roster(member(ACCOUNT_ID, local = true, isSelf = true), member(SENDER_ID))
                        else ->
                            roster(
                                member(ACCOUNT_ID, local = true, isSelf = true),
                                member(SENDER_ID),
                                member(THIRD_ID),
                            )
                    }
                },
            )
        val item = timelineMessage()
        val senderName = appState.displayName(SENDER_ID)
        val senderInitials = IdentityFormatter.initials(senderName)

        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    TimelineRowFixture(item = item, appState = appState, controller = controller)
                }
            }
        }

        composeRule.onNodeWithText(senderName, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(senderInitials, useUnmergedTree = true).assertDoesNotExist()
        val initialTextLeft = messageTextLeft()

        composeRule.runOnIdle { runBlocking { controller.retryMembers() } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(senderName, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(senderInitials, useUnmergedTree = true).assertDoesNotExist()
        val directTextLeft = messageTextLeft()
        assertEquals("refresh must not shift the two-member transcript", initialTextLeft, directTextLeft, 0.5f)

        composeRule.runOnIdle { runBlocking { controller.retryMembers() } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(senderName, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(senderInitials, useUnmergedTree = true).assertExists()
        assertTrue("three-member transcript must restore the sender-avatar gutter", messageTextLeft() > directTextLeft)
    }

    @Test
    fun twoMemberNamedGroupCompactRtlAmoledLargeFont() {
        val appState = appState()
        val controller = verifiedController(appState, listOf(ACCOUNT_ID, SENDER_ID))

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1.3f) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(320.dp).testTag(SCREENSHOT_TAG)) {
                            TimelineRowFixture(timelineMessage(), appState, controller)
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/timeline_two_member_group_direct_chrome_rtl_amoled_large_font.png")
    }

    @Test
    fun threeMemberNamedGroupCompactMissingProfileLight() {
        val appState = appState()
        val controller = verifiedController(appState, listOf(ACCOUNT_ID, SENDER_ID, THIRD_ID))

        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.width(320.dp).testTag(SCREENSHOT_TAG)) {
                        TimelineRowFixture(timelineMessage(), appState, controller)
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/timeline_three_member_group_sender_chrome_missing_profile_light.png")
    }

    /** Renders the production timeline row while accepting its default no-op media-viewer owner. */
    @androidx.compose.runtime.Composable
    @Suppress("LongMethod")
    private fun TimelineRowFixture(
        item: TimelineMessage,
        appState: WhiteNoiseAppState,
        controller: ConversationController,
    ) {
        TimelineRow(
            item = item,
            older = null,
            newer = null,
            transcriptLocale = Locale.US,
            entryUnreadCount = 0,
            entryUnreadDividerRetired = true,
            entryFirstUnreadMessageId = null,
            onMeasured = { _, _ -> },
            appState = appState,
            controller = controller,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = false,
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
            onBack = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            collapseLongMessages = false,
        )
    }

    private fun messageTextLeft(): Float =
        composeRule
            .onNodeWithText(MESSAGE_TEXT, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .left
            .value

    private fun appState(): WhiteNoiseAppState {
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { it },
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
                profileDisplayNameReader = { id -> SENDER_NAME.takeIf { id == SENDER_ID } },
            )
        runBlocking { state.warmProfilePresentationsBlocking(listOf(SENDER_ID)) }
        return state
    }

    private fun verifiedController(
        appState: WhiteNoiseAppState,
        memberIds: List<String>,
    ): ConversationController {
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                groupRosterReader = { _, _ ->
                    roster(
                        *memberIds
                            .map { id -> member(id, local = id == ACCOUNT_ID, isSelf = id == ACCOUNT_ID) }
                            .toTypedArray(),
                    )
                },
            )
        runBlocking { controller.retryMembers() }
        return controller
    }

    private fun timelineMessage(): TimelineMessage {
        val record =
            AppMessageRecordFfi(
                messageIdHex = MESSAGE_ID,
                direction = "received",
                groupIdHex = GROUP_ID,
                sender = SENDER_ID,
                plaintext = MESSAGE_TEXT,
                contentTokens = document(MESSAGE_TEXT),
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = 1uL,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
            )
        return TimelineMessage(id = "msg:$MESSAGE_ID", record = record, status = MessageStatus.Received)
    }

    private fun document(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
        )

    private fun roster(vararg members: GroupMemberDetailsFfi) =
        GroupRosterFfi(
            groupIdHex = GROUP_ID,
            members = members.toList(),
            epoch = 1uL,
            rosterRevision = members.size.toULong(),
            selfMembership = SelfMembershipFfi.MEMBER,
            memberCount = members.size.toUInt(),
            lifecycleState = GroupLifecycleStateFfi.STABLE,
        )

    private fun member(
        id: String,
        local: Boolean = false,
        isSelf: Boolean = false,
    ) = GroupMemberDetailsFfi(
        memberIdHex = id,
        account = ACCOUNT_REF.takeIf { local },
        local = local,
        isAdmin = local,
        isSelf = isSelf,
        npub = "npub-$id",
        displayName = null,
    )

    private fun cachedMember(
        id: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = id,
        account = ACCOUNT_REF.takeIf { local },
        local = local,
    )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Named pair",
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
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "11".repeat(32)
        val SENDER_ID = "22".repeat(32)
        val THIRD_ID = "33".repeat(32)
        val GROUP_ID = "44".repeat(32)
        val MESSAGE_ID = "55".repeat(32)
        const val MESSAGE_TEXT = "Hello transcript"
        const val SENDER_NAME = "Bob Example"
        const val SCREENSHOT_TAG = "two-member-group-transcript"
    }
}
