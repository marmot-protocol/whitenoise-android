package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationGroupSystemTextCoverageTest {
    @Test
    fun notificationSystemTextHandlesNonRenameGroupSystemEvents() {
        val body = appStateFunctionBody("notificationGroupSystemText")

        assertTrue(
            "group-system notification enrichment must not return before non-rename member/admin events",
            "if (!MessageProjector.isGroupSystemKind(record.kind)) return null" in body &&
                "GroupSystemEvents.renameDiffNames(event)" in body &&
                "GroupSystemEvents.renameDiffNames(event) ?: return null" !in body,
        )
        assertTrue(
            "member/admin system notifications should reuse the localized group-system summary path",
            "GroupSystemEvents.summary(" in body &&
                "subjectIsSelf = GroupSystemEvents.isSelf(update.accountIdHex, subjectHex)" in body &&
                "copy = notificationGroupSystemCopy()" in body,
        )
    }

    @Test
    fun postingNotificationKeepsConversationTitleForNonRenameSystemRows() {
        val body = appStateFunctionBody("postNotificationUpdate")

        assertTrue(
            "non-rename system rows should override body text without replacing the conversation title",
            "systemText?.body" in body &&
                "systemText?.title ?: notificationConversationTitle(update)" in body,
        )
    }

    private fun appStateFunctionBody(functionName: String): String = appStateSource().readText().kotlinFunctionBody(functionName)

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
        while (index < length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(openBrace, index + 1)
                }
            }
            index += 1
        }
        error("Unclosed $description")
    }
}
