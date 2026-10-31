package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.LocalShellPlaybackHost
import dev.ipf.whitenoise.android.ui.conversation.ShellPlaybackHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import androidx.media3.ui.R as Media3R

/** One viewer session: the clips it pages through, the page it opens on and whether the shell transport shows. */
internal data class EdgeClipScenario(
    val clips: List<EdgeClip>,
    val startIndex: Int = 0,
    val shellTransport: Boolean = false,
)

/**
 * Screen-space layout of the open viewer, measured from its real Compose nodes and Media3 views. [safe] is the
 * window less the insets the viewer window was given, [video] is Media3's aspect-fitted content frame and
 * [controls] holds only the Media3 controls currently shown.
 */
internal data class ViewerGeometry(
    val window: Rect,
    val safe: Rect,
    val body: Rect,
    val topChrome: Rect?,
    val bottomChrome: Rect?,
    val transport: Rect?,
    val video: Rect,
    val controllerVisible: Boolean,
    val minimalControlsVisible: Boolean,
    val controllerContentHeight: Int,
    val fullControllerHeight: Int,
    val controls: Map<String, Rect>,
)

/**
 * Hosts the production full-screen viewer — FullScreenMediaViewer, PlaybackDialog, MediaViewerFrame, the pager and
 * VideoViewerPage's own Player and viewer PlayerView — over local edge-marked clips. It needs no account, network
 * or MDK runtime: clips materialize into this test's own cache entries and app state uses private preferences.
 */
internal class EdgeClipViewerFixture(
    private val rule: ComposeTestRule,
    private val context: Context,
    private val beforeMaterialize: suspend () -> Unit = {},
    private val onPlayerCreated: (Player) -> Unit = {},
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val viewerState = localAppState(accounts = emptyList())
    private val shellState = localAppState(accounts = listOf(shellAccount()))
    private val controller = ConversationController(appState = viewerState, initialGroup = edgeClipGroup())
    private val shellHost = ShellPlaybackHost(shellState) {}
    private val clipBytes = EdgeClip.entries.associateWith { it.readVerifiedBytes() }
    private val renderedPlayers = Collections.synchronizedSet(mutableSetOf<Player>())
    private var scenario by mutableStateOf<EdgeClipScenario?>(null)
    private var sourceEpoch by mutableStateOf(1uL)
    private var shellTransportShown = false

    @Volatile
    private var videoResolutionStage = "not-started"

    /** Whether the private clip has been published, even while background composition cannot create a player. */
    val materializationComplete: Boolean
        get() = videoResolutionStage == "completed"

    /** Attachment index of the page the viewer last reported as current, or -1 before any page settles. */
    @Volatile
    var currentAttachment = -1
        private set

    /** Bars and cutout dispatched to the viewer window before every measurement, or null for the device's own. */
    var simulatedInsets: SimulatedInsets? = null

    /** Renders the active scenario's viewer exactly as a conversation would, under the shell host when requested. */
    @Composable
    @Suppress("FunctionNaming")
    fun Content() {
        val active = scenario ?: return
        key(active) {
            val pages =
                remember(sourceEpoch) {
                    active.clips.map { clip ->
                        clip.page().copy(reference = clip.reference().copy(sourceEpoch = sourceEpoch))
                    }
                }
            CompositionLocalProvider(LocalShellPlaybackHost provides shellHost.takeIf { active.shellTransport }) {
                FullScreenMediaViewer(
                    controller = controller,
                    appState = viewerState,
                    pages = pages,
                    startIndex = active.startIndex,
                    onDismiss = { scenario = null },
                    onCurrentPageChange = { currentAttachment = it.attachmentIndex },
                    onVideoPlayerChanged = ::trackFirstFrame,
                    videoFileResolver = { resolverContext, _, messageId, index, reference, _, _ ->
                        // Publish on the I/O worker even while the stopped viewer cannot resume composition.
                        withContext(Dispatchers.IO) {
                            videoResolutionStage = "materializing"
                            try {
                                beforeMaterialize()
                                materializeVideoAttachment(resolverContext, messageId, index, reference) {
                                    clipBytes.getValue(EdgeClip.entries[index])
                                }.also { videoResolutionStage = "completed" }
                            } catch (failure: Throwable) {
                                videoResolutionStage = "failed:${failure.javaClass.simpleName}"
                                throw failure
                            }
                        }
                    },
                )
            }
        }
    }

    /** Begins opening a page without waiting for file resolution, for background-completion probes. */
    fun beginOpen(next: EdgeClipScenario) {
        rule.runOnIdle {
            scenario = null
            currentAttachment = -1
            videoResolutionStage = "on-open"
        }
        rule.runOnIdle { scenario = next }
    }

    /** Opens [next], waits for its first decoded frame, then pauses so the frame and the controls hold still. */
    fun open(next: EdgeClipScenario): ViewerGeometry {
        beginOpen(next)
        awaitFirstFrame()
        val view = requirePlayerView()
        onMain { requireNotNull(view.player) { "The current page has no player" }.pause() }
        awaitControllerAndChrome(visible = true)
        return geometry()
    }

    /** Waits for real decoding without altering play intent, including a deliberately paused initial frame. */
    fun awaitFirstFrame() {
        try {
            awaitCondition("the first decoded frame") {
                currentPlayerView()?.let { view -> onMain { view.player in renderedPlayers } } == true
            }
        } catch (failure: AssertionError) {
            logFirstFrameTimeout()
            throw failure
        }
    }

    /** Records bounded fixture stages without allowing diagnostics to replace the original assertion. */
    private fun logFirstFrameTimeout() {
        runCatching {
            Log.e(
                "EdgeClipViewerFixture",
                "first_frame_timeout resolution=$videoResolutionStage page=$currentAttachment",
            )
        }
    }

    /** Dismisses the viewer and waits until its dialog and players are gone. */
    fun close() {
        rule.runOnIdle { scenario = null }
        awaitCondition("the viewer to close") { dialogRoot() == null }
    }

    /** The current page's player, read on the main thread by [read]. */
    fun <T> readPlayer(read: (Player) -> T): T {
        val view = requirePlayerView()
        return onMain { read(requireNotNull(view.player) { "The current page has no player" }) }
    }

    /** Identity of the current page's player, for asserting a gesture did not replace it. */
    fun currentPlayer(): Player = readPlayer { it }

    /** Publishes identical bytes at a new epoch's path, then refreshes the still-mounted page descriptor. */
    fun replaceCurrentPlaybackFile() {
        val clip = EdgeClip.entries[currentAttachment]
        val nextEpoch = sourceEpoch + 1uL
        runBlocking(Dispatchers.IO) {
            materializeVideoAttachment(
                context,
                MESSAGE_ID,
                clip.ordinal,
                clip.reference().copy(sourceEpoch = nextEpoch),
            ) {
                clipBytes.getValue(clip)
            }
        }
        rule.runOnIdle { sourceEpoch = nextEpoch }
    }

    /** Measures the open viewer, re-dispatching [simulatedInsets] first so real insets cannot replace them. */
    fun geometry(): ViewerGeometry {
        simulatedInsets?.let(::dispatchSimulatedInsets)
        rule.waitForIdle()
        val root = requireNotNull(dialogRoot()) { "The viewer is not open" }
        val body = nodeRect(MEDIA_VIEWER_CONTENT_TAG) ?: error("The viewer has no media body")
        val topChrome = nodeRect(MEDIA_VIEWER_TOP_CHROME_TAG)
        val bottomChrome = nodeRect(MEDIA_VIEWER_BOTTOM_CHROME_TAG)
        val transport = nodeRect(SHELL_TRANSPORT_TAG)
        return onMain {
            val window = root.screenRect()
            val view = playerViewIn(root, body) ?: error("No PlayerView fills the media body $body")
            val controller = requireNotNull(view.findViewById<View>(Media3R.id.exo_controller))
            val center = requireNotNull(view.findViewById<View>(Media3R.id.exo_center_controls))
            val bottom = requireNotNull(view.findViewById<View>(Media3R.id.exo_bottom_bar))
            ViewerGeometry(
                window = window,
                safe = simulatedInsets?.safeArea(window) ?: window.insetBy(realSafeInsets(root)),
                body = body,
                topChrome = topChrome,
                bottomChrome = bottomChrome,
                transport = transport,
                video = view.findViewById<View>(Media3R.id.exo_content_frame).screenRect(),
                controllerVisible = view.isControllerFullyVisible,
                minimalControlsVisible =
                    view.findViewById<View>(Media3R.id.exo_minimal_controls)?.visibility == View.VISIBLE,
                controllerContentHeight = controller.height - controller.paddingTop - controller.paddingBottom,
                fullControllerHeight =
                    center.heightWithMargins() - center.paddingTop - center.paddingBottom +
                        2 * bottom.heightWithMargins(),
                controls = shownControls(view),
            )
        }
    }

    /** Taps the picture well away from Media3's centre and bottom controls. */
    fun tapPicture(geometry: ViewerGeometry) {
        val point = geometry.body.pointAt(TAP_X_FRACTION, PICTURE_Y_FRACTION)
        injectTouchPath(point, point, steps = 1)
        rule.waitForIdle()
    }

    /** Drags horizontally across the picture, right to left, above Media3's controls. */
    fun swipePicture(geometry: ViewerGeometry) {
        injectTouchPath(
            from = geometry.body.pointAt(SWIPE_START_FRACTION, PICTURE_Y_FRACTION),
            to = geometry.body.pointAt(SWIPE_END_FRACTION, PICTURE_Y_FRACTION),
            steps = SWIPE_STEPS,
        )
        rule.waitForIdle()
    }

    /** Drags the real Media3 time bar along its centre line from near its start to [fraction] of its width. */
    fun dragTimeBar(
        geometry: ViewerGeometry,
        fraction: Float,
    ) {
        val bar = requireNotNull(geometry.controls[TIME_BAR]) { "The time bar is not shown" }
        injectTouchPath(
            from = bar.pointAt(TIME_BAR_DRAG_START_FRACTION, CENTRE_FRACTION),
            to = bar.pointAt(fraction, CENTRE_FRACTION),
            steps = TIME_BAR_DRAG_STEPS,
        )
        rule.waitForIdle()
    }

    /** Waits until Media3's controller and both viewer bars agree on [visible]. */
    fun awaitControllerAndChrome(visible: Boolean) {
        awaitCondition("Media3 controls and viewer chrome visible=$visible") {
            val controller = currentPlayerView()?.let { view -> onMain { view.isControllerFullyVisible } }
            controller == visible &&
                (nodeRect(MEDIA_VIEWER_TOP_CHROME_TAG) != null) == visible &&
                (nodeRect(MEDIA_VIEWER_BOTTOM_CHROME_TAG) != null) == visible
        }
    }

    /** Waits until the viewer reports [attachmentIndex] as its settled page. */
    fun awaitAttachment(attachmentIndex: Int) {
        awaitCondition("page $attachmentIndex to settle") { currentAttachment == attachmentIndex }
    }

    /**
     * Publishes a ready voice session for a local account, so PlaybackDialog shows the shell transport strip above the
     * viewer and consumes its status-bar inset. No audio is loaded, and [release] stops the session.
     */
    fun showShellTransport() {
        shellTransportShown = true
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        state.value =
            VoicePlaybackController.PlaybackState(
                key = "edge-clip-voice",
                ready = true,
                durationMs = VOICE_DURATION_MS,
                sessionId = VOICE_SESSION_ID,
                source = VoicePlaybackSource(SHELL_ACCOUNT_REF, GROUP_ID, VOICE_MESSAGE_ID, "Edge clip voice"),
            )
    }

    /** Closes the viewer and removes only this fixture's cache entries, preferences and voice session. */
    fun release() {
        try {
            if (dialogRoot() != null) close()
            if (shellTransportShown) onMain { VoicePlaybackController.stop() }
        } finally {
            controller.onCleared()
            EdgeClip.entries.forEach { clip ->
                for (epoch in 1uL..sourceEpoch) {
                    videoAttachmentCacheFileForTests(
                        context,
                        MESSAGE_ID,
                        clip.ordinal,
                        clip.reference().copy(sourceEpoch = epoch),
                    ).delete()
                }
            }
            context.deleteSharedPreferences(PREFERENCES_NAME)
        }
    }

    /** Polls [condition] between idle frames and fails the test with [description] once [timeoutMs] passes. */
    fun awaitCondition(
        description: String,
        timeoutMs: Long = TIMEOUT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("Timed out waiting for $description")
            SystemClock.sleep(POLL_MS)
            rule.waitForIdle()
        }
    }

    /** Records each page player's first rendered frame, observed from before it is prepared. */
    private fun trackFirstFrame(player: Player?) {
        player?.let(onPlayerCreated)
        player?.addListener(
            object : Player.Listener {
                /** Marks [player] as having put a decoded frame on its surface. */
                override fun onRenderedFirstFrame() {
                    renderedPlayers += player
                }
            },
        )
    }

    /** Dispatches [insets] to the viewer window's Compose root in place of the device's insets. */
    private fun dispatchSimulatedInsets(insets: SimulatedInsets) {
        val root = dialogRoot() ?: return
        onMain {
            ViewCompat.dispatchApplyWindowInsets(root, insets.toCompat(Rect(0, 0, root.width, root.height)))
        }
    }

    /** The viewer dialog's Compose root view, or null when no viewer is shown. */
    private fun dialogRoot(): View? =
        rule
            .onAllNodesWithTag(MEDIA_VIEWER_CONTENT_TAG)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.let { (it.root as ViewRootForTest).view }

    /** Screen bounds of the single node tagged [tag], or null when it is absent. */
    private fun nodeRect(tag: String): Rect? =
        rule
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.screenRect()

    /** The current page's PlayerView, or null while the page is still loading. */
    private fun currentPlayerView(): PlayerView? {
        val root = dialogRoot()
        val body = nodeRect(MEDIA_VIEWER_CONTENT_TAG)
        return if (root == null || body == null) null else onMain { playerViewIn(root, body) }
    }

    /** The current page's PlayerView, failing the test when there is none. */
    private fun requirePlayerView(): PlayerView = requireNotNull(currentPlayerView()) { "No current PlayerView" }

    /** Builds app state that touches no account, network or shared preference outside this fixture. */
    private fun localAppState(accounts: List<AccountSummaryFfi>) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(DiscardedEdgeClipDrafts),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = "",
            preferences = preferences,
        )
}

/** Runs [block] on the main thread and returns its result, rethrowing its failure on the test thread. */
internal fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return requireNotNull(result).getOrThrow()
}

/** The shown PlayerView in [root]'s window whose centre lies inside the media [body] — the current page's. */
private fun playerViewIn(
    root: View,
    body: Rect,
): PlayerView? =
    root.rootView
        .descendantPlayerViews()
        .filter { it.isShown }
        .firstOrNull { view -> view.screenRect().let { body.contains(it.centerX(), it.centerY()) } }

/** Measured control height including the layout margins Media3 uses for its compact-height decision. */
private fun View.heightWithMargins(): Int {
    val margins = layoutParams as? ViewGroup.MarginLayoutParams
    return height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
}

/** Screen bounds of each Media3 control [view] currently shows. */
private fun shownControls(view: PlayerView): Map<String, Rect> =
    MEDIA3_CONTROLS
        .mapNotNull { (label, id) ->
            view.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }?.let { label to it.screenRect() }
        }.toMap()

/** The safe-drawing insets the platform reported to the viewer window. */
private fun realSafeInsets(root: View): Insets =
    ViewCompat
        .getRootWindowInsets(root)
        ?.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        ) ?: Insets.NONE

/** The point at fractions of this rectangle's width and height. */
private fun Rect.pointAt(
    xFraction: Float,
    yFraction: Float,
) = PointF(left + width() * xFraction, top + height() * yFraction)

/** One gallery page per clip, all from a single local message. */
private fun EdgeClip.page() =
    MediaViewerPage(
        messageIdHex = MESSAGE_ID,
        attachmentIndex = ordinal,
        reference = reference(),
        mine = false,
        sender = SENDER_ID,
        recordedAt = 1uL,
    )

/** A local signed-in account that owns the simulated voice session. */
private fun shellAccount() =
    AccountSummaryFfi(
        label = SHELL_ACCOUNT_REF,
        accountIdHex = "4a".repeat(32),
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

/** A media-capable local group for the production conversation controller. */
private fun edgeClipGroup() =
    AppGroupRecordFfi(
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Edge clip viewer",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "4c".repeat(32),
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

/** No-op draft boundary, so the viewer fixture never reads or writes composer drafts. */
private object DiscardedEdgeClipDrafts : DraftPersistence {
    /** Starts without drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores draft writes. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

internal const val TIME_BAR = "time bar"
internal const val PLAY_PAUSE = "play/pause"
internal const val ELAPSED_TIME = "elapsed time"
internal const val TOTAL_TIME = "total time"

/** Media3 controls whose bounds the tests check, by label. */
private val MEDIA3_CONTROLS =
    listOf(
        TIME_BAR to Media3R.id.exo_progress,
        PLAY_PAUSE to Media3R.id.exo_play_pause,
        ELAPSED_TIME to Media3R.id.exo_position,
        TOTAL_TIME to Media3R.id.exo_duration,
    )

/** Test tag on the shell's voice transport strip. */
private const val SHELL_TRANSPORT_TAG = "voice-transport"
private const val PREFERENCES_NAME = "media-viewer-video-controls-android-test"
private const val SHELL_ACCOUNT_REF = "edge-clip-shell"
private val MESSAGE_ID = "4d".repeat(32)
private val GROUP_ID = "4e".repeat(32)
private val SENDER_ID = "4f".repeat(32)
private val VOICE_MESSAGE_ID = "50".repeat(32)
private const val VOICE_SESSION_ID = 3_313L
private const val VOICE_DURATION_MS = 12_000
private const val TIMEOUT_MS = 20_000L
private const val POLL_MS = 50L
private const val TAP_X_FRACTION = 0.25f
private const val PICTURE_Y_FRACTION = 0.2f
private const val SWIPE_START_FRACTION = 0.85f
private const val SWIPE_END_FRACTION = 0.15f
private const val SWIPE_STEPS = 10
private const val CENTRE_FRACTION = 0.5f
private const val TIME_BAR_DRAG_START_FRACTION = 0.15f
private const val TIME_BAR_DRAG_STEPS = 24
