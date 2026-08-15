package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.kotlinBlockFrom
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
    fun systemFailureCatchesDoNotDiscardCauseForLegacyCopy() {
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
                    discardedCauseWithLegacyCopyLines(file.readText()).map { line ->
                        "${file.relativeTo(sourceRoot)}:$line"
                    }
                }.toList()

        assertTrue(
            "A system failure discarded its cause before requesting diagnostic Copy:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun discardedCauseWithLegacyCopyIsRejected() {
        val unsafe =
            """
            try {
                prepareImage()
            } catch (_: Exception) {
                appState.present(
                    R.string.toast_couldnt_prepare_image,
                    copyable = true,
                )
            }
            """.trimIndent()
        val validation =
            """
            if (url.isBlank()) {
                appState.present(R.string.profile_picture_invalid, copyable = true)
            }
            """.trimIndent()
        val cancellation =
            """
            try {
                prepareImage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            """.trimIndent()
        val migrated =
            """
            try {
                prepareImage()
            } catch (error: Exception) {
                appState.presentFailure(
                    R.string.toast_couldnt_prepare_image,
                    "GROUP_IMAGE_PREPARE",
                    error,
                )
            }
            """.trimIndent()
        assertTrue(discardedCauseWithLegacyCopyLines(unsafe).isNotEmpty())
        assertTrue(discardedCauseWithLegacyCopyLines(validation).isEmpty())
        assertTrue(discardedCauseWithLegacyCopyLines(cancellation).isEmpty())
        assertTrue(discardedCauseWithLegacyCopyLines(migrated).isEmpty())
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
                val call =
                    source.substring(match.range.first, openParen) +
                        runCatching {
                            source.kotlinBlockFrom(openParen, "user-facing error sink", '(', ')')
                        }.getOrNull().orEmpty()
                if (rawDetail.containsMatchIn(call)) {
                    source.take(match.range.first).count { it == '\n' } + 1
                } else {
                    null
                }
            }.distinct()
            .toList()
    }

    private fun discardedCauseWithLegacyCopyLines(source: String): List<Int> {
        val systemCatch =
            Regex(
                """\bcatch\s*\(\s*[A-Za-z_]\w*\s*:\s*([\w.]*?(?:Exception|Throwable))\s*\)\s*\{""",
            )
        return systemCatch
            .findAll(source)
            // No installed activity is an expected platform capability result,
            // not a system failure with useful diagnostics.
            .filterNot { it.groupValues[1].endsWith("ActivityNotFoundException") }
            .flatMap { catchMatch ->
                val openBrace = source.indexOf('{', catchMatch.range.first)
                val catchBody = source.kotlinBlockFrom(openBrace, "system failure catch").drop(1).dropLast(1)
                legacyCopyCallLines(catchBody).asSequence().map { lineInBody ->
                    source.take(openBrace + 1).count { it == '\n' } + lineInBody
                }
            }.distinct()
            .toList()
    }

    private fun legacyCopyCallLines(source: String): List<Int> {
        val legacySink = Regex("""\b(?:present|presentText)\s*\(""")
        return legacySink
            .findAll(source)
            .mapNotNull { match ->
                val openParen = source.indexOf('(', match.range.first)
                val call =
                    source.substring(match.range.first, openParen) +
                        runCatching {
                            source.kotlinBlockFrom(openParen, "legacy copy sink", '(', ')')
                        }.getOrNull().orEmpty()
                if (Regex("""\bcopyable\s*=\s*true\b""").containsMatchIn(call)) {
                    source.take(match.range.first).count { it == '\n' } + 1
                } else {
                    null
                }
            }.distinct()
            .toList()
    }
}
