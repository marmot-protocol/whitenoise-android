package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The generic person renderers decode an undeclared picture URL in the account's public-profile worker. A group's
 * picture must never reach that worker (#3373), so every generic row that may show a group names its domain.
 */
class GroupPictureDomainCoverageTest {
    /** A generic Avatar or ContactRow given a group animation and a picture URL also names the picture's domain. */
    @Test
    fun genericRowsThatMayShowAGroupPictureNameItsDomain() {
        val calls = uiSources().flatMap { file -> calls(file, "Avatar") + calls(file, "ContactRow") }
        assertTrue("the scan found no generic avatar call", calls.size > 20)
        val undeclared =
            calls.filter { (_, arguments) ->
                "groupAnimation" in arguments &&
                    ("pictureUrl =" in arguments || "avatarUrl =" in arguments) &&
                    "pictureDomain =" !in arguments &&
                    "avatarDomain =" !in arguments
            }
        assertEquals(
            "rows that may show a group picture without its domain",
            emptyList<String>(),
            undeclared.map { it.first },
        )
    }

    /** A group screen's picture availability check loads on a miss, so it names the conversation's domain. */
    @Test
    fun groupScreensCheckPictureAvailabilityInTheirConversation() {
        val calls =
            uiSources()
                .filter { "/ui/group/" in it.invariantSeparatorsPath }
                .flatMap { calls(it, "rememberAvatarImageAvailable") }
        assertTrue("the scan found no group availability check", calls.isNotEmpty())
        val undeclared =
            calls.filterNot { (_, arguments) -> "mediaTrustDomain" in arguments || "pictureDomain" in arguments }
        assertEquals("group availability checks without a domain", emptyList<String>(), undeclared.map { it.first })
    }

    /** Every call of [name] in [file], skipping its declaration, as a location and its argument text. */
    private fun calls(
        file: File,
        name: String,
    ): List<Pair<String, String>> {
        val text = file.readText()
        return Regex("""(?<![A-Za-z0-9_])$name\(""")
            .findAll(text)
            .filterNot { text.substring(maxOf(0, it.range.first - 4), it.range.first) == "fun " }
            .map { match ->
                val start = match.range.last + 1
                var depth = 1
                var end = start
                while (end < text.length && depth > 0) {
                    when (text[end]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    end++
                }
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.name}:$line" to text.substring(start, end)
            }.toList()
    }

    /** The app's main UI sources, from either the module or the repository root. */
    private fun uiSources(): List<File> {
        val root =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/ui"),
                File("app/src/main/java/dev/ipf/whitenoise/android/ui"),
            ).first(File::isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
