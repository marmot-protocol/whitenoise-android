package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserFacingErrorArchitectureTest {
    @Test
    fun rawThrowableDetailsDoNotFlowIntoUiSinks() {
        val sourceRoot =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android"),
                File("app/src/main/java/dev/ipf/whitenoise/android"),
            ).first(File::isDirectory)
        val violations =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "DiagnosticsScreen.kt" }
                .flatMap { file ->
                    rawThrowableUiSinkLines(file.readText()).map { line ->
                        "${file.relativeTo(sourceRoot)}:$line"
                    }
                }.toList()

        assertTrue("Raw Throwable detail reached a UI sink:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun detectorCatchesMultilineUiFlowButAllowsResolvedAppErrors() {
        val unsafe =
            """
            presentText(
                AppText.Plain(
                    throwable
                        .message.orEmpty(),
                ),
            )
            """.trimIndent()
        val safe =
            """
            Text(
                error.message
                    .resolve(context),
            )
            """.trimIndent()

        assertTrue(rawThrowableUiSinkLines(unsafe).isNotEmpty())
        assertFalse(rawThrowableUiSinkLines(safe).isNotEmpty())
    }

    private fun rawThrowableUiSinkLines(source: String): List<Int> {
        val sink = Regex("\\b(?:AppText\\.Plain|AppPhase\\.Failed|onError|Text|present|presentText)\\s*\\(")
        val rawDetail =
            Regex(
                "\\b(?:throwable|exception|cause|it)\\s*\\.\\s*(?:message\\b|javaClass\\b|readableMessage\\s*\\(\\))" +
                    "|\\berror\\s*\\.\\s*(?:message\\b(?!\\s*\\.\\s*resolve)|javaClass\\b|readableMessage\\s*\\(\\))",
            )
        return sink
            .findAll(source)
            .mapNotNull { match ->
                val openParen = source.indexOf('(', match.range.first)
                val end = matchingCallEnd(source, openParen) ?: return@mapNotNull null
                if (rawDetail.containsMatchIn(source.substring(match.range.first, end + 1))) {
                    source.take(match.range.first).count { it == '\n' } + 1
                } else {
                    null
                }
            }.distinct()
            .toList()
    }

    private fun matchingCallEnd(
        source: String,
        openParen: Int,
    ): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in openParen until source.length) {
            val character = source[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                continue
            }
            when (character) {
                '\'', '"' -> quote = character
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }
}
