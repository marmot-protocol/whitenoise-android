package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerDraftSnapshot
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.conversationRoutePresentationShouldFreeze
import dev.ipf.whitenoise.android.ui.conversation.rememberComposerDictationRevisionOnEntry
import dev.ipf.whitenoise.android.ui.conversation.shouldAutoFocusComposerOnDraftRestore
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerDraftRestoreFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inPlaceDictationCompletionRehydratesComposerWithoutRequestingFocusOrIme() {
        val fixture = DictationRestoreFixture()
        val restorationTester = StateRestorationTester(composeRule)

        restorationTester.setContent {
            val snapshot = fixture.persistedDraft
            val autoFocusConsumed = remember(fixture.draftKey) { mutableStateOf(false) }
            val dictationRevisionOnEntry =
                rememberComposerDictationRevisionOnEntry(
                    groupIdHex = fixture.draftKey,
                    currentRevision = fixture.dictationRevision,
                )
            fixture.composerState =
                rememberComposerTextState(
                    draftKey = fixture.draftKey,
                    initialDraft = snapshot?.textFieldValue ?: TextFieldValue(),
                    externalRevision = 0 to fixture.dictationRevision,
                )
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = snapshot?.textFieldValue ?: TextFieldValue(),
                        draftKey = fixture.draftKey,
                        textState = fixture.composerState,
                        autoFocusOnDraftRestore =
                            shouldAutoFocusComposerOnDraftRestore(
                                snapshot = snapshot,
                                dictationRevisionOnEntry = dictationRevisionOnEntry,
                                currentDictationRevision = fixture.dictationRevision,
                            ),
                        autoFocusConsumedState = autoFocusConsumed,
                        softwareKeyboardController = fixture.keyboardController,
                        onComposerFocusChanged = { focused ->
                            if (focused) fixture.focusGainCount += 1
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val accepted = TextFieldValue("accepted words", TextRange(3, 11))
        composeRule.runOnIdle {
            fixture.persistedDraft = ComposerDraftSnapshot(accepted, focusOnRestore = true)
            fixture.dictationRevision += 1
        }
        composeRule.waitForIdle()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(accepted, fixture.composerState.valueState.value)
            assertEquals(0, fixture.focusGainCount)
            assertEquals(0, fixture.keyboardController.showRequests)
        }

        assertManualComposerFocusStillWorks(fixture)
    }

    @Test
    fun draftRestorePolicyCoversGenuineDictationAndMissingDraftCases() {
        val snapshot = ComposerDraftSnapshot(TextFieldValue("saved draft"), focusOnRestore = true)

        assertTrue(
            shouldAutoFocusComposerOnDraftRestore(
                snapshot = snapshot,
                dictationRevisionOnEntry = 7,
                currentDictationRevision = 7,
            ),
        )
        assertFalse(
            shouldAutoFocusComposerOnDraftRestore(
                snapshot = snapshot,
                dictationRevisionOnEntry = 7,
                currentDictationRevision = 8,
            ),
        )
        assertFalse(
            shouldAutoFocusComposerOnDraftRestore(
                snapshot = null,
                dictationRevisionOnEntry = 7,
                currentDictationRevision = 7,
            ),
        )
    }

    private class RecordingSoftwareKeyboardController : SoftwareKeyboardController {
        var showRequests = 0

        override fun show() {
            showRequests += 1
        }

        override fun hide() = Unit
    }

    /** Mutable state shared by the dictation restore composition and its assertions. */
    private class DictationRestoreFixture {
        val draftKey = "conversation-1"
        var persistedDraft by mutableStateOf<ComposerDraftSnapshot?>(null)
        var dictationRevision by mutableIntStateOf(0)
        lateinit var composerState: ComposerTextState
        var focusGainCount = 0
        val keyboardController = RecordingSoftwareKeyboardController()
    }

    /** Confirms suppressing restore focus does not prevent a later explicit user focus. */
    private fun assertManualComposerFocusStillWorks(fixture: DictationRestoreFixture) {
        composeRule.onNodeWithText("accepted words").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, fixture.focusGainCount) }
    }

    @Test
    fun restoredDraftFocusSurvivesComposerRemountWithSameDraftKey() {
        var showComposer by mutableStateOf(true)
        val draftKey = "conversation-1"
        lateinit var focusManager: FocusManager
        var focusGainCount = 0

        composeRule.setContent {
            focusManager = LocalFocusManager.current
            val autoFocusConsumed = remember(draftKey) { mutableStateOf(false) }
            val snapshot = ComposerDraftSnapshot(TextFieldValue("saved draft"), focusOnRestore = true)
            WhiteNoiseTheme {
                Surface {
                    if (showComposer) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            initialDraft = snapshot.textFieldValue,
                            draftKey = draftKey,
                            autoFocusOnDraftRestore =
                                shouldAutoFocusComposerOnDraftRestore(
                                    snapshot = snapshot,
                                    dictationRevisionOnEntry = 0,
                                    currentDictationRevision = 0,
                                ),
                            autoFocusConsumedState = autoFocusConsumed,
                            onComposerFocusChanged = { focused ->
                                if (focused) focusGainCount += 1
                            },
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, focusGainCount)
            focusManager.clearFocus(force = true)
        }
        composeRule.runOnIdle { showComposer = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { showComposer = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, focusGainCount) }
    }

    @Test
    fun restoredDraftFocusRunsAgainWhenConversationChanges() {
        var draftKey by mutableStateOf("first")
        lateinit var focusManager: FocusManager
        var focusGainCount = 0

        composeRule.setContent {
            focusManager = LocalFocusManager.current
            val autoFocusConsumed = remember(draftKey) { mutableStateOf(false) }
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue("draft $draftKey"),
                        draftKey = draftKey,
                        autoFocusOnDraftRestore = true,
                        autoFocusConsumedState = autoFocusConsumed,
                        onComposerFocusChanged = { focused ->
                            if (focused) focusGainCount += 1
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, focusGainCount)
            focusManager.clearFocus(force = true)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { draftKey = "second" }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(2, focusGainCount) }
    }

    /** Restored-draft focus waits until route presentation has released its terminal frame. */
    @Test
    fun restoredDraftFocusWaitsForRouteSettlement() {
        var routePresentationFrozen by mutableStateOf(true)
        var focusGainCount = 0

        composeRule.setContent {
            val autoFocusConsumed = remember { mutableStateOf(false) }
            WhiteNoiseTheme {
                Surface {
                    ComposerBar(
                        replyingTo = null,
                        messageTextCopy = MessageTextCopy.Default,
                        onCancelReply = {},
                        onSend = { _, _ -> },
                        initialDraft = TextFieldValue("saved draft"),
                        draftKey = "conversation-1",
                        autoFocusOnDraftRestore = !routePresentationFrozen,
                        autoFocusConsumedState = autoFocusConsumed,
                        onComposerFocusChanged = { focused ->
                            if (focused) focusGainCount += 1
                        },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, focusGainCount) }
        composeRule.runOnIdle { routePresentationFrozen = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, focusGainCount) }
    }

    /** A reused controller freezes its consumers in the first composition of a new transition. */
    @Test
    fun reusedControllerFreezesSynchronouslyOnTheNextTransitionEdge() {
        assertEquals(
            false,
            conversationRoutePresentationShouldFreeze(
                routeTransitionInProgress = false,
                retainedPresentationFreeze = false,
            ),
        )
        assertEquals(
            true,
            conversationRoutePresentationShouldFreeze(
                routeTransitionInProgress = true,
                retainedPresentationFreeze = false,
            ),
        )
        assertEquals(
            true,
            conversationRoutePresentationShouldFreeze(
                routeTransitionInProgress = false,
                retainedPresentationFreeze = true,
            ),
        )
    }
}
