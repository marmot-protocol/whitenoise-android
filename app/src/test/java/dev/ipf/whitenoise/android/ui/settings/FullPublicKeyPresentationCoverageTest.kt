package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.motionOwnerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3321: every full-screen identity view offers the whole public key, either through the
 * expanding [CopyablePublicKey] or, on Account & Keys, the always-full [FullPublicKey], so the middle of a key can
 * always be compared. Each view shows its key once, never a short copy next to a full block, and the five-character
 * grouping stays display only, so it never reaches a copy, a share or a QR code.
 */
class FullPublicKeyPresentationCoverageTest {
    /** Each identity-verification surface renders its own public key through a whole-key presentation. */
    @Test
    fun everyIdentitySurfaceShowsTheWholeKey() {
        SURFACES.forEach { (path, value) ->
            val call = Regex("""(?:Copyable|Full)PublicKey\(\s*npub = ${Regex.escape(value)},""")
            assertTrue("$path no longer shows the whole key for $value", call.containsMatchIn(source(path)))
        }
    }

    /** An expanding key is the only key on its screen: no short capsule or second full block beside it. */
    @Test
    fun expandingKeySurfacesShowTheKeyOnce() {
        SURFACES.map { it.first }.filter { "CopyablePublicKey(" in source(it) }.forEach { path ->
            val text = source(path)
            val presentations = Regex("""CopyablePublicKey\(""").findAll(text).count()
            assertEquals("$path shows more than one key presentation", 1, presentations)
            listOf("FullPublicKey(", "IdentifierCopyCapsule(", "IdentityFormatter.short(npub").forEach { other ->
                assertTrue("$path shows its key twice through $other", other !in text)
            }
        }
    }

    /** Account & Keys shows the public key in full and keeps every secret value on its own ellipsized line. */
    @Test
    fun accountKeysGroupsOnlyThePublicKey() {
        val keys = source(ACCOUNT_KEYS)

        val parts = ACCOUNT_KEYS_PARTS.joinToString("\n") { source(it) }
        assertEquals(1, Regex("""fullPublicKey = true""").findAll(parts).count())
        val publicRow = keys.substringAfter("""row("public_key")""").substringBefore("""row(""")
        assertTrue("the public key row lost its full display", "fullPublicKey = true" in publicRow)
    }

    /** Only display components group a key; copy, share and QR paths keep the contiguous value. */
    @Test
    fun groupingStaysDisplayOnly() {
        val grouping =
            mainSources()
                .filter { "publicKeyDisplayGroups(" in it.motionOwnerSource() }
                .map { it.name }
                .toSet()

        assertEquals(
            setOf(
                "AiAgentsScreen.kt",
                "FullPublicKey.kt",
                "GroupActionReviewIdentity.kt",
                "GroupRosterMutationUi.kt",
                "PublicKeyCopy.kt",
                "SettingsProfileKey.kt",
            ),
            grouping,
        )
    }

    /** One main source file by its path below the app package. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists)?.motionOwnerSource() ?: error("Missing $path")

    /** Every Kotlin main source file. */
    private fun mainSources(): List<File> =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .first(File::isDirectory)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private companion object {
        const val ACCOUNT_KEYS = "ui/settings/AccountKeysScreen.kt"

        /** The screen and the files split out of it, so the single-full-key count still covers all of them. */
        val ACCOUNT_KEYS_PARTS =
            listOf(
                ACCOUNT_KEYS,
                "ui/settings/AccountKeysDialogs.kt",
                "ui/settings/AccountWipeAndSignOut.kt",
                "ui/settings/AccountWipeProgressSheets.kt",
            )

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
