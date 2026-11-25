package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RECORD_PATH = "src/main/java/dev/ipf/whitenoise/android/notifications/ConversationForegroundRecord.kt"
private const val VOICE_PATH = "src/main/java/dev/ipf/whitenoise/android/audio/VoiceNoteForegroundService.kt"
private const val DICTATION_PATH =
    "src/main/java/dev/ipf/whitenoise/android/audio/ConversationDictationForegroundService.kt"
private const val TTS_PATH = "src/main/java/dev/ipf/whitenoise/android/audio/tts/TtsPlaybackForegroundService.kt"

private val SERVICE_STOP_CALL = Regex("""\b(?:stopSelf|stopSelfResult|stopService)\s*\(""")
private val RECORD_STOP_CALL = Regex("""\bstopSelf(?:Result)?\s*\(""")
private val COMMENT_OR_STRING = Regex(""""(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/""")

/**
 * Enforces that every capture or playback foreground start satisfies Android's start deadline
 * before its service stops: capture starts are noted as pending, the shared record stops only
 * through one function that satisfies a pending deadline before `stopSelfResult` and detaches
 * foreground only when `stopSelfResult` accepts the stop, and TTS follows the same gating.
 */
class ForegroundServiceStartDeadlineCoverageTest {
    /** The shared capture record stops its service in exactly one place: `removeForegroundAndStop`. */
    @Test
    fun recordStopsTheServiceOnlyThroughRemoveForegroundAndStop() {
        val source = appModuleSource(RECORD_PATH).withoutComments()
        val stopCalls = SERVICE_STOP_CALL.findAll(source).toList()
        assertEquals("ConversationForegroundRecord must stop the service from exactly one call site", 1, stopCalls.size)
        val body = source.functionBody("removeForegroundAndStop")
        val bodyStart = source.indexOf(body)
        assertTrue(
            "the only stopSelf/stopSelfResult/stopService call must live inside removeForegroundAndStop",
            stopCalls.single().range.first in bodyStart until bodyStart + body.length,
        )
    }

    /** The single stop path satisfies a pending start deadline before stopping and detaches only if stopped. */
    @Test
    fun removeForegroundAndStopSatisfiesPendingDeadlineBeforeStoppingAndDetachesOnlyIfStopped() {
        val body = appModuleSource(RECORD_PATH).withoutComments().functionBody("removeForegroundAndStop")
        val deadlineCheck =
            Regex("""val\s+hasUnmetDeadline\s*=\s*satisfyStartDeadline\s*\|\|\s*pendingStartDeadlineId\s*!=\s*null""")
                .find(body)
        assertNotNull(
            "removeForegroundAndStop must compute hasUnmetDeadline from pendingStartDeadlineId",
            deadlineCheck,
        )
        val deadlineGate = Regex("""if\s*\(\s*hasUnmetDeadline\s*&&\s*!foregroundPromoted\s*\)""").find(body)
        assertNotNull("satisfyStartDeadlineBeforeStop must be gated on hasUnmetDeadline", deadlineGate)
        assertTrue(
            "hasUnmetDeadline must be computed before the deadline gate",
            requireNotNull(deadlineCheck).range.first < requireNotNull(deadlineGate).range.first,
        )
        val deadlineBranch =
            body.kotlinBlockFrom(
                openDelimiter = requireNotNull(deadlineGate).range.first,
                description = "deadline branch in removeForegroundAndStop",
                searchForOpening = true,
            )
        assertTrue(
            "the deadline branch must call satisfyStartDeadlineBeforeStop",
            "satisfyStartDeadlineBeforeStop()" in deadlineBranch,
        )
        val satisfyIndex = body.indexOf("satisfyStartDeadlineBeforeStop()")
        val stopIndex = RECORD_STOP_CALL.find(body)?.range?.first ?: -1
        val removeIndex = body.indexOf("foregroundRemover(")
        assertTrue("the deadline placeholder must precede stopping the service", satisfyIndex in 0 until stopIndex)
        assertTrue("stopSelfResult must precede foreground removal", stopIndex in 0 until removeIndex)
        val stoppedBranch =
            requireBranch(
                body = body,
                gate = Regex("""if\s*\(\s*stopped\b"""),
                missingMessage = "foreground removal must be gated on stopSelfResult returning true",
                description = "stopped branch in removeForegroundAndStop",
            )
        assertTrue("foreground removal must stay inside the stopped branch", "foregroundRemover(" in stoppedBranch)
        val downgradeBranch =
            requireBranch(
                body = body,
                gate = Regex("""else\s+if\s*\(\s*foregroundPromoted\s*&&\s*!showingDeadlinePlaceholder\s*\)"""),
                missingMessage = "rejected stopSelfResult must downgrade finished cards to the placeholder",
                description = "downgrade branch in removeForegroundAndStop",
            )
        assertTrue(
            "rejected stop downgrade must publish the specialUse placeholder",
            "publishSpecialUsePlaceholder(" in downgradeBranch,
        )
        assertFalse("rejected stop downgrade must not remove foreground", "foregroundRemover(" in downgradeBranch)
    }

    /** Noting a start records its deadline, and the placeholder satisfies it with specialUse, never microphone. */
    @Test
    fun recordTracksPendingStartsAndSatisfiesThemWithSpecialUse() {
        val source = appModuleSource(RECORD_PATH).withoutComments()
        val notePending = source.functionBody("noteForegroundStartPending")
        val satisfy = source.functionBody("satisfyStartDeadlineBeforeStop")
        val placeholder = source.functionBody("publishSpecialUsePlaceholder")
        val idleStop = source.functionBody("stopIfIdleWithoutPlaceholder")
        assertTrue(
            "noteForegroundStartPending must record the start as an unmet deadline",
            Regex("""pendingStartDeadlineId\s*=\s*startId""").containsMatchIn(notePending),
        )
        assertTrue(
            "satisfyStartDeadlineBeforeStop must clear pendingStartDeadlineId",
            Regex("""pendingStartDeadlineId\s*=\s*null""").containsMatchIn(satisfy),
        )
        assertTrue(
            "satisfyStartDeadlineBeforeStop must publish the specialUse placeholder",
            "publishSpecialUsePlaceholder(" in satisfy,
        )
        assertTrue("the placeholder must publish foreground", "foregroundPublisher(" in placeholder)
        assertTrue("the placeholder must use specialUse", "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in placeholder)
        assertFalse(
            "the placeholder must never request microphone",
            "FOREGROUND_SERVICE_TYPE_MICROPHONE" in placeholder,
        )
        assertTrue("idle stop must check hasActiveLease before stopping", "hasActiveLease" in idleStop)
        assertTrue(
            "idle stop must pass satisfyStartDeadline = false",
            Regex("""removeForegroundAndStop\(\s*startId\s*,\s*satisfyStartDeadline\s*=\s*false\s*\)""")
                .containsMatchIn(idleStop),
        )
    }

    /** Capture adapters never stop the service themselves, so every stop goes through the shared record. */
    @Test
    fun captureAdaptersNeverStopTheServiceDirectly() {
        listOf(VOICE_PATH, DICTATION_PATH).forEach { path ->
            val stops = SERVICE_STOP_CALL.findAll(appModuleSource(path).withoutComments()).map { it.value }.toList()
            assertEquals("$path must stop only through ConversationForegroundRecord", emptyList<String>(), stops)
        }
    }

    /** A voice-note ACTION_START notes its deadline before any release or promotion. */
    @Test
    fun voiceStartCommandNotesPendingDeadlineBeforeAnyRelease() {
        val onCommand = appModuleSource(VOICE_PATH).withoutComments().functionBody("onCommand")
        val nonStartIndex = onCommand.indexOf("if (intent.action != ACTION_START)")
        assertTrue("onCommand must isolate non-START voice commands", nonStartIndex >= 0)
        val nonStartBranch =
            onCommand.kotlinBlockFrom(
                openDelimiter = nonStartIndex,
                description = "non-START branch in VoiceNoteForegroundService.onCommand",
                searchForOpening = true,
            )
        assertTrue(
            "non-START STOP/DISCARD actions must release an idle service without publishing a placeholder",
            "stopIfIdleWithoutPlaceholder(startId)" in nonStartBranch,
        )
        val forbiddenNonStartCalls =
            Regex(
                "releaseVoice|promoteVoice|noteForegroundStartPending|" +
                    "satisfyStartDeadlineBeforeStop|publishSpecialUsePlaceholder",
            )
        assertFalse(
            "plain startService STOP/DISCARD actions carry no deadline and must never publish a placeholder",
            forbiddenNonStartCalls.containsMatchIn(nonStartBranch),
        )
        assertNotesBeforeEveryCall(onCommand, Regex("""\b(?:releaseVoice|promoteVoice)\s*\("""))
    }

    /** A dictation ACTION_START notes its deadline before any release or promotion. */
    @Test
    fun dictationStartCommandNotesPendingDeadlineBeforeAnyRelease() {
        val source = appModuleSource(DICTATION_PATH).withoutComments()
        val isStart = source.functionBody("isStartCommand")
        assertTrue("isStartCommand must recognize ACTION_START", "ACTION_START" in isStart)
        listOf("ACTION_CANCEL", "ACTION_PASTE", "ACTION_SEND").forEach { action ->
            assertFalse("drawer action $action is not a foreground start", action in isStart)
        }
        val onStart = source.functionBody("onStartCommand")
        assertTrue(
            "onStartCommand must gate deadline tracking on isStartCommand",
            Regex("""=\s*isStartCommand\(intent\)""").containsMatchIn(onStart),
        )
        assertNotesBeforeEveryCall(onStart, Regex("""\b(?:removeForegroundNotification|promoteOrCancel)\s*\("""))
    }

    /** TTS promotes before every early stop, never calls bare stopSelf(), and detaches only when stopped. */
    @Test
    fun ttsServicePromotesBeforeEarlyStopAndNeverUsesBareStopSelf() {
        val source = appModuleSource(TTS_PATH).withoutComments()
        assertFalse(
            "TtsPlaybackForegroundService must use stopSelfResult(startId) instead of bare stopSelf()",
            Regex("""\bstopSelf\s*\(\s*\)""").containsMatchIn(source),
        )
        val stopHelper = source.functionBody("stopForegroundIfStopped")
        val helperStart = source.indexOf(stopHelper)
        val helperRange = helperStart until helperStart + stopHelper.length
        val stopCalls = SERVICE_STOP_CALL.findAll(source).toList()
        assertEquals("TtsPlaybackForegroundService must stop from exactly one helper", 1, stopCalls.size)
        assertTrue(
            "stopSelfResult must live inside stopForegroundIfStopped",
            stopCalls.single().range.first in helperRange,
        )
        val detachCalls = Regex("""\bstopForeground\s*\(""").findAll(source).toList()
        assertEquals("TtsPlaybackForegroundService must detach only in stopForegroundIfStopped", 1, detachCalls.size)
        assertTrue(
            "stopForeground must live inside stopForegroundIfStopped",
            detachCalls.single().range.first in helperRange,
        )
        val gate = Regex("""if\s*\(\s*stopSelfResult\s*\(\s*startId\s*\)\s*\)""").find(stopHelper)
        assertNotNull("TTS stopForeground must be gated on stopSelfResult(startId)", gate)
        val gateBranch =
            stopHelper.kotlinBlockFrom(
                openDelimiter = requireNotNull(gate).range.first,
                description = "stopSelfResult branch in stopForegroundIfStopped",
                searchForOpening = true,
            )
        assertTrue("stopForeground must run only when stopSelfResult returns true", "stopForeground(" in gateBranch)
        val onStart = source.functionBody("onStartCommand")
        val startForegroundIndex = onStart.indexOf("startForeground(")
        assertTrue("onStartCommand must call startForeground", startForegroundIndex >= 0)
        val earlyStops = Regex("""\bstopForegroundIfStopped\s*\(""").findAll(onStart).toList()
        assertTrue("onStartCommand must route early stops through stopForegroundIfStopped", earlyStops.isNotEmpty())
        earlyStops.forEach { stop ->
            assertTrue("TTS must promote before stopping", stop.range.first > startForegroundIndex)
        }
    }

    /** Manifest must keep specialUse declared on the shared foreground host used for capture teardown. */
    @Test
    fun manifestDeclaresSpecialUseForSharedCaptureForegroundHost() {
        val manifest = appModuleSource("src/main/AndroidManifest.xml")
        val serviceTag =
            Regex("""<service\b[^>]*android:name="\.notifications\.NotificationStreamForegroundService"[^>]*>""")
                .find(manifest)
                ?.value
        assertNotNull("NotificationStreamForegroundService must be declared in AndroidManifest.xml", serviceTag)
        assertTrue(
            "NotificationStreamForegroundService must declare specialUse for start-deadline placeholder promotion",
            "specialUse" in requireNotNull(serviceTag),
        )
    }

    /** Asserts [body] notes its pending start deadline before the first match of [calls]. */
    private fun assertNotesBeforeEveryCall(
        body: String,
        calls: Regex,
    ) {
        val noteIndex = body.indexOf("noteForegroundStartPending(startId)")
        val firstCall = calls.find(body)?.range?.first ?: body.length
        assertTrue("ACTION_START must note its pending deadline before any release", noteIndex in 0 until firstCall)
    }

    /** Returns the braced branch following [gate] in [body], failing with [missingMessage] if absent. */
    private fun requireBranch(
        body: String,
        gate: Regex,
        missingMessage: String,
        description: String,
    ): String {
        val match = gate.find(body)
        assertNotNull(missingMessage, match)
        return body.kotlinBlockFrom(
            openDelimiter = requireNotNull(match).range.first,
            description = description,
            searchForOpening = true,
        )
    }
}

/** Removes Kotlin comments while keeping string literals, so prose never satisfies or trips a scan. */
private fun String.withoutComments(): String {
    val keepStrings: (MatchResult) -> CharSequence = { match -> if (match.value.startsWith('"')) match.value else "" }
    return COMMENT_OR_STRING.replace(this, keepStrings)
}
