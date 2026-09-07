package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real conversation video affordances feeding the row-independent viewer host. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ConversationMediaViewerProductionPathTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fixtures = mutableListOf<Fixture>()

    /** Releases every controller owned by this test even when a view assertion fails. */
    @After
    fun releaseControllers() {
        fixtures.forEach { it.controller.onCleared() }
        fixtures.clear()
    }

    /** Verifies the production direct-video bubble opens while bytes are still pending and may dispose. */
    @Test
    fun directVideoPendingOpenSurvivesOriginatingBubbleDisposal() {
        val fixture = fixture(DIRECT_MESSAGE_ID)
        val reference = reference(attachmentIndex = 0)
        assertNull(cachedVideoAttachmentFile(context, DIRECT_MESSAGE_ID, 0, reference))

        assertPendingOpenSurvivesRowDisposal(
            messageIdHex = DIRECT_MESSAGE_ID,
            attachmentIndex = 0,
        ) { session ->
            MediaVideoBubble(
                item = fixture.item,
                attachmentIndex = 0,
                reference = reference,
                controller = fixture.controller,
                appState = fixture.appState,
                onOpenConversationMedia = { request -> session.open(request) },
                mine = false,
            )
        }
    }

    /** Verifies the production mixed-media grid delegates a pending video before its lazy row disposes. */
    @Test
    fun mixedMediaPendingVideoOpenSurvivesOriginatingTileDisposal() {
        val fixture = fixture(MIXED_MESSAGE_ID)
        val attachments =
            listOf(
                IndexedValue(0, reference(attachmentIndex = 0, mediaType = "image/jpeg")),
                IndexedValue(1, reference(attachmentIndex = 1)),
            )
        assertNull(cachedVideoAttachmentFile(context, MIXED_MESSAGE_ID, 1, attachments[1].value))

        assertPendingOpenSurvivesRowDisposal(
            messageIdHex = MIXED_MESSAGE_ID,
            attachmentIndex = 1,
        ) { session ->
            MediaVisualGridBubble(
                item = fixture.item,
                attachments = attachments,
                controller = fixture.controller,
                appState = fixture.appState,
                onOpenConversationMedia = { request -> session.open(request) },
                mine = false,
            )
        }
    }

    /** Opens [bubble], removes its row from composition, resizes, and requires the same session id. */
    private fun assertPendingOpenSurvivesRowDisposal(
        messageIdHex: String,
        attachmentIndex: Int,
        bubble: @Composable (ConversationMediaViewerSessionState) -> Unit,
    ) {
        var landscape by mutableStateOf(false)
        var openedSessionId: Long? = null

        composeRule.setContent {
            WhiteNoiseTheme {
                val session =
                    rememberConversationMediaViewerSessionState(
                        ConversationMediaViewerOwner(ACCOUNT_REF, GROUP_ID, runtimeGeneration = 1),
                    )
                val timelineState = rememberLazyListState()
                Box(
                    Modifier
                        .width(if (landscape) 780.dp else 360.dp)
                        .height(if (landscape) 360.dp else 780.dp),
                ) {
                    LazyColumn(
                        state = timelineState,
                        modifier = Modifier.fillMaxSize().testTag(TIMELINE_TAG),
                    ) {
                        item(key = "origin") { bubble(session) }
                        items(80, key = { "message-$it" }) {
                            Text("Message $it", Modifier.height(48.dp))
                        }
                    }
                    ConversationMediaViewerSessionHost(session) { active ->
                        openedSessionId = active.sessionId
                        Text(active.selectedAttachment.messageIdHex, Modifier.testTag(VIEWER_TAG))
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag(videoAttachmentOpenTestTag(messageIdHex, attachmentIndex), useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithTag(VIEWER_TAG).assertIsDisplayed()
        val sessionId = requireNotNull(openedSessionId)

        composeRule.onNodeWithTag(TIMELINE_TAG).performScrollToIndex(79)
        composeRule
            .onNodeWithTag(videoAttachmentOpenTestTag(messageIdHex, attachmentIndex), useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.runOnIdle { landscape = true }
        composeRule.onNodeWithTag(VIEWER_TAG).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(sessionId, openedSessionId) }
    }

    /** Builds the minimal production controller and timeline item for one visual bubble. */
    private fun fixture(messageIdHex: String): Fixture {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
            )
        return Fixture(
            appState = appState,
            controller = ConversationController(appState = appState, initialGroup = group()),
            item = timelineMessage(messageIdHex),
        ).also(fixtures::add)
    }

    /** Creates a received kind-9 timeline message for the requested production bubble. */
    private fun timelineMessage(messageIdHex: String) =
        TimelineMessage(
            id = "msg:$messageIdHex",
            record =
                AppMessageRecordFfi(
                    messageIdHex = messageIdHex,
                    direction = "received",
                    groupIdHex = GROUP_ID,
                    sender = SENDER_ID,
                    plaintext = "",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blankLinesBefore = byteArrayOf(),
                            blocks = emptyList(),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = 1uL,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )

    /** Creates independently hashed image or video references for mixed-media identity checks. */
    private fun reference(
        attachmentIndex: Int,
        mediaType: String = "video/mp4",
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "${attachmentIndex + 1}".repeat(64),
        plaintextSha256 = "${attachmentIndex + 2}".repeat(64),
        nonceHex = "c".repeat(24),
        fileName = if (mediaType.startsWith("video/")) "clip.mp4" else "still.jpg",
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = "320x180",
        thumbhash = null,
    )

    /** Creates a media-capable group record without starting network-backed state. */
    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Video lifetime test",
            description = "",
            admins = emptyList(),
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
                    allowedLocatorKinds = emptyList(),
                    defaultBlobEndpoints = emptyList(),
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

    private data class Fixture(
        val appState: WhiteNoiseAppState,
        val controller: ConversationController,
        val item: TimelineMessage,
    )

    private object EmptyDraftPersistence : DraftPersistence {
        /** Starts the production-path fixture without composer state. */
        override fun read(): Map<String, String> = emptyMap()

        /** Ignores unrelated draft writes during viewer-lifetime assertions. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val TIMELINE_TAG = "production-video-timeline"
        const val VIEWER_TAG = "production-conversation-media-viewer"
        val GROUP_ID = "44".repeat(32)
        val SENDER_ID = "55".repeat(32)
        val DIRECT_MESSAGE_ID = "66".repeat(32)
        val MIXED_MESSAGE_ID = "77".repeat(32)
    }
}
