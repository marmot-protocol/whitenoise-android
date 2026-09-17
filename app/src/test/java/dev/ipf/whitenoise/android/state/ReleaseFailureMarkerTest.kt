package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release logs may name the failing operation, the stable category and MarmotKit's error variant, and in
 * non-production builds a redacted note, but never an identifier or exception text. The forbidden shapes
 * mirror `scripts/verify-release-runtime.sh`, which fails a release build that emits them.
 */
class ReleaseFailureMarkerTest {
    private val forbidden =
        Regex(
            "npub1|nsec1|https?://|/data/|[0-9a-fA-F]{64}|detail=|group=|message=|filename=|path=|report=|" +
                "error=|reason=|Exception|Caused by:",
        )

    /** The marker carries operation, category and variant, and nothing MarmotKit put in its message. */
    @Test
    fun markerNamesTheVariantWithoutExceptionText() {
        val marker =
            releaseFailureMarker(
                "CHAT_LIST_WINDOW_OPEN",
                MarmotKitException.ChatWindowQuery("group=${"a".repeat(64)} detail=boom"),
            )

        assertTrue(marker.startsWith("operation_failed op=CHAT_LIST_WINDOW_OPEN code=WINDOW_QUERY mdk=ChatWindowQuery"))
        assertFalse(forbidden.containsMatchIn(marker))
    }

    /** The dev unit-test environment is verbose, so the note is present but redacted. */
    @Test
    fun verboseNoteIsRedacted() {
        val note =
            "bind failed account=abcdef12: https://relay.example/x group=${"b".repeat(64)} " +
                "Caused by: java.io.IOException path=/data/user/0/x npub1qy352euf40x77qfrg4ncn27daufwuj22r4ttmxhstefp92xqnjgs8pjhstefp92"
        val marker = releaseFailureMarker("CHATS", IllegalStateException("plain"), note)

        assertTrue(marker.contains("note="))
        assertFalse(forbidden.containsMatchIn(marker))
        assertTrue(marker.contains("account=abcdef12"))
    }

    /** Redaction keeps the prose and only rewrites the shapes the verifier rejects. */
    @Test
    fun redactionRewritesOnlyForbiddenShapes() {
        assertEquals("live chat subscription failed", redactForReleaseLog("live chat subscription failed"))
        assertEquals("detail:x error:y", redactForReleaseLog("detail=x error=y"))
        assertEquals("cause: IOExc", redactForReleaseLog("Caused by: IOException"))
        assertEquals("<hex>", redactForReleaseLog("f".repeat(64)))
        assertEquals("<bech32> and <url>", redactForReleaseLog("npub1abc and https://example.org/a?b=c"))
    }
}
