package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the real conversation viewer pages. A tap on media that is not on the device only downloads it in place,
 * which DownloadNeverOpensTest covers, so no pending open needs to outlive its originating row.
 */
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

    /** A pending or failed image page can always receive the tap that restores hidden chrome. */
    @Test
    fun pendingImagePageCanRestoreHiddenChrome() {
        val fixture = fixture(DIRECT_MESSAGE_ID)
        var chromeToggleCount = 0
        composeRule.setContent {
            ViewerPage(
                controller = fixture.controller,
                messageIdHex = DIRECT_MESSAGE_ID,
                attachmentIndex = 0,
                reference = reference(attachmentIndex = 0, mediaType = "image/jpeg"),
                scale = 1f,
                offset = Offset.Zero,
                onScaleChange = {},
                onOffsetChange = {},
                mine = false,
                isCurrent = true,
                onChromeToggle = { chromeToggleCount++ },
            )
        }

        composeRule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(1, chromeToggleCount) }
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
