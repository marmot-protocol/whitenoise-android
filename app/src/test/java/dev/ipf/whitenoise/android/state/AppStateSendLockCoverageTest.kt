package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the two AppState send paths that cannot be reached through
 * ConversationController's behavioral send tests.
 *
 * WhiteNoiseAppState construction needs Android Context plus the real Marmot FFI,
 * so this JVM test verifies the structural invariant: each commit-producing send
 * calls sendText only from inside the existing per-group commit lock.
 */
class AppStateSendLockCoverageTest {
    @Test
    fun forwardTextLocksEachTargetSend() {
        val body = appStateFunctionBody("forwardText")

        assertTrue(
            "forwardText must serialize each forwarded send through the per-group commit lock",
            Regex(
                """for\s*\(\s*groupIdHex\s+in\s+targets\s*\).*""" +
                    """withGroupCommitLock\s*\(\s*account\s*,\s*groupIdHex\s*\).*""" +
                    """marmotIo\s*\{\s*sendText\s*\(\s*account\s*,\s*groupIdHex\s*,\s*trimmed\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    @Test
    fun notificationReplyLocksSend() {
        val body = appStateFunctionBody("sendNotificationReply")

        assertTrue(
            "notification quick replies must serialize sendText through the per-group commit lock",
            Regex(
                """withGroupCommitLock\s*\(\s*account\s*,\s*group\s*\).*""" +
                    """marmotIo\s*\{\s*sendText\s*\(\s*account\s*,\s*group\s*,\s*body\s*\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    private fun appStateFunctionBody(functionName: String): String {
        val source = appStateSource().readText()
        val start =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(source)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        return source.kotlinBlockFrom(braceStart, "function $functionName")
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")

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
