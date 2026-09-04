package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.postBeforeNotificationEnrichment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationPushWakeDrainCoverageTest {
    @Test
    fun pushWakeRuntimeStartAwaitsNotificationDrain() {
        val service = serviceFunctionBody("startNotificationRuntimeForTrigger")

        assertTrue(
            "push wake starts must await one notification drain before releasing their wakelock",
            "trigger == ForegroundStartTrigger.PushWake" in service &&
                "appState.ensureNotificationRuntimeStartedAndAwaitPushDrain()" in service &&
                "appState.ensureNotificationRuntimeStarted()" in service,
        )
    }

    @Test
    fun duplicateStartsShareOneBootstrapOwner() {
        val onStart = serviceFunctionBody("onStartCommand")

        assertEquals(
            "only the BootstrapAndKeep branch may launch a service-owned bootstrap",
            1,
            Regex("serviceScope\\.launch").findAll(onStart).count(),
        )
        assertTrue(
            "queued push wakes must be folded into the active bootstrap instead of launching waiters",
            "pendingPushWakeGeneration" in onStart &&
                "KeepRunningExistingBootstrap" in onStart &&
                "Do not" in onStart,
        )
    }

    @Test
    fun pushWakeBootstrapHoldsWakeLockAcrossDrainAwait() {
        val source = serviceSource().readText()
        val attemptMarker = "private suspend fun superviseRuntimeAttempt("
        val nextFunctionMarker = "private suspend fun drainPendingNativePushRegistrationSync("
        val attemptStart = source.indexOf(attemptMarker)
        require(attemptStart >= 0) { "Missing superviseRuntimeAttempt" }
        val attemptEnd = source.indexOf(nextFunctionMarker, startIndex = attemptStart)
        require(attemptEnd > attemptStart) { "Missing function after superviseRuntimeAttempt" }
        val attempt = source.substring(attemptStart, attemptEnd)

        assertTrue(
            "bootstrap branch must call the drain-aware runtime start before releasing the wake lock",
            Regex(
                """val\s+wakeLock\s*=\s*acquirePushWakeLockIfNeeded\(trigger\).*""" +
                    """startNotificationRuntimeForTrigger\(appState,\s*trigger\).*""" +
                    """finally\s*\{\s*releaseWakeLock\(wakeLock\).*""" +
                    """RecoveryTrace\.endPushWakeLock\(wakeLockTrace\).*""" +
                    """wakeLockTraceTimeout\?\.cancel\(\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(attempt),
        )
        assertTrue(
            "the trace must close at the platform wake-lock timeout if bootstrap outlives it",
            "delay(pushWakeLockTimeoutMs())" in attempt &&
                "RecoveryTrace.endPushWakeLock(token)" in attempt,
        )
    }

    @Test
    fun nativePushSyncFlagClearsOnlyAfterSuccessfulSync() {
        val drain = serviceFunctionBody("drainPendingNativePushRegistrationSync")

        assertTrue(
            "a failed native-push sync must remain pending for the supervisor retry",
            drain.indexOf("appState.syncNativePushRegistrationIfEnabled()") in 0 until
                drain.indexOf("pendingNativePushRegistrationSync = false"),
        )
    }

    @Test
    fun pushWakeLockTimeoutCoversDrainBootstrapAndNativePushSyncBudgets() {
        assertEquals(30_000L, pushWakeLockTimeoutMs())
        assertEquals(35_000L, pushWakeLockTimeoutMs(pushDrainTimeoutMs = 10_000L, bootstrapBudgetMs = 5_000L, nativePushSyncBudgetMs = 20_000L))
    }

    @Test
    fun appStateDrainWaiterStartsBeforeRuntimeBootstrap() {
        val appState = appStateSource().readText()
        val wait = appStateFunctionBody("ensureNotificationRuntimeStartedAndAwaitPushDrain")
        val postMaintenance = appStateFunctionBody("schedulePostNotificationMaintenance")

        assertTrue(
            "drain waiter must be active before ensureNotificationRuntimeStarted can process a fast update",
            "async(start = CoroutineStart.UNDISPATCHED)" in wait &&
                wait.indexOf("async(start = CoroutineStart.UNDISPATCHED)") < wait.indexOf("ensureNotificationRuntimeStarted()") &&
                "notificationDrainSignals.first" in wait,
        )
        assertTrue(
            "notification update processing must signal push-wake drain completion",
            "signalNotificationDrain()" in postMaintenance &&
                "notificationDrainSignals.tryEmit(notificationDrainSequence.incrementAndGet())" in appState,
        )
    }

    @Test
    fun firstNotificationPostsBeforeOptionalEnrichmentIsScheduled() =
        runBlocking {
            val calls = mutableListOf<String>()
            var pendingResolver: (suspend () -> Unit)? = null
            val listener = appStateFunctionBody("runNotificationListenerLoop")
            val updateProcessing = appStateFunctionBody("processNotificationUpdate")

            val posted =
                postBeforeNotificationEnrichment(
                    post = {
                        calls += "post"
                        true
                    },
                    scheduleEnrichment = {
                        calls += "schedule-enrichment"
                        pendingResolver = { calls += "resolver-finished" }
                    },
                )

            assertTrue(posted)
            assertEquals(listOf("post", "schedule-enrichment"), calls)
            checkNotNull(pendingResolver).invoke()
            assertEquals(listOf("post", "schedule-enrichment", "resolver-finished"), calls)
            assertTrue(
                "the subscription may resolve bounded local identity before posting, " +
                    "but must schedule optional enrichment afterward",
                "processNotificationUpdate(update)" in listener &&
                    "postBeforeNotificationEnrichment(" in updateProcessing &&
                    "val postEpoch = notificationPostEpoch.capture()" in updateProcessing &&
                    "val engineMuted = engineNotificationMuted(update)" in updateProcessing &&
                    "val firstPost" in updateProcessing &&
                    "post = { postInitialNotificationUpdate(update, firstPost) }" in updateProcessing &&
                    "scheduleNotificationEnrichment(update, firstPost, receivedAtElapsedMs)" in
                    updateProcessing,
            )
        }

    @Test
    fun rejectedInitialPostDoesNotScheduleEnrichment() =
        runBlocking {
            var scheduled = false

            val posted =
                postBeforeNotificationEnrichment(
                    post = { false },
                    scheduleEnrichment = {
                        scheduled = true
                    },
                )

            assertEquals(false, posted)
            assertEquals(false, scheduled)
        }

    @Test
    fun rejectedForegroundServicePushWakeStopsBeforeRecordingOffMain() {
        val onStart = serviceFunctionBody("onStartCommand")
        val recordAfterStop = serviceFunctionBody("recordPendingPushWakeCatchUpAfterStop")

        assertTrue(
            "onStartCommand must stop synchronously before scheduling the durable catch-up marker",
            "stopSelf(startId)" in onStart &&
                "recordPendingPushWakeCatchUpAfterStop()" in onStart &&
                "recordPendingPushWakeCatchUp(applicationContext)" !in onStart,
        )
        assertTrue(
            "rejected push-wake starts must persist the marker off-main on an owned scope, not gating stopSelf",
            "applicationScope.launch" in recordAfterStop &&
                "recordPendingPushWakeCatchUp(applicationContext)" in recordAfterStop &&
                "stopSelf(startId)" !in recordAfterStop &&
                "CoroutineScope(" !in recordAfterStop,
        )
    }

    @Test
    fun everyPushWakeRecordsDurableCatchUpBeforeStartingTheService() {
        val wake = firebaseServiceFunctionBody("wakeForegroundStream")

        assertTrue(
            "an accepted foreground start can still fail in-runtime, so every wake must be durable before start()",
            "recordPendingPushWakeCatchUp()" in wake &&
                wake.indexOf("recordPendingPushWakeCatchUp()") <
                wake.indexOf("NotificationStreamForegroundService.start("),
        )
    }

    /** Verifies connectivity recovery and push wake share one ordered drain owner. */
    @Test
    fun pendingPushWakeDrainUsesSingleFlightAndGenerationClear() {
        val appState = appStateSource().readText()
        val drain = appStateFunctionBody("drainPendingPushWakeCatchUpIfNeeded")
        val clearObserved = appStateFunctionBody("clearPendingPushWakeCatchUpIfObserved")
        val reconnect = notificationNetworkRecoverySource().readText().kotlinFunctionBody("schedule")
        val schedule = appStateFunctionBody("schedulePendingPushWakeCatchUpDrain")
        val expectedPushWakeCatchUp =
            Regex(
                """catchUpAfterObservedPushWake\(\s*""" +
                    """pendingGeneration\s*=\s*pendingGeneration,\s*""" +
                    """trigger\s*=\s*PerformanceTrigger\.PUSH_WAKE,\s*\)""",
            )

        assertTrue(
            "drain must capture the durable marker generation it observed before catch-up",
            "pendingPushWakeCatchUpGeneration()" in drain,
        )
        assertTrue(
            "drain must label its catch-up with the closed push-wake trigger",
            expectedPushWakeCatchUp.containsMatchIn(drain),
        )
        assertTrue(
            "clear helper must only clear the observed durable marker generation",
            "clearPendingPushWakeCatchUp(pendingGeneration)" in clearObserved,
        )
        assertTrue(
            "connectivity callbacks must defer push-wake drain while reconnect owns receiver readiness",
            "notificationNetworkRecovery.isActive()" in schedule &&
                "notificationPushWakeRecoveryCircuit.allowsIndependentDrain" in schedule &&
                "notificationPushWakeRecoveryCircuit.claimIndependentDrain" in schedule &&
                "pushWakeCatchUpDrainJob.startIfInactive" in schedule &&
                "notificationScope" in schedule &&
                ".launch {" in schedule &&
                "ensureNotificationRuntimeStarted()" in schedule,
        )
        assertTrue(
            "recovery exhaustion must block the exact network and durable-wake trigger it attempted",
            "onRecoveryAttemptStarted =" in appState &&
                "notificationPushWakeRecoveryCircuit.noteRecoveryAttempt" in appState &&
                "onRecoveryExhausted =" in appState &&
                "notificationPushWakeRecoveryCircuit.noteRecoveryExhausted" in appState,
        )
        assertTrue(
            "reconnect completion must retry a pending marker when its catch-up did not clear it",
            "invokeOnCompletion" in reconnect &&
                "onDrainCompleted()" in reconnect &&
                "onDrainCompleted = ::schedulePendingPushWakeCatchUpDrain" in appState,
        )
        assertTrue(
            "foreground catch-up must use the same generation clear helper as runtime-start drains",
            "clearPendingPushWakeCatchUpIfObserved" in appState,
        )
    }

    private fun serviceFunctionBody(functionName: String): String = serviceSource().readText().kotlinFunctionBody(functionName)

    private fun appStateFunctionBody(functionName: String): String = appStateSource().readText().kotlinFunctionBody(functionName)

    private fun firebaseServiceFunctionBody(functionName: String): String {
        val source = firebaseServiceSource().readText()
        return source.kotlinFunctionBody(functionName)
    }

    private fun serviceSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationStreamForegroundService.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/notifications/NotificationStreamForegroundService.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing NotificationStreamForegroundService.kt source file")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")

    /** Locates the isolated network-recovery coordinator source. */
    private fun notificationNetworkRecoverySource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/NotificationNetworkRecovery.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/NotificationNetworkRecovery.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing NotificationNetworkRecovery.kt source file")

    private fun firebaseServiceSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/notifications/MarmotFirebaseMessagingService.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/notifications/MarmotFirebaseMessagingService.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MarmotFirebaseMessagingService.kt source file")

    private fun String.kotlinFunctionBody(functionName: String): String {
        val start =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(this)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        return kotlinBlockFrom(braceStart, "function $functionName")
    }

    private fun String.kotlinBlockFrom(
        openBrace: Int,
        description: String,
    ): String {
        require(getOrNull(openBrace) == '{') { "Missing opening brace for $description" }

        var depth = 0
        var index = openBrace
        var inLineComment = false
        var blockCommentDepth = 0
        var inString = false
        var inTripleString = false
        var inChar = false

        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            when {
                inLineComment -> {
                    if (current == '\n' || current == '\r') inLineComment = false
                    index += 1
                }
                blockCommentDepth > 0 -> {
                    when {
                        current == '/' && next == '*' -> {
                            blockCommentDepth += 1
                            index += 2
                        }
                        current == '*' && next == '/' -> {
                            blockCommentDepth -= 1
                            index += 2
                        }
                        else -> index += 1
                    }
                }
                inTripleString -> {
                    if (startsWith("\"\"\"", index)) {
                        inTripleString = false
                        index += 3
                    } else {
                        index += 1
                    }
                }
                inString -> {
                    when (current) {
                        '\\' -> index += 2
                        '"' -> {
                            inString = false
                            index += 1
                        }
                        else -> index += 1
                    }
                }
                inChar -> {
                    when (current) {
                        '\\' -> index += 2
                        '\'' -> {
                            inChar = false
                            index += 1
                        }
                        else -> index += 1
                    }
                }
                else -> {
                    when {
                        current == '/' && next == '/' -> {
                            inLineComment = true
                            index += 2
                        }
                        current == '/' && next == '*' -> {
                            blockCommentDepth = 1
                            index += 2
                        }
                        startsWith("\"\"\"", index) -> {
                            inTripleString = true
                            index += 3
                        }
                        current == '"' -> {
                            inString = true
                            index += 1
                        }
                        current == '\'' -> {
                            inChar = true
                            index += 1
                        }
                        current == '{' -> {
                            depth += 1
                            index += 1
                        }
                        current == '}' -> {
                            depth -= 1
                            index += 1
                            if (depth == 0) return substring(openBrace, index)
                        }
                        else -> index += 1
                    }
                }
            }
        }

        error("Unterminated block for $description")
    }
}
