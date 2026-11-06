package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationParkedAudio
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private typealias DictationListener = ConversationDictationRecognitionListener

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-mdpi")
class ComposerDictationControlTest {
    private val transcriptPasteLabel: String
        get() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.paste_dictation_transcript)

    @get:Rule
    val composeRule = createComposeRule()

    private var voicePermissionRequests = 0

    /** A real controller tail failure leaves a normal composer, with audio recovery only in its own chat. */
    @Test
    fun automaticallyKeptFailedTailDoesNotReplaceNormalComposerActions() {
        val platform = RecoverableAudioPlatform()
        var draft = TextFieldValue("")
        var revision = 0L
        var now = 100L
        val restarts = mutableListOf<() -> Unit>()
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
                writeDraft = { _, _, expected, value ->
                    if (expected != revision) {
                        null
                    } else {
                        draft = value
                        revision += 1L
                        revision
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                elapsedRealtime = { now },
                scheduleTimeout = { delay, callback ->
                    if (delay == 500L) restarts += callback
                    ConversationDictationTimeoutHandle {}
                },
            )
        assertTrue(controller.requestStart(ACCOUNT, GROUP, draft))
        platform.listener.onResult("Recognized prefix")
        now += 500L
        restarts.last().invoke()
        controller.send()
        repeat(3) {
            platform.listener.onError(ConversationDictationFailure.NoMatch)
            if (controller.state is ConversationDictationState.Starting) {
                now += 500L
                restarts.last().invoke()
            }
        }
        assertTrue(controller.state is ConversationDictationState.Idle)
        assertTrue(controller.hasParkedComposerAudio(ACCOUNT, GROUP))
        assertEquals("Recognized prefix", draft.text)
        render(draft = draft, suppliedController = controller)

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retry Send").assertDoesNotExist()
        composeRule.onNodeWithTag(APP_DICTATION_CONTROL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("dictation-recovery-status").assertDoesNotExist()
        composeRule.onNodeWithTag("dictation-recovery-panel").assertDoesNotExist()
    }

    @Test
    fun dictationStartedFromAReplyBannerPinsThatVisibleMessageIdentity() {
        val controller = render(replyingTo = timelineAppMessage(REPLY_MESSAGE_ID))

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertEquals(REPLY_MESSAGE_ID, controller.state.target?.replyToMessageIdHex)
    }

    /** Focusing unfolds the editor without displacing emoji from its action row. */
    @Test
    fun focusingUnfoldsTheEditorWithoutDisplacingEmojiFromItsActionRow() {
        render()

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()

        val before =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()

        val after =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()

        assertEquals(before.left, after.left)
        assertEquals(before.right, after.right)
        assertEquals(before.bottom - before.top, after.bottom - after.top)
        val dictation =
            composeRule
                .onNodeWithContentDescription("Dictate text")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        assertEquals(after.top, dictation.top)
        assertEquals(after.bottom, dictation.bottom)
        // The resting row centers its tools with the text line, so they start 6 dp below the pill top; focusing
        // pins them under the 12 dp grip inset and that 36 dp line, 12 + 36 - 6 below where they rested.
        assertEquals(42.dp, after.top - before.top)
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun dictationRemainsReachableBesideSendForANonBlankDraft() {
        render(draft = TextFieldValue("Ready"))

        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    /** Verifies app-owned controls replace only their compact slot and leave adjacent actions stable. */
    @Test
    fun compactComposerActionMorphsToCancelPasteSendWithoutMovingEmojiOrFocus() {
        val controller = render(draft = TextFieldValue("Ready"))
        val field = composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
        val emojiBefore = composeRule.onNodeWithContentDescription("Open emoji picker").getUnclippedBoundsInRoot()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        assertTrue(controller.ownsMicrophone)
        composeRule.onNodeWithTag(COMPOSER_DICTATION_STRIP_TAG).assertDoesNotExist()
        composeRule
            .onNode(
                hasContentDescription(transcriptPasteLabel) and
                    hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
            ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        field.assertIsDisplayed().assertIsFocused()
        assertEquals(
            emojiBefore,
            composeRule.onNodeWithContentDescription("Open emoji picker").getUnclippedBoundsInRoot(),
        )
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().size)
    }

    /** Verifies starting dictation does not focus a composer whose keyboard was already closed. */
    @Test
    fun appOwnedDictationDoesNotOpenAnInitiallyClosedComposer() {
        val controller = render(draft = TextFieldValue("Ready"))
        val field = composeRule.onNode(hasSetTextAction()).assertIsNotFocused()

        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        assertTrue(controller.state is ConversationDictationState.Starting)
        field.assertIsDisplayed().assertIsNotFocused()
        composeRule
            .onNode(
                hasContentDescription(transcriptPasteLabel) and
                    hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
            ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    /** Verifies app-owned dictation removes the competing voice-note microphone from the same composer. */
    @Test
    fun composerOwnedDictationSuppressesTheCompetingVoiceNoteMicrophone() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            render(voiceRecordingController = voiceRecording)
            composeRule.onNodeWithContentDescription("Hold to record voice message").assertExists()

            composeRule.onNodeWithContentDescription("Dictate text").performClick()

            composeRule.onNodeWithContentDescription("Hold to record voice message").assertDoesNotExist()
            composeRule
                .onNode(
                    hasContentDescription(transcriptPasteLabel) and
                        hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
                ).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
        } finally {
            voiceRecording.release()
        }
    }

    @Test
    fun attachmentSheetDoesNotDuplicateTheComposerDictationAction() {
        val controller = render(withAttachments = true)
        composeRule.onNodeWithContentDescription("Add attachment").performClick()

        val dictationActions = composeRule.onAllNodesWithContentDescription("Dictate text")
        assertEquals(1, dictationActions.fetchSemanticsNodes().size)
        assertTrue(controller.state is ConversationDictationState.Idle)
        assertFalse(controller.ownsMicrophone)
    }

    /** Compact large font rtl layout keeps the emoji action reachable without clipping. */
    @Test
    fun compactLargeFontRtlLayoutKeepsTheEmojiActionReachableWithoutClipping() {
        render(fontScale = 2f, rtl = true)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val action =
            composeRule
                .onNodeWithContentDescription("Open emoji picker")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val dictation =
            composeRule
                .onNodeWithContentDescription("Dictate text")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val field = composeRule.onNode(hasSetTextAction()).assertIsDisplayed().getUnclippedBoundsInRoot()

        assertTrue(action.left >= root.left && action.right <= root.right)
        assertTrue(action.top >= root.top && action.bottom <= root.bottom)
        val toolbarTargetWidth = with(composeRule.density) { 40.dp.toPx() }
        val minimumTouchTarget = with(composeRule.density) { 48.dp.toPx() }
        val emojiTouch =
            composeRule.onNodeWithContentDescription("Open emoji picker").fetchSemanticsNode().touchBoundsInRoot
        assertTrue(emojiTouch.width >= toolbarTargetWidth && emojiTouch.height >= minimumTouchTarget)
        assertTrue(dictation.left >= root.left && dictation.right <= root.right)
        assertTrue(dictation.top >= root.top && dictation.bottom <= root.bottom)
        val dictationTouch =
            composeRule.onNodeWithContentDescription("Dictate text").fetchSemanticsNode().touchBoundsInRoot
        assertTrue(dictationTouch.width >= toolbarTargetWidth && dictationTouch.height >= minimumTouchTarget)
        assertTrue("RTL emoji action must remain on the leading side", action.left >= field.left)
        assertTrue("RTL dictation action must not overlap the text field", dictation.right <= field.left)
    }

    /** All three native commands remain independently reachable inside the actual narrow RTL composer. */
    @Test
    @Config(qualifiers = "w240dp-h780dp-mdpi")
    fun narrowLargeRtlDictationActionsScrollWithoutOverlappingTheLeadingTools() {
        val controller = render(fontScale = 2f, rtl = true, withAttachments = true)
        composeRule.onNodeWithContentDescription("Dictate text").performClick()

        /** Records the dispatched action. */
        fun action(label: String) {
            val node =
                composeRule
                    .onNode(
                        hasContentDescription(label) and
                            hasAnyAncestor(hasTestTag(COMPOSER_DICTATION_COMPACT_ACTIONS_TAG)),
                    ).performScrollTo()
                    .assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val root = composeRule.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
            val emoji = composeRule.onNodeWithContentDescription("Open emoji picker").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= root.left && bounds.right <= root.right)
            assertTrue("RTL actions must stay clear of leading tools", bounds.right <= emoji.left)
            node.performClick()
        }
        action(transcriptPasteLabel)
        FakeDictationPlatform.listener.onResult("first")
        assertTrue(controller.state is ConversationDictationState.Idle)
        composeRule.onNodeWithContentDescription("Dictate text").performClick()
        assertTrue(controller.state is ConversationDictationState.Starting)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500L))
        action("Send")
        val late = FakeDictationPlatform.listener
        action("Cancel")
        late.onResult("must not deliver")
        assertTrue(controller.state is ConversationDictationState.Idle)
    }

    /** A failed session still owns completion even though it no longer blocks a fresh Dictate request. */
    @Test
    fun floatingFailedSessionCannotExposeANewVoiceMicrophone() {
        val controller = idleDictationController(TextFieldValue())
        val voice = previewVoiceRecordingController()
        try {
            render(suppliedController = controller, voiceRecordingController = voice, floatingOrigin = true)
            val retainedClick =
                composeRule
                    .onNodeWithTag("composer.voice.launcher")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
            composeRule.runOnIdle {
                assertTrue(controller.requestStart(ACCOUNT, GROUP, TextFieldValue()))
                FakeDictationPlatform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
                assertTrue(controller.state is ConversationDictationState.Failed)
                assertFalse(controller.blocksNewRequest)
                assertTrue(controller.completionControlsRequired)
                // Invoke while the real clickable is still attached, before the next composition.
                checkNotNull(retainedClick.action).invoke()
                assertEquals(0, voicePermissionRequests)
                assertFalse(voice.isRecording)
            }
            composeRule.onNodeWithTag("composer.voice.launcher").assertDoesNotExist()
        } finally {
            voice.release()
        }
    }

    /** A retained Send callback must recheck live ownership before the next composition. */
    @Test
    fun retainedOriginSendCannotClearTypedTextAfterDictationStarts() {
        val draft = TextFieldValue("Original")
        val controller = idleDictationController(draft)
        val input = ComposerTextState(draft)
        var sends = 0
        render(
            draft = draft,
            suppliedController = controller,
            floatingOrigin = true,
            suppliedText = input,
            onSend = { _, accepted ->
                sends++
                accepted()
            },
        )
        val send = composeRule.onNodeWithContentDescription("Send")
        val before = send.fetchSemanticsNode().boundsInRoot
        val retained = send.fetchSemanticsNode().config[SemanticsActions.OnClick]
        composeRule.runOnIdle {
            assertTrue(controller.requestStart(ACCOUNT, GROUP, draft))
            checkNotNull(retained.action).invoke()
            assertEquals(0, sends)
            assertEquals(draft, input.valueState.value)
        }
        send.assertIsNotEnabled().assertIsDisplayed().performClick()
        composeRule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(before, send.fetchSemanticsNode().boundsInRoot)
        assertEquals(0, sends)
        assertEquals(draft, input.valueState.value)
    }

    @Test
    fun originTypedSendCannotPrecedeAutomaticFatalRecovery() = blockedSendThenCompletion("fatal")

    @Test
    fun originTypedSendCannotPrecedeAutomaticSilencePaste() = blockedSendThenCompletion("silence")

    @Test
    fun originTypedSendCannotPrecedeAutomaticWatchdogRecovery() = blockedSendThenCompletion("watchdog")

    /** Uses the real Bar and listener; neither completion path presses Paste. */
    private fun blockedSendThenCompletion(mode: String) {
        val fixture = SendGuardFixture()
        val origin = ACCOUNT to GROUP
        val drafts = fixture.drafts
        val revisions = fixture.revisions
        val sent = fixture.sent
        val writes = fixture.writes
        val controller = automaticCompletionController(fixture, mode)
        renderAcceptedSendComposer(controller, mutableStateOf(origin), drafts, revisions, sent)
        composeRule.runOnIdle {
            assertTrue(controller.requestStart(ACCOUNT, GROUP, drafts.getValue(origin)))
            FakeDictationPlatform.listener.onReady()
        }
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.runOnIdle {
            assertTrue(sent.isEmpty())
            assertEquals("Typed message", drafts.getValue(origin).text)
            val firstListener = FakeDictationPlatform.listener
            firstListener.onResult("dictated words")
            if (mode == "silence") {
                runDeadline(fixture, 3_000L, 3_000L)
            } else {
                runDeadline(fixture, 500L, 500L)
                assertTrue(firstListener !== FakeDictationPlatform.listener)
                if (mode == "watchdog") {
                    runDeadline(fixture, 10_000L, 10_500L)
                } else {
                    FakeDictationPlatform.listener.onError(ConversationDictationFailure.Unknown)
                }
            }
            assertEquals("Typed message dictated words", drafts.getValue(origin).text)
            assertEquals(listOf(origin), writes)
            assertTrue(sent.isEmpty())
            assertEquals(ConversationDictationState.Idle, controller.state)
        }
        composeRule.onNode(hasSetTextAction()).assertTextContains("Typed message dictated words")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(origin to "Typed message dictated words"), sent)
            assertEquals("", drafts.getValue(origin).text)
            assertEquals(listOf(origin), writes)
        }
    }

    /** Advances monotonic time and consumes a live deadline exactly once before its callback. */
    private fun runDeadline(
        fixture: SendGuardFixture,
        delay: Long,
        now: Long,
    ) {
        fixture.now = now
        val timer = fixture.timers.first { it.first == delay }
        fixture.timers.remove(timer)
        timer.second.invoke()
    }

    private class SendGuardFixture {
        val origin = ACCOUNT to GROUP
        val drafts = mutableMapOf(origin to TextFieldValue("Typed message", TextRange("Typed message".length)))
        val revisions = mutableMapOf(origin to 0L)
        val sent = mutableListOf<Pair<Pair<String, String>, String>>()
        val writes = mutableListOf<Pair<String, String>>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
    }

    /** Retains cancellable real-controller deadlines and an authoritative draft revision. */
    private fun automaticCompletionController(
        fixture: SendGuardFixture,
        mode: String,
    ): ConversationDictationController =
        ConversationDictationController(
            platform = FakeDictationPlatform,
            readDraft = { _, _ ->
                ConversationDictationDraftSnapshot(
                    fixture.drafts.getValue(fixture.origin),
                    fixture.revisions.getValue(fixture.origin),
                )
            },
            writeDraft = { account, group, expected, value ->
                if (expected != fixture.revisions.getValue(fixture.origin)) {
                    null
                } else {
                    fixture.writes += account to group
                    fixture.drafts[fixture.origin] = value
                    fixture.revisions[fixture.origin] = expected + 1L
                    expected + 1L
                }
            },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            elapsedRealtime = { fixture.now },
            finishAfterSilenceMillis = { if (mode == "silence") 3_000L else null },
            scheduleTimeout = { delay, callback ->
                val timer = delay to callback
                fixture.timers += timer
                ConversationDictationTimeoutHandle { fixture.timers.remove(timer) }
            },
        )

    /** Another conversation keeps ordinary Send while origin Paste remains a single targeted append. */
    @Test
    fun foreignSendKeepsActivePasteAtOriginAfterAccountAndChatSwitch() {
        val origin = ACCOUNT to GROUP
        val other = "other-account" to "other-group"
        val visible = mutableStateOf(origin)
        val drafts =
            mutableMapOf(
                origin to TextFieldValue("Original", TextRange("Original".length)),
                other to TextFieldValue("Other draft"),
            )
        val revisions = mutableMapOf(origin to 0L, other to 0L)
        val writes = mutableListOf<Pair<String, String>>()
        val sent = mutableListOf<Pair<Pair<String, String>, String>>()
        val controller =
            ConversationDictationController(
                platform = FakeDictationPlatform,
                readDraft = { account, group ->
                    val key = account to group
                    ConversationDictationDraftSnapshot(drafts.getValue(key), revisions.getValue(key))
                },
                writeDraft = { account, group, expected, value ->
                    val key = account to group
                    if (expected != revisions.getValue(key)) {
                        null
                    } else {
                        writes += key
                        drafts[key] = value
                        revisions[key] = expected + 1L
                        expected + 1L
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
            )
        assertTrue(controller.requestStart(ACCOUNT, GROUP, drafts.getValue(origin)))
        val activeListener = FakeDictationPlatform.listener
        renderAcceptedSendComposer(controller, visible, drafts, revisions, sent)
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        composeRule.runOnIdle { visible.value = other }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(other to "Other draft"), sent)
            activeListener.onResult("dictated words")
            controller.paste()
            controller.paste()
            assertEquals("Original dictated words", drafts.getValue(origin).text)
            assertEquals("", drafts.getValue(other).text)
            assertEquals(listOf(origin), writes)
        }
    }

    /** Cancellation retires the real generation, unlike an ordinary typed Send. */
    @Test
    fun cancelledFloatingSessionIgnoresItsRetiredRecognitionListener() {
        val kept = TextFieldValue("Kept draft")
        var writes = 0
        var sends = 0
        val controller =
            ConversationDictationController(
                platform = FakeDictationPlatform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(kept, 0L) },
                writeDraft = { _, _, _, _ ->
                    writes++
                    1L
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
            )
        assertTrue(controller.requestStart(ACCOUNT, GROUP, kept))
        val retiredListener = FakeDictationPlatform.listener
        val input = ComposerTextState(kept)
        render(
            draft = kept,
            suppliedController = controller,
            floatingOrigin = true,
            suppliedText = input,
            onSend = { _, _ -> sends++ },
        )
        composeRule.runOnIdle {
            controller.cancel()
            retiredListener.onResult("must be ignored")
            controller.paste()
            assertTrue(controller.state is ConversationDictationState.Idle)
            assertEquals(0, writes)
            assertEquals(0, sends)
            assertEquals(kept, input.valueState.value)
        }
    }

    /** Composes each visible owner against the existing draft, revision and acceptance callback references. */
    private fun renderAcceptedSendComposer(
        controller: ConversationDictationController,
        visible: State<Pair<String, String>>,
        drafts: MutableMap<Pair<String, String>, TextFieldValue>,
        revisions: MutableMap<Pair<String, String>, Long>,
        sent: MutableList<Pair<Pair<String, String>, String>>,
    ) {
        composeRule.setContent {
            val key = visible.value
            val editor =
                rememberComposerTextState(
                    key,
                    drafts.getValue(key),
                    controller.completionRevision(key.first, key.second),
                )
            WhiteNoiseTheme {
                ComposerBar(
                    replyingTo = null,
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    textState = editor,
                    draftKey = key,
                    draftAccountRef = key.first,
                    draftGroupIdHex = key.second,
                    onDraftChange = { value, changed ->
                        drafts[key] = value
                        if (changed) revisions[key] = revisions.getValue(key) + 1L
                    },
                    onSend = { text, accepted ->
                        sent += key to text
                        drafts[key] = TextFieldValue()
                        revisions[key] = revisions.getValue(key) + 1L
                        accepted()
                    },
                    dictationController = controller,
                    dictationAccountRef = key.first,
                    dictationGroupIdHex = key.second,
                    dictationControlsVisible = false,
                    dictationOriginVisible = key == (ACCOUNT to GROUP),
                    modifier = Modifier.width(320.dp),
                )
            }
        }
    }

    /** Renders the actual composer against the supplied draft and dictation ownership state. */
    private fun render(
        fontScale: Float = 1f,
        rtl: Boolean = false,
        draft: TextFieldValue = TextFieldValue(""),
        withAttachments: Boolean = false,
        voiceRecordingController: VoiceRecordingController? = null,
        replyingTo: dev.ipf.marmotkit.AppMessageRecordFfi? = null,
        suppliedController: ConversationDictationController? = null,
        floatingOrigin: Boolean = false,
        suppliedText: ComposerTextState? = null,
        onDraftChange: (TextFieldValue, Boolean) -> Unit = { _, _ -> },
        onSend: (String, () -> Unit) -> Unit = { _, _ -> },
    ): ConversationDictationController {
        val dictationController = suppliedController ?: idleDictationController(draft)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = false) {
                    ComposerBar(
                        replyingTo = replyingTo,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = onSend,
                        onDraftChange = onDraftChange,
                        textState = suppliedText ?: rememberComposerTextState(null, draft),
                        dictationControlsVisible = !floatingOrigin,
                        dictationOriginVisible = true,
                        onPickFromGallery = if (withAttachments) ({}) else null,
                        initialDraft = draft,
                        dictationController = dictationController,
                        dictationAccountRef = ACCOUNT,
                        dictationGroupIdHex = GROUP,
                        voiceRecordingController = voiceRecordingController,
                        modifier = Modifier.width(320.dp).testTag(ROOT_TAG),
                    )
                }
            }
        }
        return dictationController
    }

    /** Creates a no-I/O voice recorder used only to exercise composer action ownership. */
    private fun previewVoiceRecordingController(): VoiceRecordingController {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return VoiceRecordingController(
            context = context,
            outputDirectory = context.cacheDir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onPermissionRequest = {
                voicePermissionRequests++
                false
            },
            onRecordingComplete = { _, _ -> },
            onError = {},
        )
    }

    /** Builds an idle dictation controller fixture. */
    private fun idleDictationController(draft: TextFieldValue): ConversationDictationController =
        ConversationDictationController(
            platform = FakeDictationPlatform,
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
            writeDraft = { _, _, _, _ -> 0L },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
        )

    private data object FakeDictationPlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        /** Has record audio permission. */
        override fun hasRecordAudioPermission() = true

        /** Recognition available. */
        override fun recognitionAvailable() = true

        /** Fake recognizer: creates a session. */
        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                /** Fake playback: stops. */
                override fun stop() = Unit

                /** Fake operation: cancels. */
                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }

    private class RecoverableAudioPlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener
        private var pending = true

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun callerAudioHasPending() = pending

        override fun parkCallerAudio(): ConversationDictationParkedAudio {
            pending = false
            return object : ConversationDictationParkedAudio {
                override fun restore(): Boolean {
                    pending = true
                    return true
                }

                override fun discard() = Unit
            }
        }

        override fun createSession(listener: DictationListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit

                override fun usesCallerAudioCapture() = true

                override fun acknowledgeCallerAudio() = true

                override fun callerAudioFullyFed() = true

                override fun callerAudioIsFinalChunk() = true
            }
        }
    }

    private companion object {
        const val ROOT_TAG = "composer-dictation-control-root"
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val REPLY_MESSAGE_ID = "reply-message"
    }
}
