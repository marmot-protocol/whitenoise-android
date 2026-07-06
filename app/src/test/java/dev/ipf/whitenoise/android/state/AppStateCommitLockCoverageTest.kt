package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppStateCommitLockCoverageTest {
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
        val source = File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt").readText()
        val start = source.indexOf("fun $functionName(")
        require(start >= 0) { "Missing function $functionName" }
        val braceStart = source.indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }

        var depth = 0
        for (index in braceStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(braceStart, index + 1)
                }
            }
        }
        error("Unterminated body for $functionName")
    }
}
