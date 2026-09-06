package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatRow
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_TAIL_GAP
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Visual contract for #812: recreation paints retained content, never a loading replacement. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class WarmResumeFirstUsefulFrameScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun retainedChatListFirstFrameLight() {
        captureChatListFirstFrame(
            snapshotName = "warm_resume_chat_list_first_frame_light",
            darkTheme = false,
            amoled = false,
        )
    }

    @Test
    fun retainedChatListFirstFrameAmoledLargeFontRtl() {
        captureChatListFirstFrame(
            snapshotName = "warm_resume_chat_list_first_frame_amoled_large_rtl",
            darkTheme = true,
            amoled = true,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    /** Captures a retained conversation with the #415 single-tail-gap contract. */
    @Test
    fun retainedConversationFirstFrameLight() {
        captureConversationFirstFrame(
            snapshotName = "warm_resume_conversation_first_frame_light",
            darkTheme = false,
            amoled = false,
        )
    }

    /** Repeats the retained #415 contract under large-font RTL rendering. */
    @Test
    fun retainedConversationFirstFrameAmoledLargeFontRtl() {
        captureConversationFirstFrame(
            snapshotName = "warm_resume_conversation_first_frame_amoled_large_rtl",
            darkTheme = true,
            amoled = true,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    private fun captureChatListFirstFrame(
        snapshotName: String,
        darkTheme: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState = appState()
        val chat = chatItem()
        withUtcTimeZone {
            composeRule.setContent {
                val density = LocalDensity.current
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                        LocalLayoutDirection provides layoutDirection,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize().testTag(SCREENSHOT_TAG),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            Column {
                                Text(
                                    text = "Chats",
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                HorizontalDivider()
                                ChatRow(
                                    item = chat,
                                    appState = appState,
                                    interactionsEnabled = false,
                                    onClick = {},
                                    onOpenProfile = {},
                                )
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(CACHED_MESSAGE, substring = true).assertIsDisplayed()
            progressNodes().assertCountEquals(0)
            composeRule
                .onNodeWithTag(SCREENSHOT_TAG)
                .captureRoboImage("src/test/snapshots/$snapshotName.png")
        }
    }

    private fun captureConversationFirstFrame(
        snapshotName: String,
        darkTheme: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState = appState()
        val group = group()
        val preview = cachedPreview()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialTimelinePreview = preview,
            )
        controller.markAuthoritativeTimelinePublishedForTest()
        val chat = chatItem(group, preview, unread = false)

        composeRule.mainClock.autoAdvance = false
        try {
            withUtcTimeZone {
                composeRule.setContent {
                    val density = LocalDensity.current
                    WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density, fontScale),
                            LocalLayoutDirection provides layoutDirection,
                        ) {
                            ConversationScreen(
                                appState = appState,
                                chat = chat,
                                controller = controller,
                                onBack = {},
                            )
                        }
                    }
                }
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.waitForIdle()
                composeRule.onNodeWithText(CACHED_MESSAGE).assertIsDisplayed()
                progressNodes().assertCountEquals(0)
                assertSingleTailGap(CACHED_MESSAGE_ID)
                composeRule
                    .onRoot()
                    .captureRoboImage("src/test/snapshots/$snapshotName.png")
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
            controller.onCleared()
        }
    }

    private fun progressNodes() =
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        )

    /** Asserts that restored geometry leaves one resting interval above the composer. */
    private fun assertSingleTailGap(messageId: String) {
        val transcriptBottom =
            composeRule
                .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val tailBottom =
            composeRule
                .onNodeWithTag(messageBubbleRowTestTag(messageId), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals(
            with(composeRule.density) { CONVERSATION_TIMELINE_TAIL_GAP.toPx() },
            transcriptBottom - tailBottom,
            1f,
        )
    }

    private inline fun withUtcTimeZone(block: () -> Unit) {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
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

    private fun chatItem(
        group: AppGroupRecordFfi = group(),
        preview: ChatListMessagePreviewFfi = cachedPreview(),
        unread: Boolean = true,
    ) = ChatListItem(
        group = group,
        latest = null,
        otherMemberAccount = null,
        memberCount = 2,
        memberSnapshot = null,
        projection = cachedProjection(preview, unread),
    )

    private fun cachedPreview() =
        ChatListMessagePreviewFfi(
            messageIdHex = CACHED_MESSAGE_ID,
            sender = "02" + "00".repeat(31),
            senderDisplayName = "Cached sender",
            plaintext = CACHED_MESSAGE,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            timelineAt = 10uL,
            deleted = false,
            attachmentKind = null,
            attachmentCount = 0u,
            deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
        )

    private fun cachedProjection(
        preview: ChatListMessagePreviewFfi,
        unread: Boolean,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = GROUP_NAME,
        groupName = GROUP_NAME,
        avatarUrl = null,
        avatar = null,
        lastMessage = preview,
        unreadCount = if (unread) 2uL else 0uL,
        hasUnread = unread,
        firstUnreadMessageIdHex = preview.messageIdHex.takeIf { unread },
        lastReadMessageIdHex = preview.messageIdHex.takeUnless { unread },
        lastReadTimelineAt = preview.timelineAt.takeUnless { unread },
        conversationCreatedAt = 0uL,
        activitySortAt = preview.timelineAt,
        updatedAt = preview.timelineAt,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.UNKNOWN,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = GROUP_NAME,
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
        const val SCREENSHOT_TAG = "warm-resume-chat-list-first-frame"
        const val ACCOUNT_REF = "personal"
        const val GROUP_NAME = "Restored project room"
        const val CACHED_MESSAGE = "Cached hello on the first frame"
        val CACHED_MESSAGE_ID = "05" + "00".repeat(31)
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
