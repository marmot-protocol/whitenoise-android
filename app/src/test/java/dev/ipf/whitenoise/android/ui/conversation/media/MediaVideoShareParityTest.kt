package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaVideoShareParityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun directAndPagerRoutesShareTheSameExactVisibleVideo() {
        val fixture = fixture()
        val targetPage =
            videoPage(
                messageIdHex = TARGET_MESSAGE_ID,
                attachmentIndex = TARGET_ATTACHMENT_INDEX,
                mine = true,
            )
        val pages =
            listOf(
                imagePage(messageIdHex = "image-message", attachmentIndex = 1),
                targetPage,
                videoPage(messageIdHex = "neighbor-message", attachmentIndex = 9, mine = false),
            )
        val showPagerRoute = mutableStateOf(false)

        setRouteContent(fixture, targetPage, pages, showPagerRoute)

        clickShareAndAwaitLaunch(fixture)
        val directRequest = fixture.requests.single()
        val directChooser = fixture.shareContext.choosers.single()
        assertShareRequest(directRequest, targetPage)
        assertSharesheetPayload(directChooser, fixture.targetBytes, fixture.neighborBytes)

        fixture.clearCaptures()
        composeRule.runOnIdle { showPagerRoute.value = true }
        val firstPageDescription = context.getString(R.string.media_viewer_page_position, 1, pages.size)
        val targetPageDescription = context.getString(R.string.media_viewer_page_position, 2, pages.size)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasStateDescription(firstPageDescription)).fetchSemanticsNodes().size == 1
        }
        composeRule.onNode(hasStateDescription(firstPageDescription)).performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasStateDescription(targetPageDescription)).fetchSemanticsNodes().size == 1
        }

        clickShareAndAwaitLaunch(fixture)
        val pagerRequest = fixture.requests.single()
        assertShareRequest(pagerRequest, targetPage)
        assertSharesheetPayload(fixture.shareContext.choosers.single(), fixture.targetBytes, fixture.neighborBytes)
        assertEquals(directRequest, pagerRequest)
    }

    private fun setRouteContent(
        fixture: ShareFixture,
        targetPage: MediaViewerPage,
        pages: List<MediaViewerPage>,
        showPagerRoute: MutableState<Boolean>,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides fixture.shareContext) {
                WhiteNoiseTheme {
                    if (showPagerRoute.value) {
                        FullScreenMediaViewer(
                            controller = fixture.controller,
                            appState = fixture.appState,
                            pages = pages,
                            startIndex = 0,
                            onDismiss = {},
                            onShareRequest = fixture::share,
                        )
                    } else {
                        ConversationMediaViewer(
                            controller = fixture.controller,
                            appState = fixture.appState,
                            conversationVisualPages = emptyList(),
                            messageIdHex = targetPage.messageIdHex,
                            attachments = listOf(IndexedValue(targetPage.attachmentIndex, targetPage.reference)),
                            tappedAttachmentIndex = targetPage.attachmentIndex,
                            sender = targetPage.sender,
                            recordedAt = targetPage.recordedAt,
                            mine = targetPage.mine,
                            onDismiss = {},
                            onShareRequest = fixture::share,
                        )
                    }
                }
            }
        }
    }

    private fun clickShareAndAwaitLaunch(fixture: ShareFixture) {
        composeRule.onNodeWithContentDescription(context.getString(R.string.share)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { fixture.outcomes.size == 1 }
        val outcome = fixture.outcomes.single()
        assertTrue(outcome.exceptionOrNull()?.stackTraceToString(), outcome.isSuccess)
        assertEquals(1, fixture.shareContext.choosers.size)
    }

    private fun assertShareRequest(
        request: MediaViewerShareRequest,
        expectedPage: MediaViewerPage,
    ) {
        assertEquals(expectedPage.messageIdHex, request.messageIdHex)
        assertEquals(expectedPage.attachmentIndex, request.attachmentIndex)
        assertEquals(expectedPage.reference.fileName, request.reference.fileName)
        assertEquals(expectedPage.reference.mediaType, request.reference.mediaType)
        assertEquals(expectedPage.mine, request.mine)
    }

    private fun assertSharesheetPayload(
        chooser: Intent,
        expectedBytes: ByteArray,
        neighboringBytes: ByteArray,
    ) {
        val shareIntent =
            IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
                ?: error("chooser did not contain a share intent")
        val stream =
            IntentCompat.getParcelableExtra(shareIntent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                ?: error("share intent did not contain a stream")

        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertEquals(TARGET_MEDIA_TYPE, shareIntent.type)
        assertEquals("content", stream.scheme)
        assertEquals("${context.packageName}.fileprovider", stream.authority)
        assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val deliveredBytes = context.contentResolver.openInputStream(stream)?.use { it.readBytes() }
        assertArrayEquals(expectedBytes, deliveredBytes)
        assertNotEquals(neighboringBytes.toList(), deliveredBytes?.toList())
    }

    private fun fixture(): ShareFixture {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
            )
        val targetBytes = "selected target video".encodeToByteArray()
        val neighborBytes = "neighboring video must stay private".encodeToByteArray()
        return ShareFixture(
            appState = appState,
            controller = ConversationController(appState = appState, initialGroup = group()),
            shareContext = CapturingShareContext(context),
            targetBytes = targetBytes,
            neighborBytes = neighborBytes,
            targetFile = File.createTempFile("selected-video", ".webm").apply { writeBytes(targetBytes) },
        )
    }

    private fun videoPage(
        messageIdHex: String,
        attachmentIndex: Int,
        mine: Boolean,
    ) = page(messageIdHex, attachmentIndex, mine, "$messageIdHex.webm", TARGET_MEDIA_TYPE)

    private fun imagePage(
        messageIdHex: String,
        attachmentIndex: Int,
    ) = page(messageIdHex, attachmentIndex, false, "$messageIdHex.jpg", "image/jpeg")

    private fun page(
        messageIdHex: String,
        attachmentIndex: Int,
        mine: Boolean,
        fileName: String,
        mediaType: String,
    ) = MediaViewerPage(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference =
            MediaAttachmentReferenceFfi(
                locators = emptyList(),
                ciphertextSha256 = "aa".repeat(32),
                plaintextSha256 = "bb".repeat(32),
                nonceHex = "cc".repeat(12),
                fileName = fileName,
                mediaType = mediaType,
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            ),
        mine = mine,
        sender = "sender-$messageIdHex",
        recordedAt = 1_777_777_777uL,
    )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = "04" + "00".repeat(31),
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Media test",
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

    private class ShareFixture(
        val appState: WhiteNoiseAppState,
        val controller: ConversationController,
        val shareContext: CapturingShareContext,
        val targetBytes: ByteArray,
        val neighborBytes: ByteArray,
        private val targetFile: File,
    ) {
        val requests = mutableListOf<MediaViewerShareRequest>()
        val outcomes = mutableListOf<Result<Unit>>()

        suspend fun share(request: MediaViewerShareRequest): Result<Unit> {
            requests += request
            return shareVideo(shareContext, targetFile, request.reference.fileName, request.reference.mediaType)
                .also(outcomes::add)
        }

        fun clearCaptures() {
            requests.clear()
            outcomes.clear()
            shareContext.choosers.clear()
        }
    }

    private class CapturingShareContext(
        base: Context,
    ) : ContextWrapper(base) {
        val choosers = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            choosers += intent
        }
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val TARGET_MESSAGE_ID = "target-message"
        const val TARGET_ATTACHMENT_INDEX = 7
        const val TARGET_MEDIA_TYPE = "video/webm"
    }
}
