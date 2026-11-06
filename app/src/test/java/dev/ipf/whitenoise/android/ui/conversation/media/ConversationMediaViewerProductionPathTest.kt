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
import kotlinx.coroutines.runBlocking
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

    /** An uncached foreign chat must not acquire or paint media through the live opener with equal message IDs. */
    @Test
    fun crossChatImageRejectsFallbackBeforeRendering() = assertCrossChatRendererBinding("image/png")

    /** The same admission boundary precedes pending video materialization and player creation. */
    @Test
    fun crossChatVideoRejectsFallbackBeforeRendering() = assertCrossChatRendererBinding("video/mp4")

    /**
     * Keeps one foreign page fixed while its controller lookup moves from rejected fallback to valid source and
     * back.
     */
    private fun assertCrossChatRendererBinding(mediaType: String) {
        val fixture = fixture(DIRECT_MESSAGE_ID)
        val source = ConversationController(fixture.appState, group().copy(groupIdHex = "ab".repeat(32)))
        fixtures.add(fixture.copy(controller = source))
        assertEquals(true, fixture.controller.ownsRetainedForegroundPresentation)
        assertEquals(true, source.ownsRetainedForegroundPresentation)
        var validSource by mutableStateOf(false)
        val acquisitions =
            java.util.concurrent.atomic
                .AtomicInteger()
        renderCrossChatBindingFixture(fixture, source, mediaType, acquisitions) { validSource }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, acquisitions.get()) }
        composeRule
            .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
            .assertDoesNotExist()
        // The page stays fixed while its source lookup changes from the opener fallback to its real chat.
        composeRule.runOnIdle { validSource = true }
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) { acquisitions.get() > 0 }
        if (mediaType == "image/png") {
            composeRule.awaitViewerImageWorkIdle()
            composeRule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).assertExists()
        }
        // Revoking the binding removes the renderer instead of retaining its last decoded pixels/player.
        composeRule.runOnIdle { validSource = false }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
            .assertDoesNotExist()
        val afterRevocation = acquisitions.get()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(afterRevocation, acquisitions.get()) }
    }

    /** Hosts the real viewer with deterministic lifecycle and decoder fixtures while counting source acquisitions. */
    private fun renderCrossChatBindingFixture(
        fixture: Fixture,
        source: ConversationController,
        mediaType: String,
        acquisitions: java.util.concurrent.atomic.AtomicInteger,
        validSource: () -> Boolean,
    ) {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        val bytes = rendererFixturePngBytes()
        val lifecycle = ViewerPageTestLifecycleOwner()
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                    FullScreenMediaViewer(
                        controller = fixture.controller,
                        appState = fixture.appState,
                        pages =
                            listOf(
                                MediaViewerPage(
                                    DIRECT_MESSAGE_ID,
                                    0,
                                    reference(0, mediaType),
                                    false,
                                    SENDER_ID,
                                    1uL,
                                    groupIdHex = source.group.groupIdHex,
                                ),
                            ),
                        startIndex = 0,
                        onDismiss = {},
                        // Matches GlobalAttachmentViewerHost's missing-controller fallback.
                        pageController = { if (validSource()) source else fixture.controller },
                        imageBytes = {
                            acquisitions.incrementAndGet()
                            bytes
                        },
                        videoFileResolver = { _, _, _, _, _, _, _ ->
                            acquisitions.incrementAndGet()
                            throw java.io.IOException("No video fixture file")
                        },
                    )
                }
            }
        }
    }

    /**
     * Encodes a small static source for the positive image path without involving attachment download or network
     * state.
     */
    private fun rendererFixturePngBytes(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(20, 10, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io
            .ByteArrayOutputStream()
            .also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }

    /** Clearing a foreign-chat controller retires an export while its viewer and immutable binding still exist. */
    @Test
    fun crossChatExportRejectsItsClearedSourceController() {
        val fixture = fixture(DIRECT_MESSAGE_ID)
        val account = checkNotNull(fixture.controller.boundAccountRef)
        val owner =
            ConversationMediaViewerOwner(
                account,
                fixture.controller.group.groupIdHex,
                fixture.appState.runtimeGeneration,
            )
        val gate = MediaViewerActionGate(owner)
        val source = ConversationController(fixture.appState, group().copy(groupIdHex = "ab".repeat(32)))
        val otherAccountSource =
            ConversationController(fixture.appState, source.group, accountRefOverride = "other-account")
        val selected =
            MediaViewerPage(
                DIRECT_MESSAGE_ID,
                0,
                reference(0, "image/png"),
                false,
                SENDER_ID,
                1uL,
                groupIdHex = source.group.groupIdHex.uppercase(),
            )
        try {
            gate.currentPage = selected
            assertEquals(true, gate.sourceOwnerIsCurrent(selected, owner, source))
            assertEquals(false, gate.sourceOwnerIsCurrent(selected, owner, fixture.controller))
            assertEquals(false, gate.sourceOwnerIsCurrent(selected, owner, otherAccountSource))
            assertEquals(
                false,
                gate.sourceOwnerIsCurrent(
                    selected,
                    owner.copy(runtimeGeneration = owner.runtimeGeneration + 1),
                    source,
                ),
            )
            gate.currentPage = selected.copy(messageIdHex = "next-page")
            assertEquals(true, gate.sourceOwnerIsCurrent(selected, owner, source))
            source.onCleared()
            assertEquals(account, source.boundAccountRef)
            assertEquals(true, gate.ownerIsCurrent(owner))
            assertEquals(false, gate.sourceOwnerIsCurrent(selected, owner, source))
        } finally {
            source.onCleared()
            otherAccountSource.onCleared()
            gate.close()
        }
    }

    /** Account teardown retires a foreign-chat export before viewer disposal or a binding change. */
    @Test
    fun crossChatExportRejectsItsSourceAccountTeardown() =
        runBlocking {
            val fixture = fixture(DIRECT_MESSAGE_ID)
            val account = checkNotNull(fixture.controller.boundAccountRef)
            val owner =
                ConversationMediaViewerOwner(
                    account,
                    fixture.controller.group.groupIdHex,
                    fixture.appState.runtimeGeneration,
                )
            val gate = MediaViewerActionGate(owner)
            val source = ConversationController(fixture.appState, group().copy(groupIdHex = "ab".repeat(32)))
            val selected =
                MediaViewerPage(
                    DIRECT_MESSAGE_ID,
                    0,
                    reference(0),
                    false,
                    SENDER_ID,
                    1uL,
                    groupIdHex = source.group.groupIdHex,
                )
            try {
                assertEquals(true, gate.sourceOwnerIsCurrent(selected, owner, source))
                source.closeLiveSubscriptionsForAccountTeardown(account)
                assertEquals(account, source.boundAccountRef)
                assertEquals(true, gate.ownerIsCurrent(owner))
                assertEquals(false, gate.sourceOwnerIsCurrent(selected, owner, source))
            } finally {
                source.onCleared()
                gate.close()
            }
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
            if (source == 0) {
                composeRule.runOnIdle { publicImage = true }
                composeRule.awaitViewerImageWorkIdle()
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissed) }
    }

    /**
     * Tracks drawable identity and decode calls through healthy playback and two confirmed worker-loss
     * notifications.
     */
    @Test
    fun aHealthyAnimationKeepsItsClockAndOnlyOneConfirmedDeathAutomaticallyReplacesIt() {
        val drawables = java.util.concurrent.CopyOnWriteArrayList<ViewerTestAnimation>()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        var visible by mutableStateOf(true)
        renderAnimationFixture(drawables, calls) { visible }
        composeRule.waitUntil(10_000) { calls.get() == 1 }
        composeRule.awaitViewerImageWorkIdle()
        val first = drawables[0]
        composeRule.runOnIdle { visible = false }
        composeRule.runOnIdle { visible = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, calls.get())
            first.terminal.complete(AnimationTermination.WorkerLostUnknown)
        }
        composeRule.waitUntil(10_000) { calls.get() == 2 }
        composeRule.awaitViewerImageWorkIdle()
        composeRule.runOnIdle { drawables[1].terminal.complete(AnimationTermination.WorkerLostUnknown) }
        composeRule.onNodeWithTag("media.viewer.retry").assertExists()
        composeRule.runOnIdle { assertEquals(2, calls.get()) }
        composeRule.onNodeWithTag("media.viewer.retry").performClick()
        composeRule.waitUntil(10_000) { calls.get() == 3 }
        composeRule.awaitViewerImageWorkIdle()
    }

    /**
     * Injects a protocol termination and requires an explicit retry instead of spending automatic worker-death
     * recovery.
     */
    @Test
    fun protocolLossNeedsAnExplicitRetryAndDoesNotInventADeathProof() {
        val drawables = java.util.concurrent.CopyOnWriteArrayList<ViewerTestAnimation>()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        renderAnimationFixture(drawables, calls) { true }
        composeRule.waitUntil(10_000) { calls.get() == 1 }
        composeRule.awaitViewerImageWorkIdle()
        composeRule.runOnIdle { drawables[0].terminal.complete(AnimationTermination.ProtocolRejected) }
        composeRule.onNodeWithTag("media.viewer.retry").assertExists()
        composeRule.runOnIdle { assertEquals(1, calls.get()) }
        composeRule.onNodeWithTag("media.viewer.retry").performClick()
        composeRule.waitUntil(10_000) { calls.get() == 2 }
        composeRule.awaitViewerImageWorkIdle()
    }

    /**
     * Moves an explicit lifecycle through foreground and background while tracking byte reads and retained
     * animation identity.
     */
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
        composeRule.awaitViewerImageWorkIdle()
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
        composeRule.awaitViewerImageWorkIdle()
        composeRule.runOnIdle {
            assertEquals(1, calls.get())
            assertEquals(1, drawables.size)
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
                /**
                 * Creates a tracked drawable for each admitted animation decode so replacement and cleanup are
                 * observable.
                 */
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
                    sticker = null,
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

    /**
     * Drives the shared viewport through zoom and fit reset, checking the same controls for both image entry
     * points.
     */
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
                    context.getString(R.string.media_viewer_image_scale, 1600),
                ),
            )
        val fitAction =
            composeRule
                .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == fitLabel }
        composeRule.runOnIdle { check(fitAction.action()) }
        composeRule
            .onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.media_viewer_image_scale, 100),
                ),
            )
    }
}
