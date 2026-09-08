package dev.ipf.whitenoise.android.ui.navigation

import android.view.View
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
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

    /** MainActivity is edge-to-edge with adjustResize; dispatch its IME model and settle navigation. */
    private fun captureKeyboardActions(
        dark: Boolean,
        largeRtl: Boolean,
    ) {
        val fixture = render(dark, largeRtl)
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
        listOf("Cancel", "Paste", "Send").forEach { label ->
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
        assertTrue("Destination composer must not pad the IME twice", stripBounds.top - inputBounds.bottom in 0f..64f)
        assertSame(capture, fixture.controller.state)
        val variant = if (largeRtl) "large_font_rtl_dark" else "light"
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
        listOf("Cancel", "Paste", "Send").forEach { label ->
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
        composeRule.onNodeWithContentDescription("Paste").performClick()
        composeRule.runOnUiThread { fixture.platform.listener.onResult("dictated words") }
        assertEquals(listOf(ACCOUNT to GROUP), fixture.writes)
        assertEquals("dictated words", fixture.draft.text)
        assertEquals(ConversationDictationState.Idle, fixture.controller.state)
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
        listOf("Cancel", "Paste", "Send").forEach { label ->
            composeRule.onAllNodesWithContentDescription(label).assertCountEquals(1)
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    /** Exercises the production ownership/layout, route animation, and actual composer controls. */
    private fun render(
        dark: Boolean = false,
        largeRtl: Boolean = false,
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
                WhiteNoiseTheme(darkTheme = dark) {
                    MainShellNoticeLayout(
                        notice = null,
                        dictationController = fixture.controller,
                        dictationComposerRoute = fixture.route,
                        appLockScreenVisible = fixture.locked,
                    ) { owner ->
                        if (!fixture.locked) {
                            val transition = updateTransition(fixture.route.selectedChatId, label = "dictation route")
                            ConversationRouteAnimatedContent(
                                transition = transition,
                                routeForwardDirection = 1,
                                suppressMotion = false,
                                contentKey = { it ?: "chat-list" },
                            ) { chatId ->
                                Box(Modifier.fillMaxSize()) {
                                    if (chatId != null) {
                                        val group = if (chatId == origin.selectedChatId) GROUP else "other-group"
                                        ComposerBar(
                                            replyingTo = null,
                                            messageTextCopy = MessageTextCopy.Default,
                                            onCancelReply = {},
                                            onSend = { _, _ -> },
                                            dictationController = fixture.controller,
                                            dictationAccountRef = ACCOUNT,
                                            dictationGroupIdHex = group,
                                            dictationControlsVisible =
                                                owner == ConversationDictationControlOwner.Composer &&
                                                    fixture.route.selectedChatId == chatId,
                                            modifier = Modifier.align(Alignment.BottomCenter).testTag(COMPOSER_TAG),
                                        )
                                    }
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
    ) {
        val fixture = render(dark, largeRtl)
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
        val variant = if (largeRtl) "large_font_rtl_dark" else "light"
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
                    true
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )
    }

    private class FakePlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

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
