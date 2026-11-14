package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Received timestamp conversion belongs to IdentityFormatter, not to presentation consumers. */
class ReceivedTimestampDateCoverageTest {
    @Test
    fun everyRawStampConversionHasAnExplicitOwner() {
        val violations =
            mainSources().flatMap { file ->
                val relative = file.invariantSeparatorsPath.substringAfter("android/")
                if (relative == "core/IdentityFormatter.kt") {
                    emptyList()
                } else {
                    val allowed = OTHER_INPUTS[relative].orEmpty()
                    rawConversions(file.readText())
                        .groupingBy { it }
                        .eachCount()
                        .filter { (conversion, count) -> conversion !in allowed || count != 1 }
                        .map { (conversion, count) -> "$relative: $conversion ($count sites)" }
                }
            }
        assertEquals("received conversions outside IdentityFormatter", emptyList<String>(), violations)
    }

    @Test
    fun nearbyCommentsAndUnrelatedBoundsCannotAuthorizeAConversion() {
        val source =
            "// MAX_DISPLAYABLE_EPOCH_SECONDS\nIdentityFormatter.localDate(other)\n" +
                "Instant.ofEpochSecond(received.toLong())\nreceived.toLong() * 1000L"
        assertEquals(2, rawConversions(source).size)
        assertTrue(rawConversions(source).all { it !in OTHER_INPUTS.values.flatten() })
    }

    private fun rawConversions(source: String): List<String> =
        conversions.flatMap { pattern ->
            pattern.findAll(source).map { it.value }.toList()
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
        val conversions =
            listOf(
                Regex("""ofEpochSecond\(\s*[\w.]+(?:\.toLong\(\))?\s*\)"""),
                Regex("""[\w.]+\.toLong\(\)\s*\*\s*(?:MILLIS_PER_SECOND\w*|1000L)"""),
            )

        // These are different input classes, reviewed by provenance at their exact production sites.
        // Device-clock receive time and bounded retention deadlines are not peer-supplied display dates.
        // Markdown and Nostr cards accept signed event timestamps and own their existing failure fallback.
        val OTHER_INPUTS =
            mapOf(
                "state/AppState.kt" to setOf("message.receivedAt.toLong() * MILLIS_PER_SECOND_LONG"),
                "state/DisappearingMessageSweep.kt" to setOf("expirySeconds.toLong() * MILLIS_PER_SECOND"),
                "ui/chats/GlobalSearchResultsSession.kt" to setOf("deadline.toLong() * MILLIS_PER_SECOND"),
                "ui/chats/GlobalAttachmentLibrarySession.kt" to setOf("deadline.toLong() * MILLIS_PER_SECOND"),
                "ui/conversation/ForwardEligibilityClock.kt" to setOf("expiry.toLong() * MILLIS_PER_SECOND"),
                "ui/settings/StorageDialogs.kt" to setOf("ofEpochSecond(cutoffSeconds)"),
                "ui/MarkdownTimestampFormat.kt" to setOf("ofEpochSecond(seconds)"),
                "ui/conversation/nostr/NostrEventCardFormatting.kt" to setOf("ofEpochSecond(createdAt)"),
            )
        val globalAttachmentDay =
            Regex("""fun globalAttachmentDay\([^)]*\): LocalDate = IdentityFormatter\.clampedLocalDate\(""")
    }
}
