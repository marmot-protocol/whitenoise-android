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
        assertTrue(body.contains("var anyDeleted = false"))
        assertTrue(body.contains("cacheFailure?.let"))
        assertTrue(body.contains("if (preparedDeleted)"))
        assertTrue(body.indexOf("cacheFailure?.let") < body.indexOf("if (preparedDeleted)"))
        assertTrue(body.contains("engineFailure?.let"))
        assertTrue(body.contains("preparedDeleted && engineFailure == null"))
    }

    /** Reads the production delete path from either Gradle's module or repository working directory. */
    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}
