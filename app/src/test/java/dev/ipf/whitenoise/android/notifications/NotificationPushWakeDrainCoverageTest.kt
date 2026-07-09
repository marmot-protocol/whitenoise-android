package dev.ipf.whitenoise.android.notifications

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
    fun pushWakeBootstrapHoldsWakeLockAcrossDrainAwait() {
        val source = serviceSource().readText()

        assertTrue(
            "bootstrap branch must call the drain-aware runtime start before releasing the wake lock",
            Regex(
                """val\s+wakeLock\s*=\s*acquirePushWakeLockIfNeeded\(trigger\).*""" +
                    """startNotificationRuntimeForTrigger\(appState,\s*trigger\).*""" +
                    """finally\s*\{\s*releaseWakeLock\(wakeLock\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(source),
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
        val postUpdate = appStateFunctionBody("postNotificationUpdate")

        assertTrue(
            "drain waiter must be active before ensureNotificationRuntimeStarted can process a fast update",
            "async(start = CoroutineStart.UNDISPATCHED)" in wait &&
                wait.indexOf("async(start = CoroutineStart.UNDISPATCHED)") < wait.indexOf("ensureNotificationRuntimeStarted()") &&
                "notificationDrainSignals.first" in wait,
        )
        assertTrue(
            "notification update processing must signal push-wake drain completion",
            "signalNotificationDrain()" in postUpdate &&
                "notificationDrainSignals.tryEmit(notificationDrainSequence.incrementAndGet())" in appState,
        )
    }

    private fun serviceFunctionBody(functionName: String): String = serviceSource().readText().kotlinFunctionBody(functionName)

    private fun appStateFunctionBody(functionName: String): String = appStateSource().readText().kotlinFunctionBody(functionName)

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
