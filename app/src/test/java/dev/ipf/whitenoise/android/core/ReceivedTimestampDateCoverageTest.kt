package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Received timestamps are untrusted unsigned seconds. Converting one past 9999-12-31 to an instant, or its instant to a
 * zoned date, throws during composition, and its millisecond product can wrap into an unrelated date. Every production
 * conversion of a raw stamp is therefore guarded by [IdentityFormatter.MAX_DISPLAYABLE_EPOCH_SECONDS] or follows a
 * null-checked [IdentityFormatter.localDate], or goes through [IdentityFormatter.clampedLocalDate].
 *
 * Retention deadlines, local expiries and the local receive stamp come from this device's clock and its own bounded
 * retention arithmetic rather than a sender's stamp, so their millisecond products are outside this gate.
 */
class ReceivedTimestampDateCoverageTest {
    @Test
    fun everyRawStampConversionIsBoundedFirst() {
        val conversions =
            mainSources().flatMap { file ->
                val text = file.readText()
                conversions
                    .flatMap { it.findAll(text) }
                    .filterNot { match -> match.groupValues[1].substringAfterLast('.') in LOCAL_CLOCK_STAMPS }
                    .map { match -> Triple(file, text, match.range.first) }
                    .toList()
            }
        val files = conversions.map { (file, _, _) -> file.name }.toSet()
        assertTrue("the scan found the search date filter", "ChatListMessageSearch.kt" in files)
        assertTrue("the scan found instant conversions", "IdentityFormatter.kt" in files)
        val unguarded =
            conversions
                .filterNot { (_, text, at) -> guarded(text, at) }
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

    private fun guarded(
        text: String,
        at: Int,
    ): Boolean {
        val window = text.substring(maxOf(0, at - GUARD_WINDOW), at)
        return GUARDS.any(window::contains)
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
        val conversions =
            listOf(
                Regex("""ofEpochSecond\(\s*([\w.]+)\.toLong\(\)"""),
                Regex("""([\w.]+)\.toLong\(\)\s*\*\s*(?:MILLIS_PER_SECOND\w*|1000L)"""),
            )

        /** Stamps produced by this device's clock or retention arithmetic, not by a sender. */
        val LOCAL_CLOCK_STAMPS = setOf("deadline", "expiry", "expirySeconds", "receivedAt")
        val globalAttachmentDay =
            Regex("""fun globalAttachmentDay\([^)]*\): LocalDate = IdentityFormatter\.clampedLocalDate\(""")
        val GUARDS = listOf("MAX_DISPLAYABLE_EPOCH_SECONDS", "IdentityFormatter.localDate(")
        const val GUARD_WINDOW = 400
    }
}
