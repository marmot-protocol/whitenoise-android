package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class AppStateDisplayNameFallbackTest {
    @Test
    fun networkDisplayNameFallsBackToShortNpub() {
        val source = appStateSource().readText()
        val start = source.indexOf("fun networkDisplayName(accountIdHex: String): String {")
        val end = source.indexOf("\n    fun chatMemberTitle", start)
        check(start >= 0 && end > start) { "Missing networkDisplayName function" }
        val function = source.substring(start, end)

        assertFalse(function.contains("IdentityFormatter.short(accountIdHex)"))
        assertEquals(2, Regex("""shortNpub\(accountIdHex\)""").findAll(function).count())
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}
