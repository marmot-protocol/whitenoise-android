package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Matches detekt's `LargeClass.allowedLines` in config/detekt/detekt.yml. */
internal const val SOURCE_FILE_LINE_BUDGET = 600

/**
 * Keeps main Kotlin files from growing into new monoliths. A file may exceed [SOURCE_FILE_LINE_BUDGET]
 * only when [sourceFileSizeBaseline] records it, and never past its recorded limit. Limits only go
 * down: a change that shrinks a listed file lowers its limit. New code belongs in a focused topic
 * file; see docs/code-structure.md.
 */
class SourceFileSizeRatchetTest {
    @Test
    fun mainSourceFilesStayWithinTheirLimits() {
        val failures =
            mainSourceLineCounts().mapNotNull { (path, lines) ->
                val limit = sourceFileSizeBaseline[path] ?: SOURCE_FILE_LINE_BUDGET
                if (lines <= limit) {
                    null
                } else {
                    "$path has $lines lines (limit $limit). Move the new code into a focused topic file in the " +
                        "same package, or split this file first in a separate pure-move change. Do not raise " +
                        "or add a limit in SourceFileSizeBaseline.kt."
                }
            }
        assertTrue(failures.report(), failures.isEmpty())
    }

    @Test
    fun baselineListsOnlyExistingOversizedFiles() {
        val counts = mainSourceLineCounts()
        val failures =
            sourceFileSizeBaseline.keys.mapNotNull { path ->
                val lines = counts[path]
                when {
                    lines == null -> "$path no longer exists. Delete its entry from SourceFileSizeBaseline.kt."
                    lines <= SOURCE_FILE_LINE_BUDGET ->
                        "$path now has $lines lines, within the $SOURCE_FILE_LINE_BUDGET-line budget. Delete its " +
                            "entry from SourceFileSizeBaseline.kt."
                    else -> null
                }
            }
        assertTrue(failures.report(), failures.isEmpty())
    }

    private fun List<String>.report(): String = joinToString("\n", prefix = "$REPORT_HEADER\n") { "- $it" }

    /** Line counts keyed by path below the source root; a trailing newline does not add a line. */
    private fun mainSourceLineCounts(): Map<String, Int> =
        mainSourceRoots()
            .flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map {
                        it.relativeTo(root).invariantSeparatorsPath to
                            it
                                .readText()
                                .trimEnd()
                                .lines()
                                .size
                    }.toList()
            }.toMap()

    private fun mainSourceRoots(): List<File> =
        listOf(appModuleFile("src/main/java")) +
            listOfNotNull(File(invariantGateRepositoryRoot(), "app/src/main/kotlin").takeIf(File::isDirectory))

    private companion object {
        const val REPORT_HEADER = "Source file size ratchet (docs/code-structure.md):"
    }
}
