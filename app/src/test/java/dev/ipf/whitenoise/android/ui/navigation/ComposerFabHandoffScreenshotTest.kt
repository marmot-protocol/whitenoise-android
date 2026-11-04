package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** Temporal pixels from the production shell host and actual composer/FAB; no native conversation is fabricated. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h500dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerFabHandoffScreenshotTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    @get:Rule val visualErrors = ErrorCollector()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale: String? = null
    private var destination by mutableStateOf(ComposerFabEndpoint.Composer)
    private var committedDestination = ComposerFabEndpoint.Composer
    private var draft = TextFieldValue(PRIVATE_DRAFT, selection = TextRange(4))
    private var handoff: MeasuredControlHandoff? = null
    private var preCommitContentAlpha: Float? = null

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

    private fun releasePreviewVoiceController() {
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

    /** Enabled system motion makes temporal pixels independent of the runner's animator default. */
    @Before
    fun enableMotion() {
        previousScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restores the clock and setting even when one of the independently retained baselines is missing. */
    @After
    fun restoreMotion() {
        rule.mainClock.autoAdvance = true
        try {
            releasePreviewVoiceController()
        } finally {
            Settings.Global.putString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
        }
    }

    /** Both directions retain one real endpoint and a privacy-safe container morph in light mode. */
    @Test
    fun composerRoundTripLight() = captureRoundTrip("light", dark = false)

    /** The measured handoff adapts to a narrow and short dark window. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h360dp-mdpi")
    fun composerRoundTripNarrowDark() = captureRoundTrip("narrow_dark", dark = true)

    /** The real large RTL composer and AMOLED FAB remain readable during the handoff. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h360dp-mdpi")
    fun composerRoundTripLargeRtlAmoled() = captureRoundTrip("large_rtl_amoled", dark = true, largeRtl = true)

    /** Uses the actual MainShell endpoints and committed paused-clock frames when reversing content. */
    @Test
    fun contentFadePreservesCurrentOpacityOnReverse() {
        draft = TextFieldValue()
        createPreviewVoiceController(context)
        destination = ComposerFabEndpoint.NewMessage
        renderEndpoints(dark = false)
        val motion = requireNotNull(handoff)
        val source = rule.runOnIdle { requireNotNull(motion.captureOrigin()) }
        commitEndpoint(ComposerFabEndpoint.Composer)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithContentDescription(context.getString(R.string.voice_message_record)).assertExists()
        val emptyEditor = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config
        assertEquals("", emptyEditor[SemanticsProperties.EditableText].text)
        assertEquals(TextRange.Zero, emptyEditor[SemanticsProperties.TextSelectionRange])
        rule.runOnIdle {
            assertTrue(motion.active)
            assertEquals(source, motion.renderedBounds)
            assertEquals(0f, motion.renderedContentAlpha, 0f)
        }
        capture("voice_note_entry_first_light")
        rule.mainClock.advanceTimeBy(80)
        val midpoint = rule.runOnIdle { motion.renderedContentAlpha }
        assertTrue(midpoint > 0f && midpoint < 1f)
        rule.onNodeWithContentDescription(context.getString(R.string.voice_message_record)).assertExists()
        capture("voice_note_entry_middle_light")
        commitEndpoint(ComposerFabEndpoint.NewMessage)
        assertCurrentEndpoint(ComposerFabEndpoint.NewMessage)
        rule.runOnIdle {
            // The commit advances one frame; compare with the source sampled in that same
            // composition, not the prior frame's still-running animation sample.
            assertEquals(requireNotNull(preCommitContentAlpha), motion.renderedContentAlpha, 0.001f)
        }
        capture("voice_note_reverse_first_light")
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { assertTrue(motion.renderedContentAlpha < requireNotNull(preCommitContentAlpha)) }
        rule.runOnIdle { motion.finish() }
        rule.runOnIdle { assertEquals(1f, motion.renderedContentAlpha, 0f) }
    }

    private fun commitEndpoint(target: ComposerFabEndpoint) {
        val start = rule.mainClock.currentTime
        rule.runOnIdle {
            destination = target
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(target, committedDestination)
        assertEquals(target, requireNotNull(handoff).destination)
        assertEquals(start + 16L, rule.mainClock.currentTime)
    }

    /** Reuses MainShell's measured host and registration locals instead of drawing test-owned endpoint shapes. */
    private fun renderEndpoints(
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        val appState = ChatRowPortFixtures.state(context)
        rule.setContent {
            CompositionLocalProvider(
                LocalCursorBlinkEnabled provides false,
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    Surface(Modifier.fillMaxSize().testTag(TAG)) {
                        preCommitContentAlpha = remember(destination) { handoff?.renderedContentAlpha }
                        MainShellNoticeLayout(
                            notice = null,
                            dictationController = appState.conversationDictation,
                            dictationComposerRoute =
                                ConversationDictationComposerRoute(null, null, null, ACCOUNT, true, true),
                            appLockScreenVisible = false,
                            handoffOwner = ACCOUNT,
                            handoffDestination = destination,
                        ) {
                            handoff = LocalComposerFabHandoff.current
                            SideEffect { committedDestination = destination }
                            val route =
                                updateTransition(
                                    if (destination == ComposerFabEndpoint.Composer) "chat" else null,
                                    label = "fixture route",
                                )
                            ConversationRouteAnimatedContent(
                                transition = route,
                                routeForwardDirection = 1,
                                suppressMotion = false,
                                contentKey = { it ?: "list" },
                            ) { chat ->
                                Endpoints(
                                    if (chat != null) ComposerFabEndpoint.Composer else ComposerFabEndpoint.NewMessage,
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun captureRoundTrip(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        renderEndpoints(dark, largeRtl)
        captureTransition(variant, ComposerFabEndpoint.NewMessage)
        captureTransition(variant, ComposerFabEndpoint.Composer)
    }

    /** Only the current endpoint is mounted; the composer retains its own text and selection. */
    @Composable
    private fun Endpoints(endpoint: ComposerFabEndpoint) {
        Box(Modifier.fillMaxSize()) {
            if (endpoint == ComposerFabEndpoint.Composer) {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> error("The temporal fixture must never send") },
                    voiceRecordingController = previewVoice,
                    initialDraft = draft,
                    onDraftChange = { draftValue, _ -> draft = draftValue },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .testTag(COMPOSER_TAG),
                )
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets.navigationBars,
                    floatingActionButton = { ChatsNewMessageFabContent(missingRelays = false) {} },
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding))
                }
            }
        }
    }

    /** Captures the first committed frame, measured interpolation and retired decoration on a paused clock. */
    private fun captureTransition(
        variant: String,
        target: ComposerFabEndpoint,
    ) {
        val source = rule.runOnIdle { requireNotNull(handoff?.captureOrigin()) }
        val sourceStroke = strokePixels(source)
        val start = rule.mainClock.currentTime
        rule.runOnIdle {
            destination = target
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(target, committedDestination)
        assertEquals(start + 16L, rule.mainClock.currentTime)
        val direction = if (target == ComposerFabEndpoint.NewMessage) "to_fab" else "to_composer"
        val end = assertCurrentEndpoint(target)
        assertEquals(
            "Native and bridge strokes must occupy the same pixels on the first frame",
            sourceStroke,
            strokePixels(source),
        )
        capture("${direction}_first_$variant")
        rule.mainClock.advanceTimeBy(80)
        rule.waitForIdle()
        assertEquals(start + 96L, rule.mainClock.currentTime)
        assertCurrentEndpoint(target)
        assertInterpolating(source, end)
        val middle = pixels()
        capture("${direction}_mid_$variant")
        // Placement admission and the first animation sample precede the 200 ms morph.
        // Keep first/midpoint clocks exact, then allow two presentation frames for final retirement.
        rule.mainClock.advanceTimeBy(176)
        rule.waitForIdle()
        assertEquals(start + 272L, rule.mainClock.currentTime)
        assertCurrentEndpoint(target)
        rule.runOnIdle { assertNull(handoff?.renderedBounds) }
        val settled = pixels()
        capture("${direction}_settled_$variant")
        assertFalse("The container morph must change actual pixels before retiring", middle.contentEquals(settled))
        assertEquals(TextFieldValue(PRIVATE_DRAFT, selection = TextRange(4)), draft)
    }

    /** Straight-edge pixels catch centered-versus-inset stroke pops without comparing live editor content. */
    private fun strokePixels(bounds: Rect): List<Int> {
        val root = rule.onNodeWithTag(TAG)
        val origin = root.fetchSemanticsNode().boundsInWindow.topLeft
        val image = root.captureToImage().toPixelMap()
        val x = (bounds.left - origin.x).toInt()
        val y = (bounds.center.y - origin.y).toInt()
        return (-1..1).map { image[x + it, y].toArgb() }
    }

    /** Private input is absent from the chat list, and the outgoing real endpoint is never duplicated. */
    private fun assertCurrentEndpoint(target: ComposerFabEndpoint): Rect {
        val composer = target == ComposerFabEndpoint.Composer
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(if (composer) 1 else 0)
        val fab = rule.onNodeWithContentDescription(context.getString(R.string.new_message))
        return if (composer) {
            fab.assertDoesNotExist()
            rule.onNodeWithText(PRIVATE_DRAFT, substring = true).assertExists()
            val editor = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config
            assertEquals(PRIVATE_DRAFT, editor[SemanticsProperties.EditableText].text)
            assertEquals(TextRange(4), editor[SemanticsProperties.TextSelectionRange])
            rule
                .onNodeWithTag(COMPOSER_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInWindow
        } else {
            // AnimatedContent may retain an empty measured placeholder, but never an editor or action.
            val outgoingComposer = hasTestTag(COMPOSER_TAG) or hasAnyAncestor(hasTestTag(COMPOSER_TAG))
            rule.onAllNodes(outgoingComposer and (hasSetTextAction() or hasClickAction())).assertCountEquals(0)
            rule.onNodeWithText(PRIVATE_DRAFT, substring = true).assertDoesNotExist()
            fab
                .assertIsDisplayed()
                .assertHasClickAction()
                .fetchSemanticsNode()
                .boundsInWindow
        }
    }

    /** The real measured state must be strictly between source and destination, not a static extra outline. */
    private fun assertInterpolating(
        source: Rect,
        target: Rect,
    ) {
        rule.runOnIdle {
            val middle = handoff?.renderedBounds
            assertNotNull("The measured handoff must be active at the captured midpoint", middle)
            requireNotNull(middle)
            assertTrue(middle.width > minOf(source.width, target.width))
            assertTrue(middle.width < maxOf(source.width, target.width))
            val horizontalRange = minOf(source.center.x, target.center.x)..maxOf(source.center.x, target.center.x)
            assertTrue(middle.center.x in horizontalRange)
        }
    }

    /** Preserves all phase actuals for independent review without accepting absent or different PNGs. */
    private fun capture(name: String) {
        visualErrors.checkSucceeds {
            rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_fab_motion_$name.png")
        }
    }

    /** Reads the production draw result, without substituting fixture-generated pixels. */
    private fun pixels(): IntArray {
        val image = rule.onNodeWithTag(TAG).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    private companion object {
        const val ACCOUNT = "temporal-composer-owner"
        const val PRIVATE_DRAFT = "Private unsent draft"
        const val TAG = "composer-fab-motion-viewport"
        const val COMPOSER_TAG = "temporal-composer"
    }
}
