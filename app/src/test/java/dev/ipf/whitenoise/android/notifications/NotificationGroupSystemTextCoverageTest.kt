package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationGroupSystemTextCoverageTest {
    /** Keeps every structured system event on the localized projection path, not only renames. */
    @Test
    fun notificationSystemTextHandlesNonRenameGroupSystemEvents() {
        val body =
            resolutionSource()
                .readText()
                .substringAfter("internal class NotificationGroupSystemTextResolver")
                .substringBefore("internal fun notificationGroupSystemCopy")

        assertTrue(
            "group-system notification enrichment must not return before non-rename member/admin events",
            ".takeIf { MessageProjector.isGroupSystemKind(it.kind) }" in body &&
                "GroupSystemEvents.resolve(record)?.let { event ->" in body &&
                "val diff = GroupSystemEvents.renameDiffNames(event)" in body &&
                "GroupSystemEvents.renameDiffNames(event) ?: return null" !in body,
        )
        assertTrue(
            "member/admin system notifications should reuse the localized group-system summary path",
            "GroupSystemEvents.summary(" in body &&
                "subjectIsSelf = GroupSystemEvents.isSelf(update.accountIdHex, subjectHex)" in body &&
                "copy = notificationGroupSystemCopy(context)" in body,
        )
    }

    /** Keeps ordinary system rows under their conversation title while rename rows may override it. */
    @Test
    fun postingNotificationKeepsConversationTitleForNonRenameSystemRows() {
        val body =
            resolutionSource()
                .readText()
                .substringAfter("internal class NotificationFirstPostResolver")
                .substringBefore("internal fun createNotificationContentResolutionServices")
                .kotlinFunctionBody("resolve")

        assertTrue(
            "non-rename system rows should override body text without replacing the conversation title",
            "system?.body" in body &&
                "system?.title ?: conversationTitle.resolve(update, localOnly)" in body,
        )
    }

    /** Finds the extracted notification-resolution source from either Gradle test working directory. */
    private fun resolutionSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/NotificationFirstPostResolution.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/NotificationFirstPostResolution.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing NotificationFirstPostResolution.kt source file")

    /** Extracts a block-bodied Kotlin function from the class-scoped source slice. */
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

    /** Returns one balanced Kotlin brace block for stable source-wiring assertions. */
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
