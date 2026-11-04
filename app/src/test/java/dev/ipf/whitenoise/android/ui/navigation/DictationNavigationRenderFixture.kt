package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.conversation.compactTopClearanceFor
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_HANDLE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOuterVerticalPadding
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.ACCOUNT
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.COMPOSER_TAG
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.GROUP
import dev.ipf.whitenoise.android.ui.navigation.DictationNavigationFixtureValues.origin
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import java.io.File
import java.nio.file.Files
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

/** Owns one test instance's production-shell composition, scheduler and guarded voice fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class DictationNavigationRenderFixture {
    protected val transcriptPasteLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.paste_dictation_transcript)

    protected val scheduler = TestCoroutineScheduler()

    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    protected lateinit var renderedFixture: Fixture
    protected lateinit var renderedView: View
    protected var observedImeBottom = 0

    private var previewVoice: VoiceRecordingController? = null
    private var previewVoiceScope: CoroutineScope? = null
    private var previewVoiceDirectory: File? = null
    private var previewPermissionRequests = 0

    /** Creates presentation state only: every permission/start/completion path is guarded. */
    private fun createPreviewVoiceController(context: Context): VoiceRecordingController {
        check(previewVoice == null)
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "composer-motion-voice-").toFile()
        previewVoiceScope = scope
        previewVoiceDirectory = directory
        return VoiceRecordingController(
            context = context,
            outputDirectory = directory,
            scope = scope,
            onPermissionRequest = {
                previewPermissionRequests++
                false
            },
            onRecordingComplete = { _, _ -> error("Motion fixtures must never complete a recording") },
            onError = { error("Motion fixture recorder error: ${it.javaClass.simpleName}") },
            recorderFactory = { _, _, _ -> error("Motion fixtures must never start a recorder") },
            mainDispatcher = dispatcher,
            recorderDispatcher = dispatcher,
        ).also { previewVoice = it }
    }

    protected fun releasePreviewVoiceController() {
        try {
            previewVoice?.release()
            scheduler.runCurrent()
            assertEquals("Motion fixtures must not request microphone permission", 0, previewPermissionRequests)
        } finally {
            previewVoiceScope?.cancel()
            previewVoiceDirectory?.let { check(it.deleteRecursively()) }
            previewVoice = null
            previewVoiceScope = null
            previewVoiceDirectory = null
        }
    }

    /** Exercises the production ownership/layout, route animation, and actual composer controls. */
    protected fun render(
        dark: Boolean = false,
        largeRtl: Boolean = false,
        amoled: Boolean = false,
        compact: Boolean = false,
        withComposerMorph: Boolean = false,
    ): Fixture {
        val (fixture, voice) = prepareFixture(withComposerMorph)
        composeRule.setContent {
            observeImeInsets()
            val route = fixture.route
            val locked = fixture.locked
            SideEffect {
                fixture.committedRoute = route
                fixture.committedLocked = locked
            }
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        MainShellNoticeLayout(
                            notice = null,
                            dictationController = fixture.controller,
                            dictationComposerRoute = fixture.route,
                            appLockScreenVisible = fixture.locked,
                            handoffOwner = if (withComposerMorph) ACCOUNT else null,
                            handoffDestination =
                                when {
                                    !withComposerMorph || locked -> null
                                    route.selectedChatId == null -> ComposerFabEndpoint.NewMessage
                                    else -> ComposerFabEndpoint.Composer
                                },
                        ) { owner ->
                            val anchor = LocalFloatingDictationAnchor.current
                            val handoff = LocalComposerFabHandoff.current
                            SideEffect {
                                fixture.anchor = anchor
                                fixture.handoff = handoff
                            }
                            if (!fixture.locked) {
                                val transition =
                                    updateTransition(fixture.route.selectedChatId, label = "dictation route")
                                ConversationRouteAnimatedContent(
                                    transition = transition,
                                    routeForwardDirection = 1,
                                    suppressMotion = false,
                                    contentKey = { it ?: "chat-list" },
                                ) { chatId ->
                                    RenderRouteContents(fixture, owner, chatId, largeRtl, compact, voice)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return fixture
    }

    /** Paints the actual route-specific FAB/composer inside its existing animation. */
    @Composable
    private fun RenderRouteContents(
        fixture: Fixture,
        owner: ConversationDictationControlOwner,
        chatId: String?,
        largeRtl: Boolean,
        compact: Boolean,
        voice: VoiceRecordingController?,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (chatId == null) {
                Scaffold(
                    contentWindowInsets = WindowInsets.navigationBars,
                    floatingActionButton = { ChatsNewMessageFabContent(missingRelays = false) {} },
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding))
                }
            }
            if (chatId != null) {
                val group = if (chatId == origin.selectedChatId) GROUP else "other-group"
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> },
                    topInteractionClearance =
                        if (compact) {
                            compactTopClearanceFor(if (largeRtl) 2f else 1f)
                        } else {
                            64.dp
                        },
                    voiceRecordingController = voice,
                    dictationController = fixture.controller,
                    dictationAccountRef = ACCOUNT,
                    dictationGroupIdHex = group,
                    dictationControlsVisible =
                        owner == ConversationDictationControlOwner.Composer &&
                            fixture.route.selectedChatId == chatId,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .testTag(COMPOSER_TAG)
                            // Match the normal ComposerGate contract in ConversationBottomBar.
                            // This fixture does not exercise selection/search/notice chrome.
                            .floatingDictationAnchor(
                                topInset = ComposerOuterVerticalPadding,
                                followsComposerFab = true,
                            ),
                )
            }
        }
    }

    @Composable
    private fun observeImeInsets() {
        val view = LocalView.current
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        SideEffect {
            renderedView = view
            observedImeBottom = imeBottom
        }
    }

    protected class Fixture {
        var route by mutableStateOf(origin)
        var locked by mutableStateOf(false)
        var committedRoute = origin
        var committedLocked = false
        var anchor: FloatingDictationAnchor? = null
        var handoff: MeasuredControlHandoff? = null
        var checkedArtworkFrames = 0
        var gripTopOffset = 0f
        var incomingCapsuleLeft = 0f
        val platform = FakePlatform()
        var draft = TextFieldValue()
        val writes = mutableListOf<Pair<String, String>>()
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
                writeDraft = { account, group, _, value ->
                    writes += account to group
                    draft = value
                    0L
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )
    }

    protected class FakePlatform : ConversationDictationPlatform {
        var cancels = 0
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun createSession(listener: ConversationDictationRecognitionListener): RecognitionSession {
            this.listener = listener
            return object : RecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() {
                    cancels++
                }

                override fun destroy() = Unit
            }
        }
    }

    /** Starts the same guarded dictation fixture before its production-shell composition. */
    private fun prepareFixture(withComposerMorph: Boolean): Pair<Fixture, VoiceRecordingController?> {
        val fixture = Fixture()
        renderedFixture = fixture
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
        val voice =
            if (withComposerMorph) {
                createPreviewVoiceController(ApplicationProvider.getApplicationContext())
            } else {
                null
            }
        return fixture to voice
    }

    /** The painted bar follows the same frame's border, including an interrupted return. */
    protected fun assertGripFrame(
        bitmap: Bitmap,
        scene: String,
        index: Int,
    ) {
        if (renderedFixture.route.selectedChatId == null) return
        val motion = requireNotNull(renderedFixture.handoff)
        val target = requireNotNull(motion.targetBounds)
        val moving = motion.renderedBounds ?: target
        val grip =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("Grip must follow the moving border center", moving.center.x, grip.center.x, 0.5f)
        assertEquals(
            "Grip must follow the moving border top",
            moving.top + renderedFixture.gripTopOffset,
            grip.top,
            0.5f,
        )
        if (scene == "active_return" && index == 0) {
            assertEquals("Incoming grip uses the same zero-alpha first frame", 0f, motion.renderedContentAlpha, 0f)
            val left = (target.center.x - grip.width / 2).toInt()
            val right =
                minOf(
                    (target.center.x + grip.width / 2).toInt(),
                    renderedFixture.incomingCapsuleLeft.toInt() - 1,
                )
            val top = (target.top + renderedFixture.gripTopOffset).toInt()
            assertTrue("Fixture must expose the old detached-grip fragment", right > left)
            for (y in top until top + grip.height.toInt()) {
                for (x in left until right) {
                    assertEquals(
                        "No early detached grip may protrude beside the capsule",
                        0,
                        bitmap.getPixel(x, y) and 0x00ffffff,
                    )
                }
            }
        }
    }

    /** The AMOLED fixture makes a missing outline/glyph distinguishable from its black surface. */
    protected fun assertSettledArtwork(bitmap: Bitmap) {
        if (requireNotNull(renderedFixture.handoff).active) return
        renderedFixture.checkedArtworkFrames++
        val pen = renderedFixture.route.selectedChatId == null
        val bounds =
            if (pen) {
                composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
            } else {
                composeRule
                    .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            }

        fun visiblePixels(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ): Int =
            (top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)).sumOf { y ->
                (left.coerceAtLeast(0) until right.coerceAtMost(bitmap.width)).count { x ->
                    bitmap.getPixel(x, y) and 0x00ffffff != 0
                }
            }

        val center = bounds.center.x.toInt()
        val top = bounds.top.toInt()
        assertTrue(
            "Settled control outline must survive its retirement frame",
            visiblePixels(center - 8, top, center + 8, top + 3) > 0,
        )
        if (pen) {
            val middle = bounds.center.y.toInt()
            assertTrue(
                "Settled pen must survive its retirement frame",
                visiblePixels(center - 12, middle - 12, center + 12, middle + 12) > 8,
            )
        }
    }
}

internal object DictationNavigationFixtureValues {
    const val ACCOUNT = "account"
    const val GROUP = "group"
    const val COMPOSER_TAG = "navigation-origin-composer"
    val origin = ConversationDictationComposerRoute("chat", GROUP, "chat", ACCOUNT, true, true)
}
