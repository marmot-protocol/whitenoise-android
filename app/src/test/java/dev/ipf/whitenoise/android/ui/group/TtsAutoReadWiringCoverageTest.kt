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
    fun conversationScreenOpenIdleTriggerUsesAnchoredBacklogAndAutoReadOwnership() {
        val body = source("ui/conversation/ConversationScreen.kt")
        val openEffectStart = body.indexOf("LaunchedEffect(controller, chat.id, initialTimelineAnchored)")
        val openEffectEnd = body.indexOf("// Live continuation:", openEffectStart)
        val openEffect = body.substring(openEffectStart, openEffectEnd)

        assertTrue(
            "open-time auto-read must wait for timeline anchor",
            "if (!initialTimelineAnchored) return@LaunchedEffect" in openEffect,
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
    fun conversationScreenLiveContinuationSkipsSeedAndRequiresOwnedSession() {
        val body = source("ui/conversation/ConversationScreen.kt")
        val liveEffectStart = body.indexOf("LaunchedEffect(controller, chat.id) {")
        val liveEffectEnd = body.indexOf("// Auto-read return-from-background:", liveEffectStart)
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
            "live continuation must only extend active speech",
            "TtsState.Speaking" in liveEffect && "TtsState.Paused" in liveEffect,
        )
        assertTrue(
            "live continuation must append rather than replace",
            "appendSpeech(entry" in liveEffect,
        )
    }

    @Test
    fun speakFromHereClaimsAutoReadOwnershipThroughAppState() {
        val body = source("ui/conversation/messages/MessageBubble.kt").functionBody("speakFromHere")

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

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing source file: $relativePath")
}
