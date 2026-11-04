package dev.ipf.whitenoise.android.ui.navigation

import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatListBulkArchiveAction
import dev.ipf.whitenoise.android.ui.chats.ChatListSelectionControls
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.conversation.composer.APP_DICTATION_CONTROL_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession as RecognitionSession

/** Production shell controls in a real Android window; recognition is controlled and never opens a microphone. */
@PullRequestDeviceSmoke
@SdkSuppress(minSdkVersion = 30)
@RunWith(AndroidJUnit4::class)
class NavigationControlAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fixture = DictationFixture()
    private var height by mutableStateOf(580.dp)
    private var showSelection by mutableStateOf(false)
    private var destination by mutableStateOf<ComposerFabEndpoint?>(null)
    private var locked by mutableStateOf(false)
    private var handoff: MeasuredControlHandoff? = null
    private var committedSelection = false
    private var selections = 0
    private var deletes = 0
    private var fabClicks = 0
    private var previousScale: String? = null
    private var imeAnimationRunning = false

    /** Explicit enabled motion is required because the ordinary hosted emulator disables animator durations. */
    @Before
    @Suppress("DEPRECATION")
    fun enableMotion() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            ViewCompat.setWindowInsetsAnimationCallback(
                rule.activity.window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    /** Holds exact geometry assertions while the platform is animating IME insets. */
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimationRunning = true
                    }

                    /** Leaves production inset dispatch intact for the actual shell and footer. */
                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: List<WindowInsetsAnimationCompat>,
                    ): WindowInsetsCompat = insets

                    /** Releases geometry assertions only after the keyboard reaches its docked bounds. */
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) imeAnimationRunning = false
                    }
                },
            )
        }
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val resolver = rule.activity.contentResolver
        previousScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        check(Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }

    /** Retire the controlled recognizer and restore the emulator setting after every scenario. */
    @After
    fun restoreMotion() {
        try {
            rule.mainClock.autoAdvance = true
            rule.runOnUiThread { fixture.controller.cancel() }
        } finally {
            try {
                Settings.Global.putString(
                    rule.activity.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    previousScale,
                )
            } finally {
                InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    /** A newly mounted, wrapped RTL selection footer must clear the live capsule on its first layout. */
    @Test
    fun activeDictationClearsActualSelectionFooterOnFirstFrame() {
        rule.runOnUiThread {
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
            fixture.platform.listener.onReady()
        }
        render(largeRtl = true)
        val capture = fixture.controller.state
        rule.mainClock.autoAdvance = false
        val before = rule.mainClock.currentTime
        rule.runOnUiThread {
            showSelection = true
            Snapshot.sendApplyNotifications()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(before + 16L, rule.mainClock.currentTime)
        assertTrue(committedSelection)
        val capsule =
            rule
                .onNodeWithTag(APP_DICTATION_CONTROL_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val footer =
            rule
                .onNodeWithTag("chats.selectionControls")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("active capsule overlaps wrapped footer: $capsule / $footer", capsule.bottom <= footer.top)
        rule.onNodeWithTag("chats.selectAllAction").performClick()
        assertEquals(1, selections)
        assertSame(capture, fixture.controller.state)
        assertEquals(TextFieldValue(PRIVATE_DRAFT), fixture.draft)
        assertTrue(fixture.writes.isEmpty())
    }

    /** The same capsule and full-size selection actions remain reachable through the shared short-window scroll. */
    @Test
    fun shortWindowKeepsLastSelectionActionReachableWithoutRetargetingDictation() {
        rule.runOnUiThread {
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
            fixture.platform.listener.onReady()
        }
        showSelection = true
        height = 140.dp
        render(largeRtl = true)
        val capture = fixture.controller.state
        rule
            .onNodeWithContentDescription("Delete selected fixtures")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, deletes)
        assertSame(capture, fixture.controller.state)
        assertTrue(fixture.writes.isEmpty())
        repeat(3) {
            rule.onNodeWithTag("navigation.viewport").performTouchInput { swipeDown() }
        }
        rule.onNodeWithContentDescription("Cancel").assertIsDisplayed().performClick()
        assertEquals(TextFieldValue(PRIVATE_DRAFT), fixture.draft)
    }

    /** Real IME insets preserve the active capsule and selection actions without changing the captured destination. */
    @Test
    fun realKeyboardKeepsDictationAndSelectionActionsAboveItsWindow() {
        rule.runOnUiThread {
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
            fixture.platform.listener.onReady()
        }
        showSelection = true
        render(editor = true)
        val capture = fixture.controller.state
        rule.onNodeWithTag("navigation.imeField").performClick()
        rule.waitUntil(10_000) {
            rule.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 &&
                    !imeAnimationRunning
            }
        }
        rule.onNodeWithTag("navigation.imeField").assertIsFocused()
        rule.onNodeWithTag("chats.selectAllAction").performScrollTo().assertIsDisplayed()
        rule.waitForIdle()
        val capsule =
            rule
                .onNodeWithTag(APP_DICTATION_CONTROL_TAG)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInWindow
        val action = rule.onNodeWithTag("chats.selectAllAction").fetchSemanticsNode().boundsInWindow
        val keyboardTop =
            rule.runOnUiThread {
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))
                val windowBottom = rule.activity.windowManager.currentWindowMetrics.bounds.bottom
                windowBottom - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
        assertTrue("capsule must clear the real keyboard", capsule.bottom <= keyboardTop)
        assertTrue("selection action must clear the real keyboard", action.bottom <= keyboardTop)
        assertTrue("capsule and the current selection action must not overlap", capsule.bottom <= action.top)
        rule.onNodeWithTag("chats.selectAllAction").performClick()
        assertEquals(1, selections)
        assertSame(capture, fixture.controller.state)
        assertTrue(fixture.writes.isEmpty())
    }

    /** Measured production containers preserve rapid return and never duplicate private draft input. */
    @Test
    fun composerFabRoundTripKeepsDraftPrivateAndClearsGeometryOnLock() {
        destination = ComposerFabEndpoint.Composer
        render()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { destination = ComposerFabEndpoint.NewMessage }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText(PRIVATE_DRAFT, substring = true).assertDoesNotExist()
        rule.runOnIdle { assertNotNull("the actual FAB must admit the measured container", handoff?.renderedBounds) }
        rule.onNodeWithContentDescription("New Message").assertIsDisplayed().performClick()
        assertEquals(1, fabClicks)
        rule.runOnIdle { assertNull("activation must retire the decoration immediately", handoff?.renderedBounds) }
        rule.runOnIdle { destination = ComposerFabEndpoint.Composer }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithText(PRIVATE_DRAFT, substring = true).assertExists()
        assertEquals(TextFieldValue(PRIVATE_DRAFT), fixture.draft)
        rule.runOnIdle { locked = true }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText(PRIVATE_DRAFT, substring = true).assertDoesNotExist()
        rule.runOnIdle { assertNull(handoff?.renderedBounds) }
    }

    /** Uses the production shell's layout and local endpoint owner rather than a fake anchor/capsule rectangle. */
    private fun render(
        largeRtl: Boolean = false,
        editor: Boolean = false,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (largeRtl) 2f else 1f),
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = largeRtl) {
                    Surface(Modifier.size(280.dp, height).testTag("navigation.viewport")) {
                        MainShellNoticeLayout(
                            notice = null,
                            dictationController = fixture.controller,
                            dictationComposerRoute =
                                ConversationDictationComposerRoute(null, null, null, ACCOUNT, true, true),
                            appLockScreenVisible = locked,
                            handoffOwner = ACCOUNT,
                            handoffDestination = destination,
                        ) {
                            handoff = LocalComposerFabHandoff.current
                            SideEffect { committedSelection = showSelection }
                            Box(Modifier.fillMaxSize()) {
                                if (!locked) {
                                    if (editor) {
                                        WhiteNoiseTextField(
                                            state = rememberTextFieldState(),
                                            modifier = Modifier.fillMaxWidth().testTag("navigation.imeField"),
                                        )
                                    }
                                    if (showSelection) {
                                        Box(Modifier.align(Alignment.BottomCenter)) { SelectionFooter() }
                                    } else if (destination == ComposerFabEndpoint.Composer) {
                                        ComposerBar(
                                            replyingTo = null,
                                            messageTextCopy = MessageTextCopy.Default,
                                            onCancelReply = {},
                                            onSend = { _, _ -> error("This fixture must never send") },
                                            initialDraft = fixture.draft,
                                            modifier = Modifier.align(Alignment.BottomCenter),
                                        )
                                    } else if (destination == ComposerFabEndpoint.NewMessage) {
                                        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                                            ChatsNewMessageFabContent(false) { fabClicks++ }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The real wrapped selection footer participates in the same registration and scrolling contract as the app. */
    @Composable
    private fun SelectionFooter() {
        ChatListSelectionControls(
            count = 3,
            archiveAction = ChatListBulkArchiveAction.Archive,
            actionsEnabled = true,
            allVisibleSelected = false,
            showMarkRead = true,
            showMarkUnread = false,
            showMute = true,
            showPin = true,
            showMovePinnedUp = false,
            showMovePinnedDown = false,
            onArchive = {},
            onDelete = { deletes++ },
            onAddToFolder = {},
            onMarkRead = {},
            onMarkUnread = {},
            onSetMuted = {},
            onSetPinned = {},
            onMovePinned = {},
            onSelectAll = { selections++ },
            onDeselectAll = {},
            deleteLabel = "Delete selected fixtures",
        )
    }

    /** A controlled recognizer uses the real controller's capture/destination lifecycle without audio or network. */
    private class DictationFixture {
        val platform = FakePlatform()
        var draft = TextFieldValue(PRIVATE_DRAFT)
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

    /** Speech events are explicit; no permission prompt, microphone, provider or remote recognizer is involved. */
    private class FakePlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun createSession(listener: ConversationDictationRecognitionListener): RecognitionSession {
            this.listener = listener
            return object : RecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        const val ACCOUNT = "emulator-dictation-owner"
        const val GROUP = "emulator-origin-group"
        const val PRIVATE_DRAFT = "Private unsent emulator fixture"
    }
}
