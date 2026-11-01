package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.AnimationTermination
import dev.ipf.whitenoise.android.media.PreemptibleAnimation
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventImagePane
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
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

    /** Both real source entry points expose the same zoom/reset viewport, without constructing a fake attachment. */
    @Test
    fun attachmentAndPublicImageUseTheSameAccessibleViewport() {
        val fixture = fixture(DIRECT_MESSAGE_ID)
        var publicImage by mutableStateOf(false)
        var dismissed = 0
        val bitmap = android.graphics.Bitmap.createBitmap(20, 10, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes =
            java.io.ByteArrayOutputStream().also {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        composeRule.setContent {
            WhiteNoiseTheme {
                if (publicImage) {
                    NostrEventImagePane(
                        url = "https://images.example/shared-viewer",
                        initiallyRequested = true,
                        onFullscreenDismiss = { dismissed++ },
                        loadImage = { _, _ -> bitmap.asImageBitmap() },
                    )
                } else {
                    FullScreenMediaViewer(
                        controller = fixture.controller,
                        appState = fixture.appState,
                        pages =
                            listOf(
                                MediaViewerPage(
                                    DIRECT_MESSAGE_ID,
                                    0,
                                    reference(0, "image/png"),
                                    false,
                                    "Alex",
                                    1uL,
                                ),
                            ),
                        startIndex = 0,
                        onDismiss = { dismissed++ },
                        imageBytes = { bytes.toByteArray() },
                    )
                }
            }
        }
        val zoomLabel = context.getString(R.string.media_viewer_zoom_image)
        val fitLabel = context.getString(R.string.media_viewer_fit_image)
        repeat(2) { source ->
            assertSharedViewportZoomAndFit(zoomLabel, fitLabel)
            if (source == 0) composeRule.runOnIdle { publicImage = true }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun aHealthyAnimationKeepsItsClockAndOnlyOneConfirmedDeathAutomaticallyReplacesIt() {
        val drawables = java.util.concurrent.CopyOnWriteArrayList<ViewerTestAnimation>()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        var visible by mutableStateOf(true)
        renderAnimationFixture(drawables, calls) { visible }
        composeRule.waitUntil(10_000) { calls.get() == 1 }
        awaitViewerSourceWorkCompletion()
        val first = drawables[0]
        composeRule.runOnIdle { visible = false }
        composeRule.runOnIdle { visible = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, calls.get())
            first.terminal.complete(AnimationTermination.WorkerLostUnknown)
        }
        composeRule.waitUntil(10_000) { calls.get() == 2 }
        awaitViewerSourceWorkCompletion()
        composeRule.runOnIdle { drawables[1].terminal.complete(AnimationTermination.WorkerLostUnknown) }
        composeRule.onNodeWithTag("media.viewer.retry").assertExists()
        composeRule.runOnIdle { assertEquals(2, calls.get()) }
        composeRule.onNodeWithTag("media.viewer.retry").performClick()
        composeRule.waitUntil(10_000) { calls.get() == 3 }
    }

    @Test
    fun protocolLossNeedsAnExplicitRetryAndDoesNotInventADeathProof() {
        val drawables = java.util.concurrent.CopyOnWriteArrayList<ViewerTestAnimation>()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        renderAnimationFixture(drawables, calls) { true }
        composeRule.waitUntil(10_000) { calls.get() == 1 }
        awaitViewerSourceWorkCompletion()
        composeRule.runOnIdle { drawables[0].terminal.complete(AnimationTermination.ProtocolRejected) }
        composeRule.onNodeWithTag("media.viewer.retry").assertExists()
        composeRule.runOnIdle { assertEquals(1, calls.get()) }
        composeRule.onNodeWithTag("media.viewer.retry").performClick()
        composeRule.waitUntil(10_000) { calls.get() == 2 }
    }

    @Test
    fun viewerImageWorkWaitsForForegroundAndRetainsItsAnimationAcrossBackgrounding() {
        val lifecycle = ViewerPageTestLifecycleOwner(Lifecycle.State.CREATED)
        val drawables = java.util.concurrent.CopyOnWriteArrayList<ViewerTestAnimation>()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        val reads =
            java.util.concurrent.atomic
                .AtomicInteger()
        renderAnimationFixture(drawables, calls, lifecycle, { reads.incrementAndGet() }) { true }
        composeRule.runOnIdle {
            assertEquals(0, reads.get())
            assertEquals(0, calls.get())
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitUntil(10_000) { calls.get() == 1 }
        awaitViewerSourceWorkCompletion()
        composeRule.runOnIdle {
            assertEquals(1, reads.get())
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, reads.get())
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitUntil(10_000) { reads.get() == 2 }
        awaitViewerSourceWorkCompletion()
        composeRule.runOnIdle {
            assertEquals(1, calls.get())
            assertEquals(1, drawables.size)
        }
    }

    private fun awaitViewerSourceWorkCompletion() {
        val sourceWorkCompleted =
            java.util.concurrent.atomic
                .AtomicBoolean()
        val probeOwner = Job()
        val completionProbe =
            CoroutineScope(Dispatchers.Default + probeOwner).launch {
                // The byte read happens inside this lane; reacquiring it joins source preparation and publication.
                withViewerImageWork { sourceWorkCompleted.set(true) }
            }
        try {
            composeRule.waitUntil(10_000) { sourceWorkCompleted.get() && completionProbe.isCompleted }
        } finally {
            probeOwner.cancel()
            composeRule.waitUntil(10_000) { probeOwner.isCompleted }
        }
    }

    private fun renderAnimationFixture(
        drawables: java.util.concurrent.CopyOnWriteArrayList<ViewerTestAnimation>,
        calls: java.util.concurrent.atomic.AtomicInteger,
        lifecycle: ViewerPageTestLifecycleOwner = ViewerPageTestLifecycleOwner(),
        onRead: () -> Unit = {},
        visible: () -> Boolean,
    ) {
        val fixture = fixture(DIRECT_MESSAGE_ID)
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun animation(
                    bytes: ByteArray,
                    edge: Int,
                ): android.graphics.drawable.Drawable =
                    ViewerTestAnimation().also {
                        drawables.add(it)
                        calls.incrementAndGet()
                    }
            },
        )
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalSystemAnimationsEnabled provides true,
                    LocalLifecycleOwner provides lifecycle,
                ) {
                    ViewerPage(
                        controller = fixture.controller,
                        messageIdHex = DIRECT_MESSAGE_ID,
                        attachmentIndex = 0,
                        reference = reference(0, "image/gif"),
                        scale = 1f,
                        offset = Offset.Zero,
                        onScaleChange = {},
                        onOffsetChange = {},
                        mine = false,
                        isCurrent = visible(),
                        onChromeToggle = {},
                        imageBytes = {
                            onRead()
                            twoFrameGif()
                        },
                    )
                }
            }
        }
    }

    private class ViewerTestAnimation :
        android.graphics.drawable.Drawable(),
        android.graphics.drawable.Animatable,
        PreemptibleAnimation,
        java.io.Closeable {
        val terminal = kotlinx.coroutines.CompletableDeferred<AnimationTermination>()
        private var running = false

        override fun getIntrinsicWidth() = 2

        override fun getIntrinsicHeight() = 2

        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawColor(android.graphics.Color.YELLOW)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Suppress("DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

        override fun start() {
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun isRunning() = running

        override suspend fun awaitPreemption() {
            kotlinx.coroutines.awaitCancellation()
        }

        override suspend fun awaitTermination() = terminal.await()

        override fun close() {
            running = false
            terminal.complete(AnimationTermination.OwnerClosed)
        }
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

    private fun assertSharedViewportZoomAndFit(
        zoomLabel: String,
        fitLabel: String,
    ) {
        composeRule.waitForIdle()
        val zoomAction =
            composeRule
                .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == zoomLabel }
        repeat(3) { composeRule.runOnIdle { check(zoomAction.action()) } }
        composeRule
            .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.media_viewer_image_scale, 800),
                ),
            )
        composeRule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).performTouchInput { doubleClick() }
        composeRule
            .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.media_viewer_image_scale, 100),
                ),
            )
        val fitAction =
            composeRule
                .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == fitLabel }
        composeRule.runOnIdle { check(fitAction.action()) }
    }
}
