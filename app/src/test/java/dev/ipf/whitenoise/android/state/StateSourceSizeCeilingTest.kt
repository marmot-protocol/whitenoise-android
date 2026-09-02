package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Growth ceiling for the two largest state sources. The ceilings exist because
 * [Controllers.kt] (12,759 lines) and [AppState.kt] (10,185 lines) are already
 * hard to navigate; new work should land in smaller units unless a Kover report
 * justifies a deliberate, reviewed raise of these limits. Lowering a ceiling
 * after a refactor is routine; raising one requires an intentional edit here
 * backed by coverage measurements from
 * `./gradlew :app:koverXmlReportDevZapstoreDebug`.
 */
class StateSourceSizeCeilingTest {
    @Test
    fun controllersAndAppStateStayWithinGrowthCeiling() {
        assertTrue(
            "Controllers.kt exceeds ${CONTROLLERS_MAX_LINES} lines",
            sourceLineCount("Controllers.kt") <= CONTROLLERS_MAX_LINES,
        )
        assertTrue(
            "AppState.kt exceeds ${APP_STATE_MAX_LINES} lines",
            sourceLineCount("AppState.kt") <= APP_STATE_MAX_LINES,
        )
    }

    @Test
    fun lineCountHelperCountsPhysicalLinesLikeWc() {
        val temp = File.createTempFile("state-source-ceiling", ".kt")
        temp.writeText("first\nsecond\nthird\n")
        assertEquals(3, sourceLineCount(temp))
    }

    private fun sourceLineCount(name: String): Int = sourceLineCount(sourceFile(name))

    internal companion object {
        // Lowered as `ChatListItem` and then the chat-list sort moved to their own
        // same-package files. Raised to the exact post-#2431 size for its covered
        // chat-list convergence and notification-open fixes; keep this ratchet exact.
        const val CONTROLLERS_MAX_LINES = 12877

        // Deliberately raised for PR #2392's covered app-owned dictation integration. Keep this exact so
        // unrelated AppState growth remains blocked after the stacked branch is merged with current master.
        const val APP_STATE_MAX_LINES = 10226

        /** Counts physical source lines with the same trailing-newline semantics as `wc -l`. */
        internal fun sourceLineCount(file: File): Int = file.bufferedReader().useLines { lines -> lines.count() }

        private fun sourceFile(name: String): File =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/state/$name"),
                File("app/src/main/java/dev/ipf/whitenoise/android/state/$name"),
            ).firstOrNull(File::exists)
                ?: error("Missing $name source file")
    }
}
