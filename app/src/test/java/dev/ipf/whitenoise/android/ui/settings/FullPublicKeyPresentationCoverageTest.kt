package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3321: every full-screen identity view shows the whole public key through [FullPublicKey], so
 * the middle of a key can always be compared, and the five-character grouping stays display only, so it never
 * reaches a copy, a share or a QR code.
 */
class FullPublicKeyPresentationCoverageTest {
    /** Each identity-verification surface renders its own public key in full. */
    @Test
    fun everyIdentitySurfaceShowsTheWholeKey() {
        SURFACES.forEach { (path, value) ->
            val call = Regex("""FullPublicKey\(\s*npub = ${Regex.escape(value)},""")
            assertTrue("$path no longer shows the whole key for $value", call.containsMatchIn(source(path)))
        }
    }

    /** Account & Keys shows the public key in full and keeps every secret value on its own ellipsized line. */
    @Test
    fun accountKeysGroupsOnlyThePublicKey() {
        val keys = source(ACCOUNT_KEYS)

        assertEquals(1, Regex("""fullPublicKey = true""").findAll(keys).count())
        val publicRow = keys.substringAfter("""row("public_key")""").substringBefore("""row(""")
        assertTrue("the public key row lost its full display", "fullPublicKey = true" in publicRow)
    }

    /** Only the display component groups a key; copy, share and QR paths keep the contiguous value. */
    @Test
    fun groupingStaysDisplayOnly() {
        val grouping =
            mainSources()
                .filter { "publicKeyDisplayGroups(" in it.readText() }
                .map { it.name }

        assertEquals(listOf("FullPublicKey.kt"), grouping)
    }

    /** One main source file by its path below the app package. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $path")

    /** Every Kotlin main source file. */
    private fun mainSources(): List<File> =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .first(File::isDirectory)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private companion object {
        const val ACCOUNT_KEYS = "ui/settings/AccountKeysScreen.kt"

        /** Identity surfaces and the expression each passes as its own public key. */
        val SURFACES =
            listOf(
                "ui/profile/PersonProfileContent.kt" to "person.publicKey",
                "ui/settings/ShareConnectScreen.kt" to "profile.npub",
                "ui/profile/ProfileQrSheet.kt" to "npub",
                ACCOUNT_KEYS to "value",
            )
    }
}
