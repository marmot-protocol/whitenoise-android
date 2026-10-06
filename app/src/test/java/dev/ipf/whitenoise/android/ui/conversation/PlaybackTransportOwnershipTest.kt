package dev.ipf.whitenoise.android.ui.conversation

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.media.AudioManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.window.DialogProperties
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsNavigationOutcome
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsSpokenTextSpan
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.currentPlaybackConversationDestination
import dev.ipf.whitenoise.android.state.observePlaybackConversationDestination
import dev.ipf.whitenoise.android.state.observePlaybackTransportVisible
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentCandidate
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentFormat
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentNativeActions
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentPreview
import dev.ipf.whitenoise.android.ui.conversation.media.speakTextAttachment
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import java.io.File
import java.util.Locale

/** Exercises the real selector and account/lock revocation, rather than only a stateless strip fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackTransportOwnershipTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Switching destinations/accounts may retain playback, but removing its source account revokes it. */
    @Test fun removedSourceAccountStopsAndHidesVoiceTransport() {
        val appState = appState()
        setVoice()
        render(appState)
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.runOnIdle { setAccounts(appState, emptyList()) }
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
        rule.runOnIdle { assertNull(VoicePlaybackController.state.value.key) }
    }

    /** A signed-out account never emits even a first frame of its captured source title. */
    @Test fun signedOutSourceDoesNotDisplayCapturedVoiceTitle() {
        val appState = appState()
        setAccounts(appState, appState.accounts.map { it.copy(signedOut = true) })
        setVoice()
        render(appState)
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
        rule.runOnIdle { assertNull(VoicePlaybackController.state.value.key) }
    }

    /** Active speech and a retained voice session produce exactly one transport; pause restores voice controls. */
    @Test fun activeSpeechSelectsOneTransportThenPausedSpeechYieldsToVoice() {
        val appState = appState()
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("m", "Maya", "Speech.")), Locale.US))
        setVoice()
        render(appState)
        rule.onNodeWithTag(TTS_TRANSPORT_BODY_TAG).assertIsDisplayed()
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
        rule.runOnIdle { appState.ttsController.pause() }
        rule.onNodeWithTag(TTS_TRANSPORT_BODY_TAG).assertDoesNotExist()
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.runOnIdle { appState.stopSpeaking() }
    }

    /** Locking the app removes the source label and controls immediately without relying on playback callbacks. */
    @Test fun appLockHidesVoiceTransport() {
        Shadow
            .extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
            .setCanAuthenticate(true)
        val appState = appState()
        setVoice()
        render(appState)
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.runOnIdle { appState.updateRequireAppUnlock(true) }
        assertTrue(appState.appLockScreenVisible)
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
    }

    /** A full-screen window keeps its own controls and dismisses before the shared source route runs. */
    @Test fun fullScreenDialogKeepsTransportAndDismissesBeforeReturningToSource() {
        val appState = appState()
        setVoice()
        var visible by mutableStateOf(true)
        var opened = 0
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalShellPlaybackHost provides
                        ShellPlaybackHost(appState) {
                            assertTrue(!visible)
                            opened++
                        },
                ) {
                    PlaybackTransportBar(appState)
                    if (visible) {
                        PlaybackDialog(
                            onDismissRequest = { visible = false },
                            properties =
                                DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false,
                                ),
                        ) { Text("Modal destination", Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        rule.onNode(hasTestTag("voice-transport") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNode(hasText("Maya") and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(1, opened) }
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
    }

    /** Audio time changes update the small strip, never the full navigation shell or modal destination tree. */
    @Test fun audioTicksDoNotRecomposeShellObservers() {
        val appState = appState()
        setVoice()
        var commits = 0
        rule.setContent {
            appState.observePlaybackConversationDestination()
            appState.observePlaybackTransportVisible()
            SideEffect { commits++ }
        }
        rule.waitForIdle()
        val before = commits
        rule.runOnIdle {
            val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
            state.value = state.value.copy(positionMs = 15_000)
        }
        rule.waitForIdle()
        assertEquals(before, commits)
    }

    /** Source navigation uses the real folder editor's discard confirmation and never writes a dirty draft. */
    @Test fun dirtyFolderDraftSurvivesSourceReturnUntilDiscardIsConfirmed() {
        val appState = appState()
        setVoice()
        var opened = 0
        val host = ShellPlaybackHost(appState) { opened++ }
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalShellPlaybackHost provides host) {
                    androidx.compose.foundation.layout.Column {
                        PlaybackTransportBar(appState, onBodyClick = { host.requestOpenSource() })
                        dev.ipf.whitenoise.android.ui.settings.ChatFolderEditScreen(
                            appState,
                            "personal",
                            null,
                            onClose = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("folder.name").performTextReplacement("Unsaved folder")
        rule.onNodeWithText("Maya").performClick()
        rule.onNodeWithText(context.getString(dev.ipf.whitenoise.android.R.string.folder_keep_editing)).performClick()
        assertEquals(0, opened)
        rule.onNodeWithTag("folder.name").assertTextContains("Unsaved folder")
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.onNodeWithText("Maya").performClick()
        rule.onNodeWithText(context.getString(dev.ipf.whitenoise.android.R.string.folder_discard)).performClick()
        assertEquals(1, opened)
        assertTrue(appState.chatFolderPreferences.foldersFor("personal").none { it.name == "Unsaved folder" })
    }

    /** Speech without canonical source metadata cannot dismiss an unrelated modal through its body label. */
    @Test fun sourceLessSpeechKeepsModalOpenAndTransportControlsAvailable() {
        val appState = appState()
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("m", "Maya", "Speech.")), Locale.US))
        var dismissals = 0
        val host = ShellPlaybackHost(appState) { error("No canonical source") }
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalShellPlaybackHost provides host) {
                    PlaybackDialog(onDismissRequest = { dismissals++ }) { Text("Reader stays open") }
                }
            }
        }
        rule.onNodeWithTag(TTS_TRANSPORT_BODY_TAG).assertHasNoClickAction()
        rule.onNodeWithText("Reader stays open").assertIsDisplayed()
        assertEquals(0, dismissals)
        rule.runOnIdle { host.requestOpenSource { dismissals++ } }
        assertEquals(0, dismissals)
        rule.runOnIdle { appState.stopSpeaking() }
    }

    /** Pending dirty confirmation loses authority if playback is stopped or replaced while it is visible. */
    @Test fun deferredSourceNavigationCannotDismissAfterSessionReplacement() {
        val appState = appState()
        setVoice()
        var opened = 0
        var dismissed = 0
        var confirm: (() -> Unit)? = null
        val host = ShellPlaybackHost(appState) { opened++ }
        host.registerLeaveGuard(this) { confirm = it }
        host.requestOpenSource { dismissed++ }
        VoicePlaybackController.stop()
        checkNotNull(confirm).invoke()
        assertEquals(0, dismissed)
        assertEquals(0, opened)
    }

    /** A current speech queue may advance naturally while the user decides whether to discard an editor draft. */
    @Test fun deferredSourceReturnFollowsTheCurrentPassageOfTheSameSpeechSession() {
        val appState = appState()
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(
            appState.speakAloudAutoRead(
                "group",
                listOf("first" to "First message.", "next" to "Next message.").map { (id, text) ->
                    TtsSpeakableEntry(
                        senderKey = "maya",
                        senderDisplayName = "Maya",
                        text = text,
                        messageIdHex = id,
                        spokenTextSpans =
                            listOf(
                                TtsSpokenTextSpan(
                                    TtsTextRange(0, text.length),
                                    TtsVisibleTextSpan("body", 0, text.length),
                                ),
                            ),
                        projectionId = id,
                        visibleLeaves = mapOf("body" to text),
                    )
                },
                Locale.US,
            ),
        )
        var opened = 0
        var confirm: (() -> Unit)? = null
        val host = ShellPlaybackHost(appState) { opened++ }
        host.registerLeaveGuard(this) { confirm = it }
        assertEquals("first", appState.currentPlaybackConversationDestination()?.messageIdHex)
        host.requestOpenSource()
        assertEquals(TtsNavigationOutcome.Moved, appState.ttsController.skipNextMessage())
        assertEquals("next", appState.currentPlaybackConversationDestination()?.messageIdHex)
        checkNotNull(confirm).invoke()
        assertEquals(1, opened)
        appState.stopSpeaking()
    }

    /** A discarded editor cannot replay a captured confirmation into a later shell destination. */
    @Test fun disposedLeaveGuardRevokesItsCapturedConfirmation() {
        val appState = appState()
        setVoice()
        var opened = 0
        var mounted by mutableStateOf(true)
        var confirm: (() -> Unit)? = null
        val host = ShellPlaybackHost(appState) { opened++ }
        rule.setContent {
            CompositionLocalProvider(LocalShellPlaybackHost provides host) {
                if (mounted) PlaybackSourceLeaveGuard { confirm = it }
            }
        }
        rule.runOnIdle { host.requestOpenSource() }
        rule.runOnIdle { mounted = false }
        rule.runOnIdle { checkNotNull(confirm).invoke() }
        assertEquals(0, opened)
    }

    /** Attachment reading returns to its canonical message after the reader closes without enabling history paging. */
    @Test fun attachmentSpeechRetainsOnlyItsAcceptedCanonicalSource() {
        val appState = appState()
        appState.ttsController.attachEngine(FakeSessionEngine())
        val owner =
            dev.ipf.whitenoise.android.audio
                .AttachmentSpeechOwner("personal", "group", "canonical", 2)
        val actions = TextAttachmentNativeActions({ true }, owner) {}
        val preview =
            TextAttachmentPreview(
                TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText),
                "Attachment speech.",
            )
        kotlinx.coroutines.runBlocking {
            appState.speakTextAttachment(preview, "sender", "Maya", "canonical", 2, actions)
        }
        actions.release()
        val destination = checkNotNull(appState.currentPlaybackConversationDestination())
        assertEquals("canonical", destination.messageIdHex)
        assertEquals("personal", destination.accountRef)
        assertEquals("group", destination.groupIdHex)
        assertNull(destination.ttsFocusSessionId)
        assertNull(appState.ttsHistorySession.conversationSource.value)
        appState.ttsController.pause()
        assertEquals(destination, appState.currentPlaybackConversationDestination())
        assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("another", "Maya", "New queue.")), Locale.US))
        assertNull(appState.currentPlaybackConversationDestination())
        assertNull(appState.attachmentSpeechDestination.value)
        appState.stopSpeaking()
    }

    /** A rejected reader preparation cannot stop the newer queue that replaced its source owner. */
    @Test fun staleAttachmentPreparationCannotStopReplacementSpeech() {
        shadowOf(context.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val preparationContext = PreparationServiceContext(context)
        val appState = appState(preparationContext)
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)
        var current = true
        var replacementSession: Long? = null
        var preparationStarts = 0
        var preparingState: TtsState? = null
        var replacementStarted = false
        var callbackFailure: Throwable? = null
        preparationContext.onStart = {
            preparationStarts += 1
            preparingState = appState.ttsController.state.value
            current = false
            runCatching {
                replacementStarted =
                    appState.speakAloud(
                        listOf(TtsSpeakableEntry("sender", "Maya", "Replacement queue.")),
                        Locale.US,
                    )
                replacementSession = appState.ttsController.state.value.sessionId
            }.onFailure { callbackFailure = it }
        }
        val actions = TextAttachmentNativeActions(sourceIsCurrent = { current }) {}
        val preview =
            TextAttachmentPreview(
                TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText),
                "Obsolete attachment speech.",
            )
        rule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                appState.speakTextAttachment(preview, "sender", "Maya", "obsolete", 0, actions)
            }
        }
        callbackFailure?.let { throw AssertionError("Replacement service callback failed", it) }
        val failure = appState.ttsController.lastStartFailure
        assertEquals("Preparation service was not admitted: $failure", 1, preparationStarts)
        assertTrue("Expected Preparing at service admission: $preparingState", preparingState is TtsState.Preparing)
        assertTrue("Replacement speech was refused: $failure", replacementStarted)
        val acceptedSession = checkNotNull(replacementSession) { "Replacement session was not captured" }
        assertEquals(acceptedSession, appState.ttsController.state.value.sessionId)
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
        assertTrue(appState.ownsCurrentAccountSpeech())
        assertEquals(1, engine.spoken.size)
        assertNull(appState.attachmentSpeechDestination.value)
        appState.stopSpeaking()
    }

    /** MainShell must retain ordinary outgoing chrome while preserving account and lock privacy gates. */
    @Test fun backDoesNotDeselectTheRetainedConversationPlayer() {
        val source = File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt").readText()
        val slot = source.substringAfter("playbackTransport = {").substringBefore("dictationControlsVisible =")
        assertTrue(slot.contains("navAccountStable && !appState.appLockScreenVisible"))
        assertFalse(slot.contains("selectedChat"))
    }

    /** Releases the process-wide player so a failed ownership assertion cannot leak a session into another fixture. */
    @After fun resetPlayer() {
        VoicePlaybackController.stop()
    }

    /** Mounts the production voice-versus-speech selector against the supplied account state. */
    private fun render(appState: WhiteNoiseAppState) {
        rule.setContent { WhiteNoiseTheme { PlaybackTransportBar(appState, onBodyClick = {}) } }
    }

    /** Builds one signed-in account with isolated preferences and no durable draft side effects. */
    private fun appState(appContext: Context = context) =
        WhiteNoiseAppState(
            context = appContext,
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi("personal", "id-a", true, false, false, true)),
            activeAccountRef = "personal",
            preferences = context.getSharedPreferences("playback-ownership", Context.MODE_PRIVATE),
        )

    /** Publishes account removal through the real observable state without invoking native account teardown. */
    @Suppress("UNCHECKED_CAST")
    private fun setAccounts(
        appState: WhiteNoiseAppState,
        accounts: List<AccountSummaryFfi>,
    ) {
        val field =
            WhiteNoiseAppState::class.java
                .getDeclaredField("accounts\u0024delegate")
                .apply { isAccessible = true }
        val state = field.get(appState) as androidx.compose.runtime.MutableState<List<AccountSummaryFfi>>
        state.value = accounts
    }

    /** Admits the platform service request synchronously so replacement occurs at the actual Preparing boundary. */
    private class PreparationServiceContext(
        base: Context,
    ) : ContextWrapper(base) {
        var onStart: () -> Unit = {}

        /** Retains the service-boundary fixture when app state requests its application context. */
        override fun getApplicationContext(): Context = this

        /** Fires once; a replacement queue may make its own service request without recursion. */
        override fun startForegroundService(service: Intent): ComponentName {
            val callback = onStart
            onStart = {}
            callback()
            return checkNotNull(service.component)
        }
    }

    private object DiscardedDrafts : DraftPersistence {
        /** Keeps unrelated saved drafts out of playback ownership fixtures. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards fixture draft writes so playback tests cannot persist composer state. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    /** The retained player flow lets the root observe real session transitions without platform codec work. */
    @Suppress("UNCHECKED_CAST")
    private fun setVoice() {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        state.value =
            VoicePlaybackController.PlaybackState(
                key = "voice",
                ready = true,
                sessionId = 10,
                source = VoicePlaybackSource("personal", "group", "message", "Maya"),
            )
    }
}
