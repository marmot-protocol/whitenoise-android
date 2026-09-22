package dev.ipf.whitenoise.android.state

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class AuditLogShareTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    /** One export yields one archive in private cache holding every confined log, byte for byte. */
    @Test
    fun prepareAuditLogArchiveWritesEveryConfinedLogIntoOneArchive() {
        val first = temporaryFolder.newFile("audit.jsonl").apply { writeText("one") }
        val second = temporaryFolder.newFile("audit-b.jsonl").apply { writeText("two") }
        val cache = temporaryFolder.newFolder("cache")

        val archive =
            prepareAuditLogArchive(cache, temporaryFolder.root, listOf(first.absolutePath, second.absolutePath))

        assertEquals(AUDIT_LOG_ARCHIVE_NAME, archive.name)
        assertEquals(File(cache, "audit_logs"), archive.parentFile?.parentFile)
        assertEquals(mapOf("audit.jsonl" to "one", "audit-b.jsonl" to "two"), archive.entries())
    }

    /** An allowed root reached through a filesystem alias is still the same confined root. */
    @Test
    fun prepareAuditLogArchiveAcceptsTheConfiguredRootThroughItsFilesystemAlias() {
        val realRoot = temporaryFolder.newFolder("real-allowed")
        val source = File(realRoot, "audit.jsonl").apply { writeText("entry") }
        val rootAlias = File(temporaryFolder.root, "allowed-alias")
        Files.createSymbolicLink(rootAlias.toPath(), realRoot.toPath())
        val cache = temporaryFolder.newFolder("alias-cache")

        val archive =
            prepareAuditLogArchive(cache, rootAlias, listOf(File(rootAlias, source.name).absolutePath))

        assertEquals(mapOf("audit.jsonl" to "entry"), archive.entries())
    }

    /**
     * Export is complete or it fails. A symlink, a missing file, a source outside the root and one
     * reached through an intermediate symlink each abort the whole archive, leaving nothing staged
     * that a caller could present as a finished export.
     *
     * Aborting clears only the failed export. An archive already handed to a recipient survives,
     * because its content URI may still be unopened and this export failing is unrelated to it.
     */
    @Test
    fun prepareAuditLogArchiveFailsClosedInsteadOfWritingAPartialArchive() {
        val allowed = temporaryFolder.newFolder("allowed")
        val good = File(allowed, "audit.jsonl").apply { writeText("kept") }
        val outside = temporaryFolder.newFolder("outside")
        val secret = File(outside, "secret.jsonl").apply { writeText("secret") }
        val fileLink = File(allowed, "link.jsonl")
        Files.createSymbolicLink(fileLink.toPath(), good.toPath())
        val linkedDirectory = File(allowed, "linked")
        Files.createSymbolicLink(linkedDirectory.toPath(), outside.toPath())

        val rejected =
            listOf(
                fileLink.absolutePath,
                File(allowed, "missing.jsonl").absolutePath,
                secret.absolutePath,
                File(linkedDirectory, secret.name).absolutePath,
            )
        rejected.forEachIndexed { index, unsafe ->
            val cache = temporaryFolder.newFolder("cache-$index")
            val shared = prepareAuditLogArchive(cache, allowed, listOf(good.absolutePath))

            val failure =
                runCatching { prepareAuditLogArchive(cache, allowed, listOf(good.absolutePath, unsafe)) }
                    .exceptionOrNull()

            assertTrue("$unsafe must abort the export", failure != null)
            assertTrue("$unsafe must not destroy an archive already handed out", shared.exists())
            assertEquals(
                "$unsafe must leave no session of its own",
                listOf(shared.parentFile!!.name),
                File(cache, "audit_logs").listFiles().orEmpty().map { it.name },
            )
        }
    }

    /** Entry names are safe, relative and unique, and a new export replaces the previous one. */
    @Test
    fun prepareAuditLogArchiveUsesDistinctSafeEntryNamesAndClearsPriorExports() {
        val firstDir = temporaryFolder.newFolder("first")
        val secondDir = temporaryFolder.newFolder("second")
        val first = File(firstDir, "../first/audit.jsonl").apply { writeText("one") }
        val second = File(secondDir, "audit.jsonl").apply { writeText("two") }
        val cache = temporaryFolder.newFolder("cache")
        val stale =
            File(cache, "audit_logs/stale-secret.jsonl").apply {
                parentFile!!.mkdirs()
                writeText("stale")
            }

        val archive =
            prepareAuditLogArchive(cache, temporaryFolder.root, listOf(first.absolutePath, second.absolutePath))

        assertEquals(mapOf("audit.jsonl" to "one", "audit-2.jsonl" to "two"), archive.entries())
        archive.entries().keys.forEach { entry ->
            assertFalse(entry.contains('/'))
            assertFalse(entry.contains('\\'))
            assertFalse(entry.contains(".."))
        }
        assertFalse(stale.exists())
        val firstSession = archive.parentFile!!.name
        val next = prepareAuditLogArchive(cache, temporaryFolder.root, listOf(first.absolutePath))
        assertNotEquals(firstSession, next.parentFile!!.name)
    }

    /**
     * A shared archive is handed over as a content URI, so it must outlive the next export long
     * enough for the recipient to open it — while cache growth stays bounded.
     */
    @Test
    fun prepareAuditLogArchiveKeepsThePreviousSessionAndPrunesOlderOnes() {
        val source = temporaryFolder.newFile("audit.jsonl").apply { writeText("body") }
        val cache = temporaryFolder.newFolder("cache")
        val paths = listOf(source.absolutePath)

        val first = prepareAuditLogArchive(cache, temporaryFolder.root, paths)
        first.parentFile!!.setLastModified(1_000L)
        val second = prepareAuditLogArchive(cache, temporaryFolder.root, paths)
        second.parentFile!!.setLastModified(2_000L)

        assertTrue("the just-shared archive must survive the next export", first.exists())
        assertTrue(second.exists())

        val third = prepareAuditLogArchive(cache, temporaryFolder.root, paths)
        third.parentFile!!.setLastModified(3_000L)

        assertTrue(third.exists())
        assertTrue(second.exists())
        assertFalse("older sessions must not accumulate", first.exists())
        assertEquals(
            RETAINED_AUDIT_EXPORT_SESSIONS,
            File(cache, "audit_logs").listFiles().orEmpty().count { it.isDirectory },
        )
    }

    /** A source name made entirely of unsafe characters still yields a usable entry name. */
    @Test
    fun prepareAuditLogArchiveNamesAnEntryEvenWhenTheSourceNameIsUnusable() {
        val source = temporaryFolder.newFile("...").apply { writeText("body") }
        val cache = temporaryFolder.newFolder("cache")

        val archive = prepareAuditLogArchive(cache, temporaryFolder.root, listOf(source.absolutePath))

        assertEquals(mapOf("audit-log.jsonl" to "body"), archive.entries())
    }

    /** Clearing removes every staged session, and reports that there was nothing left to remove. */
    @Test
    fun clearPreparedAuditLogSharesRemovesEveryStagedSession() {
        val cache = temporaryFolder.newFolder("cache")
        val first =
            File(cache, "audit_logs/session-a/one.zip").apply {
                parentFile!!.mkdirs()
                writeText("one")
            }
        File(cache, "audit_logs/session-b/two.zip").apply {
            parentFile!!.mkdirs()
            writeText("two")
        }

        assertTrue(clearPreparedAuditLogShares(cache))
        assertFalse(first.exists())
        assertFalse(File(cache, "audit_logs").exists())
        assertFalse(clearPreparedAuditLogShares(cache))
    }

    /** Sharing publishes exactly one read-only archive URI carrying sensitive-content metadata. */
    @Test
    fun auditLogShareIntentPublishesOneReadOnlySensitiveArchive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shareDir = File(context.cacheDir, "audit_logs/session").apply { mkdirs() }
        val archive = File(shareDir, AUDIT_LOG_ARCHIVE_NAME).apply { writeText("zip") }

        val send = auditLogShareIntent(context, archive)

        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(AUDIT_LOG_ARCHIVE_MIME_TYPE, send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(send.getBooleanExtra(ClipDescription.EXTRA_IS_SENSITIVE, false))
        assertTrue(
            send.clipData!!
                .description.extras!!
                .getBoolean(ClipDescription.EXTRA_IS_SENSITIVE),
        )
        assertEquals(1, send.clipData!!.itemCount)
        assertNull(send.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals(listOf("audit_logs", "session"), uri.pathSegments.take(2))

        val chooser = auditLogShareChooserIntent(context, archive, "Export")
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, chooser.clipData!!.itemCount)
    }

    /** Entry name to content, so a test can assert an archive's whole contents in one comparison. */
    private fun File.entries(): Map<String, String> =
        ZipFile(this).use { zip ->
            zip
                .entries()
                .asSequence()
                .associate { entry -> entry.name to zip.getInputStream(entry).bufferedReader().readText() }
        }
}
