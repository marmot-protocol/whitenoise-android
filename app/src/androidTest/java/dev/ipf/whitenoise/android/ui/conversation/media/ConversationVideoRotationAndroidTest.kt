package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Device proof that the production Media3 player survives MainActivity-style configuration rotation. */
@RunWith(AndroidJUnit4::class)
class ConversationVideoRotationAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val targetContext = ApplicationProvider.getApplicationContext<Context>()
    private val reference = videoReference()
    private lateinit var cacheFile: File
    private lateinit var fixtureBytes: ByteArray
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var controller: ConversationController
    private lateinit var timelineState: LazyListState
    private lateinit var viewerSessionState: ConversationMediaViewerSessionState
    private var activePlayer: ExoPlayer? = null
    private var firstPlayer: ExoPlayer? = null
    private var activeSessionId: Long? = null
    private var selectedAttachment: ConversationMediaViewerAttachmentId? = null
    private var releaseCallbacks = 0
    private var dismissCallbacks = 0
    private var stateWhenInitialRotationWasRequested = Player.STATE_IDLE
    private var positionWhenInitialRotationWasRequested = Long.MAX_VALUE

    /** Pins portrait, creates the production controller, and installs a hash-verified local fixture. */
    @Before
    fun setUp() {
        requestOrientationAndAwait(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            expectedOrientation = Configuration.ORIENTATION_PORTRAIT,
        )
        appState = appState(targetContext)
        controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
            )
        cacheFile =
            videoAttachmentCacheFileForTests(
                context = targetContext,
                messageIdHex = MESSAGE_ID,
                attachmentIndex = ATTACHMENT_INDEX,
                reference = reference,
            )
        cacheFile.delete()
        fixtureBytes = readVerifiedVideoFixture()
    }

    /** Releases the requested orientation and removes only this test's cache artifact. */
    @After
    fun tearDown() {
        try {
            runOnMain { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        } finally {
            try {
                if (::controller.isInitialized) controller.onCleared()
            } finally {
                if (::cacheFile.isInitialized) cacheFile.delete()
            }
        }
    }

    /**
     * Uses the smallest production page seam to prove a real Media3 instance survives a
     * configuration change requested synchronously from its buffering callback.
     */
    @Test
    fun realPlayerSurvivesConfigurationRotationDuringInitialBuffering() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                VideoViewerPage(
                    controller = controller,
                    messageIdHex = MESSAGE_ID,
                    attachmentIndex = ATTACHMENT_INDEX,
                    reference = reference,
                    isCurrent = true,
                    mine = false,
                    onPlayerChanged = ::recordPlayerAndRotateDuringBuffering,
                    videoFileResolver = { context, _, messageId, index, resolvedReference, _, _ ->
                        materializeVideoAttachment(context, messageId, index, resolvedReference) { fixtureBytes }
                    },
                )
            }
        }

        awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)

        assertSame("initial configuration change replaced the Media3 player", firstPlayer, activePlayer)
        assertEquals("initial rotation disposed the player", 0, releaseCallbacks)
        assertEquals(Player.STATE_BUFFERING, stateWhenInitialRotationWasRequested)
        assertTrue(positionWhenInitialRotationWasRequested <= INITIAL_POSITION_TOLERANCE_MS)
    }

    /**
     * Opens through the production row/session/dialog chain, disposes the origin, then rotates
     * during active playback, pause, and end-of-stream without replacing session, page, or player.
     */
    @Test
    fun productionOwnerChainKeepsPlayerSessionPageAndPositionAcrossRotation() {
        val materializationGate = CompletableDeferred<Unit>()
        mountProductionOwnerChain(materializationGate)
        val opened = openViewerAfterOriginDisposal(materializationGate)

        assertPlayingStateSurvivesRotation(opened)
        val paused = assertPausedStateSurvivesRotation(opened.player)
        assertEndedStateSurvivesRotation(opened.player, paused.durationMs)
        assertPlaybackResumesAfterEndedRotation(opened.player)
        assertBackAndClosePreserveAnchor()
    }

    /** Mounts the real row, conversation session host, dialog, and Media3 subtree. */
    private fun mountProductionOwnerChain(materializationGate: CompletableDeferred<Unit>) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ProductionOwnerChain(materializationGate)
            }
        }
    }

    /** Renders the lazy source and viewer host under the same conversation owner. */
    @Suppress("FunctionNaming")
    @Composable
    private fun ProductionOwnerChain(materializationGate: CompletableDeferred<Unit>) {
        val sessionState =
            rememberConversationMediaViewerSessionState(
                ConversationMediaViewerOwner(ACCOUNT_REF, GROUP_ID, runtimeGeneration = 1),
            ).also { viewerSessionState = it }
        val listState = rememberLazyListState().also { timelineState = it }
        Box(Modifier.fillMaxSize()) {
            ProductionVideoTimeline(sessionState, listState, materializationGate)
            ProductionViewerHost(sessionState, materializationGate)
        }
    }

    /** Renders the production video bubble inside a long, disposable lazy timeline. */
    @Suppress("FunctionNaming")
    @Composable
    private fun ProductionVideoTimeline(
        sessionState: ConversationMediaViewerSessionState,
        listState: LazyListState,
        materializationGate: CompletableDeferred<Unit>,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(TIMELINE_TAG),
        ) {
            item(key = "video-origin") {
                MediaVideoBubble(
                    item = videoTimelineMessage(),
                    attachmentIndex = ATTACHMENT_INDEX,
                    reference = reference,
                    controller = controller,
                    appState = appState,
                    onOpenConversationMedia = { request -> sessionState.open(request) },
                    mine = false,
                    videoFileResolver = { context, _, messageId, index, resolvedReference, _, _ ->
                        materializationGate.await()
                        materializeVideoAttachment(context, messageId, index, resolvedReference) { fixtureBytes }
                    },
                )
            }
            items(LONG_TIMELINE_ROWS, key = { "timeline-row-$it" }) { index ->
                Text("Timeline row $index", Modifier.height(48.dp))
            }
        }
    }

    /** Renders the production dialog and records its session, page, and real player. */
    @Suppress("FunctionNaming")
    @Composable
    private fun ProductionViewerHost(
        sessionState: ConversationMediaViewerSessionState,
        materializationGate: CompletableDeferred<Unit>,
    ) {
        ConversationMediaViewerSessionHost(sessionState) { active ->
            activeSessionId = active.sessionId
            selectedAttachment = active.selectedAttachment
            val request = active.request
            ConversationMediaViewer(
                controller = controller,
                appState = appState,
                conversationVisualPages = emptyList(),
                messageIdHex = request.messageIdHex,
                attachments = request.attachments,
                tappedAttachmentIndex = request.tappedAttachmentIndex,
                sender = request.sender,
                recordedAt = request.recordedAt,
                mine = request.mine,
                onDismiss = {
                    if (sessionState.dismiss(active.sessionId)) dismissCallbacks++
                },
                selectedAttachment = active.selectedAttachment,
                onSelectedAttachmentChange = { selected ->
                    sessionState.selectPage(active.sessionId, selected)
                },
                onVideoPlayerChanged = ::recordPlayer,
                videoFileResolver = { context, _, messageId, index, resolvedReference, _, _ ->
                    materializationGate.await()
                    materializeVideoAttachment(context, messageId, index, resolvedReference) { fixtureBytes }
                },
            )
        }
    }

    /** Opens the dialog, disposes its source row, and releases delayed materialization. */
    private fun openViewerAfterOriginDisposal(materializationGate: CompletableDeferred<Unit>): OwnerChainOpenResult {
        composeRule
            .onNodeWithTag(videoAttachmentOpenTestTag(MESSAGE_ID, ATTACHMENT_INDEX), useUnmergedTree = true)
            .performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) { activeSessionId != null }
        val originalSession = requireNotNull(activeSessionId)
        assertEquals(
            ConversationMediaViewerAttachmentId(MESSAGE_ID, ATTACHMENT_INDEX),
            selectedAttachment,
        )
        runOnMain { timelineState.requestScrollToItem(LONG_TIMELINE_ROWS) }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(videoAttachmentOpenTestTag(MESSAGE_ID, ATTACHMENT_INDEX), useUnmergedTree = true)
            .assertDoesNotExist()
        assertFalse("originating row materialized bytes before disposal", cacheFile.exists())
        assertNull("pending viewer materialization must not create a player", activePlayer)
        materializationGate.complete(Unit)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) { activePlayer != null }

        val original = requireNotNull(firstPlayer)
        awaitPlayer("ready before owner-chain rotation") {
            it.playbackState == Player.STATE_READY && it.duration >= MIN_FIXTURE_DURATION_MS
        }
        return OwnerChainOpenResult(originalSession, original)
    }

    /** Verifies active playback, session, page, and position survive landscape rotation. */
    private fun assertPlayingStateSurvivesRotation(opened: OwnerChainOpenResult) {
        composeRule.runOnIdle {
            opened.player.seekTo(MID_PLAYBACK_SEEK_MS)
            opened.player.play()
        }
        val playingBeforeRotation =
            awaitSnapshot("playing before mid-stream rotation") {
                it.playWhenReady &&
                    it.playbackState == Player.STATE_READY &&
                    it.currentPosition >= MID_PLAYBACK_OBSERVATION_MS
            }
        requestOrientationAndAwait(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            expectedOrientation = Configuration.ORIENTATION_LANDSCAPE,
        )
        val playingAfterRotation = snapshot()
        assertSame(opened.player, playingAfterRotation.player)
        assertEquals("configuration change replaced the viewer session", opened.sessionId, activeSessionId)
        assertEquals(
            "configuration change moved the selected page",
            ConversationMediaViewerAttachmentId(MESSAGE_ID, ATTACHMENT_INDEX),
            selectedAttachment,
        )
        assertTrue("mid-stream rotation cleared play intent", playingAfterRotation.playWhenReady)
        assertTrue(
            "mid-stream rotation reset playback position",
            playingAfterRotation.positionMs >= playingBeforeRotation.positionMs - POSITION_TOLERANCE_MS,
        )
    }

    /** Verifies an exact paused seek remains paused and positioned across portrait rotation. */
    private fun assertPausedStateSurvivesRotation(original: ExoPlayer): PlayerSnapshot {
        composeRule.runOnIdle {
            original.pause()
            original.seekTo(PAUSED_SEEK_MS)
        }
        awaitSnapshot("paused at a stable position") {
            !it.playWhenReady && abs(it.currentPosition - PAUSED_SEEK_MS) <= POSITION_TOLERANCE_MS
        }
        requestOrientationAndAwait(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            expectedOrientation = Configuration.ORIENTATION_PORTRAIT,
        )
        val pausedAfterRotation = snapshot()
        assertSame(original, pausedAfterRotation.player)
        assertTrue("paused rotation restarted playback", !pausedAfterRotation.playWhenReady)
        assertTrue(
            "paused rotation moved the playback position",
            abs(pausedAfterRotation.positionMs - PAUSED_SEEK_MS) <= POSITION_TOLERANCE_MS,
        )
        return pausedAfterRotation
    }

    /** Verifies end-of-stream and its terminal position survive landscape rotation. */
    private fun assertEndedStateSurvivesRotation(
        original: ExoPlayer,
        durationMs: Long,
    ) {
        composeRule.runOnIdle {
            original.seekTo((durationMs - NEAR_END_OFFSET_MS).coerceAtLeast(0L))
            original.play()
        }
        awaitPlayer("end of deterministic video") { it.playbackState == Player.STATE_ENDED }
        requestOrientationAndAwait(
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            expectedOrientation = Configuration.ORIENTATION_LANDSCAPE,
        )
        val endedAfterRotation = snapshot()
        assertSame(original, endedAfterRotation.player)
        assertEquals("rotation changed the ended state", Player.STATE_ENDED, endedAfterRotation.playbackState)
        assertTrue(
            "ended rotation lost the terminal playback position",
            abs(endedAfterRotation.durationMs - endedAfterRotation.positionMs) <= POSITION_TOLERANCE_MS,
        )
    }

    /** Verifies the same ended player can resume without a rotation-time release. */
    private fun assertPlaybackResumesAfterEndedRotation(original: ExoPlayer) {
        composeRule.runOnIdle {
            original.seekTo(RESUME_SEEK_MS)
            original.play()
        }
        awaitSnapshot("playback resumes after rotating at end-of-stream") {
            it.playWhenReady &&
                it.playbackState == Player.STATE_READY &&
                it.currentPosition >= RESUME_OBSERVATION_MS
        }
        assertEquals("a rotation disposed the player", 0, releaseCallbacks)
        assertSame(original, activePlayer)
    }

    /** Exercises Back and Close independently and preserves both underlying anchors. */
    private fun assertBackAndClosePreserveAnchor() {
        assertDismissalReturnsToExactAnchor { Espresso.pressBack() }
        runOnMain { timelineState.requestScrollToItem(0) }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(videoAttachmentOpenTestTag(MESSAGE_ID, ATTACHMENT_INDEX), useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.close)).assertIsDisplayed()
        runOnMain { timelineState.requestScrollToItem(LONG_TIMELINE_ROWS) }
        composeRule.waitForIdle()
        assertDismissalReturnsToExactAnchor {
            composeRule.onNodeWithContentDescription(targetContext.getString(R.string.close)).performClick()
        }
        assertEquals("Back and Close must each dismiss exactly once", 2, dismissCallbacks)
    }

    /** Dismisses the production dialog once and preserves the underlying conversation viewport. */
    private fun assertDismissalReturnsToExactAnchor(dismiss: () -> Unit) {
        val anchorIndex = composeRule.runOnIdle { timelineState.firstVisibleItemIndex }
        val anchorOffset = composeRule.runOnIdle { timelineState.firstVisibleItemScrollOffset }
        val expectedDismissals = dismissCallbacks + 1

        dismiss()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) { viewerSessionState.active == null }
        composeRule.onNodeWithContentDescription(targetContext.getString(R.string.close)).assertDoesNotExist()

        assertEquals(expectedDismissals, dismissCallbacks)
        assertEquals(anchorIndex, composeRule.runOnIdle { timelineState.firstVisibleItemIndex })
        assertEquals(anchorOffset, composeRule.runOnIdle { timelineState.firstVisibleItemScrollOffset })
    }

    /** Records the player currently owned by the production viewer subtree. */
    private fun recordPlayer(player: ExoPlayer?) {
        if (player == null) {
            releaseCallbacks++
            activePlayer = null
            return
        }
        activePlayer = player
        if (firstPlayer == null) firstPlayer = player
    }

    /** Records one player and requests rotation from its first real buffering callback. */
    private fun recordPlayerAndRotateDuringBuffering(player: ExoPlayer?) {
        recordPlayer(player)
        if (player == null || firstPlayer !== player) return
        player.addListener(
            object : Player.Listener {
                /** Rotates once from the initial buffering state before the fixture becomes ready. */
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (
                        playbackState != Player.STATE_BUFFERING ||
                        stateWhenInitialRotationWasRequested != Player.STATE_IDLE
                    ) {
                        return
                    }
                    stateWhenInitialRotationWasRequested = playbackState
                    positionWhenInitialRotationWasRequested = player.currentPosition
                    composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            },
        )
    }

    /** Waits for [predicate] and returns the player captured in the matching snapshot. */
    private fun awaitPlayer(
        description: String,
        predicate: (ExoPlayer) -> Boolean,
    ): ExoPlayer = awaitSnapshot(description, predicate).player

    /** Polls Media3 state on the instrumentation main thread until [predicate] is satisfied. */
    private fun awaitSnapshot(
        description: String,
        predicate: (ExoPlayer) -> Boolean,
    ): PlayerSnapshot {
        val deadline = SystemClock.uptimeMillis() + PLAYER_TIMEOUT_MS
        var result: PlayerSnapshot? = null
        while (result == null && SystemClock.uptimeMillis() < deadline) {
            composeRule.runOnIdle {
                activePlayer?.takeIf(predicate)?.let { result = it.snapshot() }
            }
            if (result == null) SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue("Timed out waiting for $description", result != null)
        return requireNotNull(result)
    }

    /** Captures the currently owned player state without reading Media3 off its application thread. */
    private fun snapshot(): PlayerSnapshot {
        var result: PlayerSnapshot? = null
        composeRule.runOnIdle { result = requireNotNull(activePlayer).snapshot() }
        return requireNotNull(result)
    }

    /** Converts mutable Media3 state into one assertion-safe value captured on the main thread. */
    private fun ExoPlayer.snapshot() =
        PlayerSnapshot(
            player = this,
            playbackState = playbackState,
            playWhenReady = playWhenReady,
            positionMs = currentPosition,
            durationMs = duration,
        )

    /** Requests one orientation and blocks until resources report the corresponding configuration. */
    private fun requestOrientationAndAwait(
        requestedOrientation: Int,
        expectedOrientation: Int,
    ) {
        runOnMain { composeRule.activity.requestedOrientation = requestedOrientation }
        awaitOrientation(expectedOrientation)
    }

    /** Waits for the Activity's handled configuration change without recreating the composition owner. */
    private fun awaitOrientation(expectedOrientation: Int) {
        composeRule.waitUntil(ORIENTATION_TIMEOUT_MS) {
            composeRule.activity.resources.configuration.orientation == expectedOrientation
        }
    }

    /** Executes player or Activity mutation on the instrumentation main thread. */
    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /** Decodes and hash-verifies AndroidX Media's Apache-2.0 video fixture. */
    private fun readVerifiedVideoFixture(): ByteArray {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val encoded =
            instrumentation.context.assets
                .open(VIDEO_FIXTURE_ASSET)
                .bufferedReader()
                .use { it.readText() }
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        assertEquals(VIDEO_FIXTURE_SHA256, decoded.sha256Hex())
        return decoded
    }

    /** Computes the lowercase SHA-256 used to reject corrupted or replaced fixture bytes. */
    private fun ByteArray.sha256Hex(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
            }

    /** Creates local-only app state without account or network bootstrap work. */
    private fun appState(context: Context) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "",
        )

    /** Creates the cache identity expected by the deterministic local MP4. */
    private fun videoReference() =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = "silent-black-10s.mp4",
            mediaType = "video/mp4",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = "320x180",
            thumbhash = null,
        )

    /** Creates the received kind-9 row that exposes the production inline-video affordance. */
    private fun videoTimelineMessage() =
        TimelineMessage(
            id = "msg:$MESSAGE_ID",
            record =
                AppMessageRecordFfi(
                    messageIdHex = MESSAGE_ID,
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
                    sourceEpoch = reference.sourceEpoch,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )

    /** Creates a media-capable local group for the production conversation controller. */
    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Video rotation test",
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

    private companion object {
        const val ACCOUNT_REF = "personal"
        val MESSAGE_ID = "47".repeat(32)
        val GROUP_ID = "48".repeat(32)
        val SENDER_ID = "49".repeat(32)
        const val ATTACHMENT_INDEX = 0
        const val LONG_TIMELINE_ROWS = 80
        const val TIMELINE_TAG = "video-rotation-conversation-timeline"
        const val VIDEO_FIXTURE_ASSET = "silent-black-10s.mp4.b64"
        const val VIDEO_FIXTURE_SHA256 = "83fbcd994ece32535285a0ea6505c681cb96c471736308316eabda90cced9f51"
        const val MIN_FIXTURE_DURATION_MS = 9_000L
        const val INITIAL_POSITION_TOLERANCE_MS = 250L
        const val MID_PLAYBACK_SEEK_MS = 2_000L
        const val MID_PLAYBACK_OBSERVATION_MS = 2_250L
        const val PAUSED_SEEK_MS = 5_000L
        const val NEAR_END_OFFSET_MS = 350L
        const val RESUME_SEEK_MS = 1_000L
        const val RESUME_OBSERVATION_MS = 1_250L
        const val POSITION_TOLERANCE_MS = 750L
        const val POLL_INTERVAL_MS = 50L
        const val PLAYER_TIMEOUT_MS = 15_000L
        const val ORIENTATION_TIMEOUT_MS = 15_000L
    }
}

/** Main-thread snapshot of the exact Media3 instance and state used by rotation assertions. */
private data class PlayerSnapshot(
    val player: ExoPlayer,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/** Stable viewer generation and player captured after delayed materialization completes. */
private data class OwnerChainOpenResult(
    val sessionId: Long,
    val player: ExoPlayer,
)

/** No-op draft boundary that keeps the device fixture local and independent of account storage. */
private object EmptyDraftPersistence : DraftPersistence {
    /** Starts the device test without composer drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores unrelated lifecycle draft writes during playback assertions. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
