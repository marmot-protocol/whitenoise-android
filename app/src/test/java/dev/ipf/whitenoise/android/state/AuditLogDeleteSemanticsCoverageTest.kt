package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditLogDeleteSemanticsCoverageTest {
    /** Checks that app-owned diagnostic deletion cannot hide a failed native audit deletion. */
    @Test
    fun cacheCleanupCannotMaskEngineDeleteFailureOrEmptyStateFailure() {
        val body =
            appStateSource()
                .substringAfter("suspend fun deleteAuditLogs(): Boolean {")
                .substringBefore("fun updateThemeMode")

        assertFalse(body.contains("var anyDeleted = preparedDeleted"))
        assertTrue(body.contains("cacheFailure?.let"))
        assertTrue(body.contains("if (preparedDeleted)"))
        assertTrue(body.indexOf("cacheFailure?.let") < body.indexOf("if (preparedDeleted)"))
    }

    /** Checks that one deleted file cannot turn a failed sibling deletion into a cleared result (#3705). */
    @Test
    fun anyFailedNativeDeletionMakesTheResultAFailure() {
        val body =
            appStateSource()
                .substringAfter("suspend fun deleteAuditLogs(): Boolean {")
                .substringBefore("fun updateThemeMode")

        assertFalse("a successful sibling must not override a recorded failure", body.contains("anyDeleted ||"))
        assertTrue(body.contains("val failure = engineFailure") && body.contains("return failure == null"))
    }

    /** Reads the production delete path from either Gradle's module or repository working directory. */
    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}
