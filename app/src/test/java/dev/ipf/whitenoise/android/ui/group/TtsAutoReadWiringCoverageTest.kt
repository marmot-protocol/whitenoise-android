package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsAutoReadWiringCoverageTest {
    /** Text to speech screen wires global default to app state. */
    @Test
    fun textToSpeechScreenWiresGlobalDefaultToAppState() {
        val body = source("ui/settings/TextToSpeechScreen.kt").functionBody("TextToSpeechScreen")

        assertTrue("global default must read preference state", "ttsAutoReadPrefs" in body)
        assertTrue(
            "global default toggle must persist through AppState",
            "appState.setTtsAutoReadGlobalDefault(it)" in body,
        )
        assertTrue("global default row must be composed", "TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG" in body)
    }

    @Test
    fun groupDetailsScreenWiresPerChatOverridePicker() {
        val body = source("ui/group/GroupDetailsScreen.kt")

        assertTrue("group details must observe auto-read prefs", "ttsAutoReadPrefs" in body)
        assertTrue("group details must resolve per-account override", "overrideFor(accountRef" in body)
        assertTrue("picker must be shown from the action row", "showAutoReadPicker = true" in body)
        assertTrue(
            "picker must delegate to AppState",
            "setConversationAutoReadOverride(controller.group.groupIdHex" in body,
        )
        assertTrue(
            "auto-read row must hide without a usable engine",
            "if (appState.ttsHasUsableEngine)" in body,
        )
    }

    @Test
    fun conversationTtsEffectsOpenIdleTriggerUsesRevealedBacklogAndAutoReadOwnership() {
        val body = source("ui/conversation/ConversationTtsEffects.kt")
        val openEffectStart = body.indexOf("LaunchedEffect(controller, chatId, transcriptReadyToReveal)")
        val openEffectEnd = body.indexOf("// The process-owned continuation", openEffectStart)
        val openEffect = body.substring(openEffectStart, openEffectEnd)

        assertTrue(
            "open-time auto-read must wait for the authoritative transcript reveal",
            "if (!transcriptReadyToReveal) return@LaunchedEffect" in openEffect,
        )
        assertTrue(
            "open-time auto-read must use bounded backlog helper",
            "autoReadBacklogEntries()" in openEffect,
        )
        assertTrue(
            "open-time auto-read must use auto-read session ownership",
            "speakAloudAutoRead(" in openEffect,
        )
        assertFalse(
            "open-time auto-read must not use manual speech entry",
            "appState.speakAloud(" in openEffect,
        )
        val backlogHelper =
            body.substring(
                body.indexOf("suspend fun autoReadBacklogEntries()"),
                body.indexOf("// Auto-read (#1483):"),
            )
        assertTrue(
            "open backlog must respect effective auto-read setting",
            "appState.isConversationAutoRead(controller.group.groupIdHex)" in backlogHelper,
        )
    }

    @Test
    fun processOwnedLiveContinuationUsesNativeWindowsAndRejectsReplacedSessions() {
        val body = source("state/TtsAutoReadContinuation.kt")
        val app = source("state/AppState.kt")

        assertTrue(
            "both auto-read starts must attach their own native continuation",
            app.split("ttsAutoReadContinuation.start(").size == 3,
        )
        assertTrue(
            "live speech must use the existing native window seam",
            "conversationLiveSubscriptions().openTimeline(" in body,
        )
        assertTrue(
            "the anchor must come from the accepted speech queue",
            ".queuedMessagesSnapshot()" in body.functionBody("start"),
        )
        assertTrue("live speech must retain the exact account owner", "appState.activeAccountRef == account" in body)
        assertTrue("live speech must retain the exact conversation owner", "ownsTtsAutoReadSession(group)" in body)
        assertTrue("replacement playback must invalidate the native consumer", "state.sessionId == run.session" in body)
        assertTrue(
            "only speaking and paused sessions may append",
            "state is TtsState.Speaking || state is TtsState.Paused" in body,
        )
        assertTrue("live speech must consume complete native windows", "active.nextWindow()" in body)
        assertTrue(
            "live speech must append rather than replace",
            "host.controller.appendSpeech(entry, run.locale)" in body,
        )
        assertFalse(
            "the disposed screen must not own a competing live collector",
            "if (!seededLastId)" in source("ui/conversation/ConversationTtsEffects.kt"),
        )
    }

    /** Withheld notification transcripts must gate every automatic speech lane, not only paint. */
    @Test
    fun notificationTranscriptRevealOwnsOpenLiveAndResumeAutoRead() {
        val effects = source("ui/conversation/ConversationTtsEffects.kt").replace(Regex("\\s+"), " ")
        val screen = source("ui/conversation/ConversationScreen.kt").replace(Regex("\\s+"), " ")

        assertTrue(
            "the screen must pass the same reveal owner used by paint and accessibility",
            "transcriptReadyToReveal = transcriptReadyToReveal" in screen,
        )
        assertTrue(
            "open-time auto-read must restart only for a revealed transcript",
            effects
                .split("LaunchedEffect(controller, chatId, transcriptReadyToReveal)")
                .size == 2,
        )
        assertTrue(
            "foreground-return auto-read must retry when the withheld transcript reveals",
            "LaunchedEffect(controller, chatId, autoReadResumeGeneration, transcriptReadyToReveal)" in effects,
        )
        assertTrue(
            "a hidden or cancelled resume generation must not replay when reveal changes",
            "handledAutoReadResumeGeneration = autoReadResumeGeneration" in effects,
        )
        assertTrue(
            "open and resume speech must fail closed before their cancellable projection work",
            effects
                .split("if (!transcriptReadyToReveal) return@LaunchedEffect")
                .size > 2,
        )
        assertTrue(
            "reveal must not replace an already owned background queue",
            "if (appState.ownsTtsAutoReadSession(controller.group.groupIdHex)) return@LaunchedEffect" in effects,
        )
    }

    @Test
    fun conversationScreenDelegatesTtsOrchestrationOutOfItsGeneratedMethod() {
        val body = source("ui/conversation/ConversationScreen.kt")

        assertTrue("screen must delegate auto-read orchestration", "ConversationTtsAutoReadEffects(" in body)
        assertTrue("screen must delegate follow orchestration", "ConversationTtsFollowEffects(" in body)
        assertFalse("screen must not inline auto-read backlog work", "autoReadBacklogEntries" in body)
        assertFalse(
            "screen must not inline the TTS StateFlow collector",
            "ttsController.state.collectAsState" in body,
        )
    }

    @Test
    fun speakFromHereClaimsAutoReadOwnershipThroughAppState() {
        val source = source("ui/conversation/messages/MessageBubble.kt")
        val body = source.functionBody("startSpeakAloud")
        val entries = source.substringAfter("suspend fun speakFromHereEntries").substringBefore("fun startSpeakAloud")

        assertTrue(
            "speak-from-here must build bounded candidates through the shared preparation helper",
            "val entries = speakFromHereEntries(literalCode)" in body && "ttsSpeakFromHereCandidates(" in entries,
        )
        assertTrue(
            "speak-from-here must claim auto-read ownership",
            "speakAloudAutoRead(" in body,
        )
        assertFalse(
            "speak-from-here must not use manual-only speech",
            "appState.speakAloud(" in body,
        )
    }

    @Test
    fun asynchronousSpeechOwnershipUsesTheAccountCapturedBeforePreparation() {
        val source = source("state/AppState.kt")

        assertTrue(
            "both asynchronous manual-speech ownership writes must use the captured account",
            Regex("ttsSpeechAccountRef = ownerAccount").findAll(source).count() >= 2,
        )
        assertTrue(
            "auto-read history ownership must use the captured account",
            "ttsHistorySession.onConversationSessionStarted(ownerAccount, groupIdHex)" in source,
        )
    }

    @Test
    fun literalCodeModeAppliesOnlyToTheSelectedSpeakFromHereEntry() {
        val source = source("ui/conversation/messages/MessageBubble.kt")
        val body = source.functionBody("startSpeakAloud")
        val entries = source.substringAfter("suspend fun speakFromHereEntries").substringBefore("fun startSpeakAloud")

        assertTrue("the requested mode must reach preparation", "speakFromHereEntries(literalCode)" in body)
        assertTrue("the entry position must participate in mode selection", "mapIndexed" in entries)
        assertTrue("only the selected first entry may become literal code", "index == 0 && literalCode" in entries)
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing source file: $relativePath")
}
