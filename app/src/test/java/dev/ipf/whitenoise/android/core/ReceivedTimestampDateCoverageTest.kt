package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Received timestamps are untrusted unsigned seconds. Converting one past 9999-12-31 to an instant, or its instant to a
 * zoned date, throws during composition, so every production conversion of a raw stamp is guarded by
 * [IdentityFormatter.MAX_DISPLAYABLE_EPOCH_SECONDS] or goes through [IdentityFormatter.localDate] or
 * [IdentityFormatter.clampedLocalDate].
 */
class ReceivedTimestampDateCoverageTest {
    @Test
    fun everyRawStampConversionIsBoundedFirst() {
        val conversions =
            mainSources().flatMap { file ->
                val text = file.readText()
                conversion.findAll(text).map { match -> Triple(file, text, match.range.first) }.toList()
            }
        assertTrue("the scan found the guarded conversions", conversions.size >= MIN_CONVERSIONS)
        val unguarded =
            conversions
                .filterNot { (_, text, at) -> text.substring(maxOf(0, at - GUARD_WINDOW), at).contains(GUARD) }
                .map { (file, text, at) -> "${file.name}:${text.substring(0, at).count { it == '\n' } + 1}" }
        assertEquals("raw stamp conversions without the displayable bound", emptyList<String>(), unguarded)
    }

    /** Conversation day separators and media grouping use the bounded date helpers. */
    @Test
    fun conversationAndMediaDatesUseTheBoundedHelpers() {
        val rows = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationRows.kt")
        assertTrue(rows.functionBody("differentDay").contains("IdentityFormatter.localDate("))
        assertTrue(rows.functionBody("messageDayLabel").contains("IdentityFormatter.localDate("))
        val media = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/medialibrary/MediaLibrary.kt")
        assertTrue(media.functionBody("monthKeyForMedia").contains("IdentityFormatter.clampedLocalDate("))
        val global = appModuleSource("src/main/java/dev/ipf/whitenoise/android/core/GlobalAttachmentLibrary.kt")
        assertTrue(globalAttachmentDay.containsMatchIn(global))
    }

    private fun mainSources(): List<File> {
        val root =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android"),
                File("app/src/main/java/dev/ipf/whitenoise/android"),
            ).first(File::isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        val conversion = Regex("""ofEpochSecond\(\s*[\w.]+\.toLong\(\)""")
        val globalAttachmentDay =
            Regex("""fun globalAttachmentDay\([^)]*\): LocalDate = IdentityFormatter\.clampedLocalDate\(""")
        const val GUARD = "MAX_DISPLAYABLE_EPOCH_SECONDS"
        const val GUARD_WINDOW = 400
        const val MIN_CONVERSIONS = 5
    }
}
