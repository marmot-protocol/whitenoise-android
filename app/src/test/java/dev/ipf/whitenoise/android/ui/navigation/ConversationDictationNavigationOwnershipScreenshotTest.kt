package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.conversation.compactTopClearanceFor
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_DICTATION_COMPACT_ACTIONS_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_ACCESSIBILITY_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationDictationNavigationOwnershipScreenshotTest {
    private val transcriptPasteLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.paste_dictation_transcript)

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    private lateinit var renderedFixture: Fixture
    private lateinit var renderedView: View
    private var observedImeBottom = 0

    @Test
    fun persistentActionsAboveKeyboardLight() = captureKeyboardActions(dark = false, largeRtl = false)

    @Test
    fun persistentActionsAboveKeyboardLargeRtlDark() = captureKeyboardActions(dark = true, largeRtl = true)

    @Test
    fun keyboardAmoled() = captureKeyboardActions(dark = true, largeRtl = false, amoled = true)

    @Test
    fun returnedComposerAmoled() = captureNavigation(dark = true, largeRtl = false, returning = true, amoled = true)

    @Test
    fun departureAmoled() = captureNavigation(dark = true, largeRtl = false, returning = false, amoled = true)

    @Test
    fun settledChatListLight() = captureChatList(dark = false, amoled = false)

    @Test
    fun settledChatListDark() = captureChatList(dark = true, amoled = false)

    @Test
    fun settledChatListAmoled() = captureChatList(dark = true, amoled = true)

    private fun captureChatList(
        dark: Boolean,
        amoled: Boolean,
    ) {
        val fixture = render(dark = dark, amoled = amoled)
        composeRule.runOnIdle { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.waitForIdle()
        val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val pen = composeRule.onNodeWithContentDescription("New Message").fetchSemanticsNode().boundsInRoot
        assertTrue("Capsule must clear actual pen: capsule=$capsule pen=$pen", capsule.bottom <= pen.top - 10f)
        assertSingleActionSet()
        val theme =
            if (amoled) {
                "amoled"
            } else if (dark) {
                "dark"
            } else {
                "light"
            }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_chat_list_$theme.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h480dp-mdpi")
    fun compactExpandedOtherChatLight() = captureCompactExpanded(largeRtl = false)

    @Test
    @Config(qualifiers = "w360dp-h480dp-mdpi")
    fun compactExpandedOtherChatLargeRtlDark() = captureCompactExpanded(largeRtl = true)

    private fun captureCompactExpanded(largeRtl: Boolean) {
        val fixture = render(dark = largeRtl, largeRtl = largeRtl, compact = true)
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                renderedView.rootView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 280))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        composeRule.waitForIdle()
        val expand =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
        composeRule.runOnIdle { assertTrue(expand.action()) }
        composeRule.waitForIdle()

        fun capture(phase: String) {
            val capsule = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
            val composer = composeRule.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("Capsule clears status bar: $capsule", capsule.top >= 24f)
            assertTrue("Clearance: capsule=$capsule composer=$composer", capsule.bottom <= composer.top)
            assertTrue("Compact capsule keeps its native action height", capsule.height <= 48f)
            val variant = if (largeRtl) "large_font_rtl_dark" else "light"
            val snapshotName = "dictation_navigation_compact_${phase}_$variant.png"
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshotName")
        }
        capture("expanded")
        composeRule.runOnIdle { fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable) }
        composeRule.waitForIdle()
        capture("failure")
    }

    /** MainActivity is edge-to-edge with adjustResize; dispatch its IME model and settle navigation. */
    private fun captureKeyboardActions(
        dark: Boolean,
        largeRtl: Boolean,
        amoled: Boolean = false,
    ) {
        val fixture = render(dark, largeRtl, amoled)
        val capture = fixture.controller.state
        composeRule.runOnIdle {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(renderedView.rootView, insets)
        }
        composeRule.waitForIdle()
        assertEquals(300, observedImeBottom)
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val keyboardTop = rootBounds.bottom - observedImeBottom
        listOf("Cancel", transcriptPasteLabel, "Send").forEach { label ->
            val matcher = hasContentDescription(label) and hasAnyAncestor(hasTestTag(APP_DICTATION_CONTROL_TAG))
            composeRule.onAllNodes(matcher).assertCountEquals(1)
            val action = composeRule.onNode(matcher).assertIsDisplayed()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must be above the IME", bounds.bottom <= keyboardTop)
            assertTrue("$label must not clip above the root", bounds.top >= rootBounds.top)
            assertTrue("$label must retain visible area", bounds.width > 0f && bounds.height > 0f)
        }
        val stripBounds = composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).fetchSemanticsNode().boundsInRoot
        val inputBounds = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val inputVisible = inputBounds.height > 0f && inputBounds.top >= rootBounds.top
        assertTrue("Destination input must remain visible", inputVisible)
        val composerBounds = composeRule.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Capsule must clear the composer: capsule=$stripBounds composer=$composerBounds",
            composerBounds.top - stripBounds.bottom in 11f..13f,
        )
        assertTrue("Capsule must remain physically right aligned", stripBounds.right > rootBounds.right - 40f)
        assertTrue("Capsule must not become a full-width strip", stripBounds.width < rootBounds.width - 64f)
        assertSame(capture, fixture.controller.state)
        val variant =
            if (amoled) {
                "amoled"
            } else if (largeRtl) {
                "large_font_rtl_dark"
            } else {
                "light"
            }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_keyboard_$variant.png")
    }

    @Test
    fun departureFirstFrameLight() = captureNavigation(dark = false, largeRtl = false, returning = false)

    @Test
    fun departureFirstFrameLargeFontRtlDark() = captureNavigation(dark = true, largeRtl = true, returning = false)

    @Test
    fun returnedComposerLight() = captureNavigation(dark = false, largeRtl = false, returning = true)

    @Test
    fun returnedComposerLargeFontRtlDark() = captureNavigation(dark = true, largeRtl = true, returning = true)

    /** A navigation commit alone transfers the actions, even with a retained outgoing composer. */
    @Test
    fun leaveOriginShowsActionsOnFirstCommittedFrame() {
        val fixture = render()
        assertSingleActionSet()
        val capture = fixture.controller.state

        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }

        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertExists()
        assertSingleActionSet()
        assertSame(capture, fixture.controller.state)
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            assertSingleActionSet()
        }
    }

    /** Re-entering before outgoing disposal cannot leave a second root action set behind. */
    @Test
    fun returnTransfersOwnershipOnFirstCommittedFrame() {
        val fixture = render()
        val capture = fixture.controller.state
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()

        firstNavigationFrame { fixture.route = origin }

        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
        assertSingleActionSet()
        assertSame(capture, fixture.controller.state)
    }

    /** Covers same-group account switches and covered composers without touching capture state. */
    @Test
    fun accountAndCoveringRouteChangesDoNotWaitForRecognitionCallbacks() {
        val fixture = render()
        listOf(
            origin.copy(renderedAccountRef = "other-account"),
            origin.copy(navigationAccountStable = false),
            origin.copy(composerVisible = false),
        ).forEach { hiddenOrigin ->
            firstNavigationFrame { fixture.route = hiddenOrigin }
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
            assertSingleActionSet()
            firstNavigationFrame { fixture.route = origin }
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
            assertSingleActionSet()
        }
    }

    /** The lock boundary suppresses both the persistent strip and the protected content. */
    @Test
    fun lockNeverExposesEitherActionSet() {
        val fixture = render()
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null)
            fixture.locked = true
        }
        listOf(
            "Cancel",
            transcriptPasteLabel,
            ApplicationProvider.getApplicationContext<Context>().getString(R.string.paste_from_clipboard),
            "Send",
        ).forEach { label ->
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(0)
        }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
    }

    /** Shell actions keep the captured account/group even after a different destination is selected. */
    @Test
    fun pasteAfterNavigationWritesOnlyTheImmutableOrigin() {
        val fixture = render()
        firstNavigationFrame {
            fixture.route = origin.copy(selectedChatId = "other-chat", selectedGroupIdHex = "other-group")
        }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
        composeRule
            .onNode(
                hasContentDescription(transcriptPasteLabel) and
                    hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
            ).performClick()
        composeRule.runOnUiThread { fixture.platform.listener.onResult("dictated words") }
        assertEquals(listOf(ACCOUNT to GROUP), fixture.writes)
        assertEquals("dictated words", fixture.draft.text)
        assertEquals(ConversationDictationState.Idle, fixture.controller.state)
    }

    /** Actual transformed hit testing keeps Cancel owned until its pointer-up dispatch. */
    @Test
    fun midpointCancelUsesOneRealActionAndCancelsOnce() {
        val fixture = render()
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.mainClock.advanceTimeBy(80)
        assertSingleActionSet()
        composeRule
            .onNode(
                hasContentDescription("Cancel") and hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
            ).performTouchInput {
                down(center)
                up()
            }
        composeRule.runOnIdle {
            assertEquals(ConversationDictationState.Idle, fixture.controller.state)
            assertEquals(1, fixture.platform.cancels)
        }
    }

    /** First/intermediate/settled pixels come from the production shell and its single control set. */
    @Test
    fun dictationMorphFramesAmoled() {
        val fixture = render(dark = true, amoled = true)
        listOf(false, true).forEach { returning ->
            firstNavigationFrame {
                fixture.route = if (returning) origin else origin.copy(selectedChatId = null, selectedGroupIdHex = null)
            }
            val direction = if (returning) "into_composer" else "into_capsule"
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_first_amoled.png")
            composeRule.mainClock.advanceTimeBy(80)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_middle_amoled.png")
            composeRule.mainClock.advanceTimeBy(176)
            assertSingleActionSet()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_morph_${direction}_settled_amoled.png")
        }
    }

    /** No await-idle loop or recognition callback may advance the clock past this single commit. */
    private fun firstNavigationFrame(change: () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        val before = composeRule.mainClock.currentTime
        composeRule.runOnUiThread {
            change()
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals(before + 16L, composeRule.mainClock.currentTime)
        assertEquals(renderedFixture.route, renderedFixture.committedRoute)
        assertEquals(renderedFixture.locked, renderedFixture.committedLocked)
    }

    private fun assertSingleActionSet() {
        listOf("Cancel", transcriptPasteLabel, "Send").forEach { label ->
            val action =
                hasContentDescription(label) and
                    hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG))
            composeRule.onAllNodes(action).assertCountEquals(1)
            composeRule.onNode(action).assertIsDisplayed()
        }
    }

    /** Exercises the production ownership/layout, route animation, and actual composer controls. */
    private fun render(
        dark: Boolean = false,
        largeRtl: Boolean = false,
        amoled: Boolean = false,
        compact: Boolean = false,
    ): Fixture {
        val fixture = Fixture()
        renderedFixture = fixture
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
        fixture.platform.listener.onReady()
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
                        ) { owner ->
                            if (!fixture.locked) {
                                val transition =
                                    updateTransition(fixture.route.selectedChatId, label = "dictation route")
                                ConversationRouteAnimatedContent(
                                    transition = transition,
                                    routeForwardDirection = 1,
                                    suppressMotion = false,
                                    contentKey = { it ?: "chat-list" },
                                ) { chatId ->
                                    RenderRouteContents(fixture, owner, chatId, largeRtl, compact)
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
                            .floatingDictationAnchor(),
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

    /** Pixel evidence for the same first departure frame and the fully returned composer. */
    private fun captureNavigation(
        dark: Boolean,
        largeRtl: Boolean,
        returning: Boolean,
        amoled: Boolean = false,
    ) {
        val fixture = render(dark, largeRtl, amoled)
        firstNavigationFrame { fixture.route = origin.copy(selectedChatId = null, selectedGroupIdHex = null) }
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertIsDisplayed()
        if (returning) {
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            composeRule.runOnIdle { fixture.route = origin }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
        }
        assertSingleActionSet()
        val phase = if (returning) "returned_composer" else "departure_first_frame"
        val variant =
            if (amoled) {
                "amoled"
            } else if (largeRtl) {
                "large_font_rtl_dark"
            } else {
                "light"
            }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/dictation_navigation_${phase}_$variant.png")
    }

    private class Fixture {
        var route by mutableStateOf(origin)
        var locked by mutableStateOf(false)
        var committedRoute = origin
        var committedLocked = false
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

    private class FakePlatform : ConversationDictationPlatform {
        var cancels = 0
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() {
                    cancels++
                }

                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val COMPOSER_TAG = "navigation-origin-composer"
        val origin = ConversationDictationComposerRoute("chat", GROUP, "chat", ACCOUNT, true, true)
    }
}
