package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsAutoReadWiringCoverageTest {
    @Test
    fun textToSpeechScreenWiresGlobalDefaultToAppState() {
        val body = source("ui/settings/TextToSpeechScreen.kt").functionBody("TextToSpeechScreen")

        assertTrue("global default must read preference state", "ttsAutoReadPrefs" in body)
        assertTrue(
            "global default toggle must persist through AppState",
            "appState.setTtsAutoReadGlobalDefault(it)" in body,
        )
        assertTrue("global default row must be composed", "TtsAutoReadGlobalDefaultRow(" in body)
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
        val openEffectEnd = body.indexOf("// Live continuation", openEffectStart)
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
    fun conversationTtsEffectsLiveContinuationSkipsSeedAndRequiresOwnedSession() {
        val body = source("ui/conversation/ConversationTtsEffects.kt")
        val liveEffectStart = body.indexOf("LaunchedEffect(controller, chatId, transcriptReadyToReveal) {")
        val liveEffectEnd = body.indexOf("// On a real foreground return", liveEffectStart)
        val liveEffect = body.substring(liveEffectStart, liveEffectEnd)

        assertTrue(
            "live continuation must seed the current tail without speaking",
            "if (!seededLastId)" in liveEffect,
        )
        assertTrue(
            "live continuation must gate on session ownership",
            "ownsTtsAutoReadSession(controller.group.groupIdHex)" in liveEffect,
        )
        assertTrue(
            "live continuation must reject a withheld transcript",
            "if (!transcriptReadyToReveal) return@collect" in liveEffect,
        )
        assertTrue(
            "live continuation must only extend active speech",
            "TtsState.Speaking" in liveEffect && "TtsState.Paused" in liveEffect,
        )
        assertTrue(
            "live continuation must append rather than replace",
            "appendSpeech(entry" in liveEffect,
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
                .size > 2,
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
            "live continuation must reject hidden rows before appending speech",
            "if (!transcriptReadyToReveal) return@collect" in effects,
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
        val body = source("ui/conversation/messages/MessageBubble.kt").functionBody("startSpeakAloud")

        assertTrue(
            "speak-from-here must build bounded candidates",
            "ttsSpeakFromHereCandidates(" in body,
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
        val body = source("ui/conversation/messages/MessageBubble.kt").functionBody("startSpeakAloud")

        assertTrue("the entry position must participate in mode selection", "mapIndexed" in body)
        assertTrue("only the selected first entry may become literal code", "index == 0 && literalCode" in body)
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing source file: $relativePath")
}
