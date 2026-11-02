package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
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
import org.robolectric.shadows.ShadowView
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaVideoShareParityTest {
    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

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

    /** The real dialog returns from page two toward page two's currently drawn thumbnail. */
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    // Real dialog acquisition, selected-page paint, and matching return share one owned fixture.
    @Suppress("LongMethod")
    fun pagedDialogReturnsToItsSelectedThumbnailWithoutAnotherImageRead() {
        val fixture = fixture()
        val pages =
            listOf(imagePage("first-image", 0), imagePage("second-image", 1)).map { page ->
                page.copy(
                    reference =
                        page.reference.copy(
                            fileName = "${page.messageIdHex}.png",
                            mediaType = "image/png",
                            dim = "64x32",
                        ),
                )
            }
        val registry = MediaViewerThumbnailRegistry()
        val open = mutableStateOf(false)
        val selected = mutableStateOf<ConversationMediaViewerAttachmentId?>(null)
        val reads = java.util.concurrent.CopyOnWriteArrayList<String>()
        var dismissals = 0
        val blue = 0xff145bd7.toInt()
        val red = 0xffd72e14.toInt()
        val pixels = imageFixturePixels(pages, red, blue)
        val resolver = context.contentResolver
        val previousScale =
            android.provider.Settings.Global
                .getString(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        val previousDrawTraversals =
            ReflectionHelpers.callStaticMethod<Boolean>(ShadowView::class.java, "areRealDrawTraversalsEnabled")
        // Native PixelCopy uses the supported View.draw adapter without a real device window surface.
        ShadowView.setUseRealDrawTraversals(false)
        android.provider.Settings.Global
            .putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = true, amoled = true) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier
                            .fillMaxSize(),
                    ) {
                        MediaViewerThumbnailHost(registry) {
                            for ((index, page) in pages.withIndex()) {
                                androidx.compose.foundation.Canvas(
                                    androidx.compose.ui.Modifier
                                        .offset(24.dp, if (index == 0) 150.dp else 600.dp)
                                        .size(96.dp, 64.dp)
                                        .mediaViewerThumbnail(page.messageIdHex, page.attachmentIndex, 1uL)
                                        .testTag("origin.$index")
                                        .clickable { if (index == 0) open.value = true },
                                ) {
                                    drawRect(
                                        androidx.compose.ui.graphics
                                            .Color(if (index == 0) red else blue),
                                    )
                                }
                            }
                        }
                        if (open.value) {
                            ConversationMediaViewer(
                                controller = fixture.controller,
                                appState = fixture.appState,
                                conversationVisualPages = pages,
                                messageIdHex = pages.first().messageIdHex,
                                attachments = listOf(IndexedValue(0, pages.first().reference)),
                                tappedAttachmentIndex = 0,
                                sender = pages.first().sender,
                                recordedAt = pages.first().recordedAt,
                                mine = false,
                                onDismiss = {
                                    dismissals++
                                    open.value = false
                                },
                                onSelectedAttachmentChange = { selected.value = it },
                                imageBytes = { page ->
                                    reads += page.messageIdHex
                                    pixels.getValue(page.messageIdHex)
                                },
                                thumbnails = registry,
                            )
                        }
                    }
                }
            }
            // A real draw makes the owned thumbnail layers eligible; this does not decode or load media.
            composeRule.onRoot().captureToImage()
            assertTrue(registry.find(ConversationMediaViewerAttachmentId("first-image", 0)) != null)
            assertTrue(registry.find(ConversationMediaViewerAttachmentId("second-image", 1)) != null)
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("origin.0").performClick()
            composeRule.submitMediaViewerDialogFrame()
            composeRule.mainClock.autoAdvance = true
            composeRule.onNodeWithContentDescription(context.getString(R.string.close)).assertIsDisplayed()
            val first = context.getString(R.string.media_viewer_page_position, 1, 2)
            val second = context.getString(R.string.media_viewer_page_position, 2, 2)
            composeRule.onNode(hasStateDescription(first)).performTouchInput { swipeLeft() }
            composeRule.waitUntil(5_000) {
                selected.value == ConversationMediaViewerAttachmentId("second-image", 1) &&
                    reads.contains("second-image")
            }
            composeRule.onNode(hasStateDescription(second)).assertExists()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithContentDescription("second-image.png").fetchSemanticsNodes().any {
                    it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
                }
            }
            composeRule.waitUntil(5_000) {
                val rendered = composeRule.onNode(isDialog()).captureToImage().toPixelMap()
                val center = rendered[rendered.width / 2, rendered.height / 2]
                center.blue > 0.7f && center.red < 0.2f && center.green < 0.5f
            }
            val beforeReturn = reads.toList()
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(128)
            assertSelectedBlueReturn(composeRule.onNode(isDialog()).captureToImage())
            assertEquals("return motion cannot request another image", beforeReturn, reads.toList())
            composeRule.mainClock.advanceTimeBy(240)
            composeRule.waitForIdle()
            assertEquals(1, dismissals)
            composeRule.onNode(isDialog()).assertDoesNotExist()
            assertEquals(beforeReturn, reads.toList())
        } finally {
            composeRule.mainClock.autoAdvance = true
            ShadowView.setUseRealDrawTraversals(previousDrawTraversals)
            fixture.controller.onCleared()
            android.provider.Settings.Global
                .putString(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
            resolver.notifyChange(
                android.provider.Settings.Global
                    .getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),
                null,
            )
        }
    }

    /** Encodes a solid 64x32 PNG per page, [red] for the first and [blue] for the rest, keyed by message id. */
    private fun imageFixturePixels(
        pages: List<MediaViewerPage>,
        red: Int,
        blue: Int,
    ): Map<String, ByteArray> =
        pages.associate { page ->
            val bitmap = android.graphics.Bitmap.createBitmap(64, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (page === pages.first()) red else blue)
            val bytes =
                java.io.ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            bitmap.recycle()
            page.messageIdHex to bytes
        }

    /** Asserts a mid-return frame still paints the blue source and that it sits near the second thumbnail's origin. */
    private fun assertSelectedBlueReturn(image: androidx.compose.ui.graphics.ImageBitmap) {
        val frame = image.toPixelMap()
        var top = frame.height
        var bottom = -1
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val color = frame[x, y]
                if (color.blue > 0.7f && color.red < 0.2f && color.green < 0.5f) {
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("the return must still paint the selected blue source", bottom >= top)
        assertTrue(
            "the flight must approach the second thumbnail, not the first or viewer center",
            (top + bottom) / 2f > frame.height * 0.65f,
        )
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
