package dev.ipf.whitenoise.android.state

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AuditLogShareTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun clearFileProviderStrategyBeforeTest() = clearFileProviderStrategyCache()

    @After
    fun clearFileProviderStrategyAfterTest() = clearFileProviderStrategyCache()

    private fun clearFileProviderStrategyCache() {
        val cacheField = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (cacheField.get(null) as MutableMap<String, *>).clear()
    }

    @Test
    fun prepareAuditLogShareFilesCopiesRegularFilesIntoPrivateShareCache() {
        val source = temporaryFolder.newFile("audit.jsonl").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cache = temporaryFolder.newFolder("cache")

        val shared = prepareAuditLogShareFiles(cache, temporaryFolder.root, listOf(source.absolutePath))

        assertEquals(1, shared.size)
        assertEquals(File(cache, "audit_logs"), shared.single().parentFile?.parentFile)
        assertTrue(
            shared
                .single()
                .parentFile
                ?.name
                ?.isNotBlank() == true,
        )
        assertArrayEquals(source.readBytes(), shared.single().readBytes())
        assertTrue(shared.single().isFile)
    }

    @Test
    fun prepareAuditLogShareFilesAcceptsTheConfiguredRootThroughItsFilesystemAlias() {
        val realRoot = temporaryFolder.newFolder("real-allowed")
        val source = File(realRoot, "audit.jsonl").apply { writeText("entry") }
        val rootAlias = File(temporaryFolder.root, "allowed-alias")
        Files.createSymbolicLink(rootAlias.toPath(), realRoot.toPath())
        val cache = temporaryFolder.newFolder("alias-cache")

        val shared =
            prepareAuditLogShareFiles(
                cache,
                rootAlias,
                listOf(File(rootAlias, source.name).absolutePath),
            )

        assertEquals(listOf("audit.jsonl"), shared.map(File::getName))
        assertEquals(listOf("entry"), shared.map(File::readText))
    }

    @Test
    fun prepareAuditLogShareFilesRejectsSymlinksAndMissingFiles() {
        val source = temporaryFolder.newFile("source.jsonl").apply { writeText("private") }
        val link = File(temporaryFolder.root, "link.jsonl")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        val cache = temporaryFolder.newFolder("cache")

        val shared =
            prepareAuditLogShareFiles(
                cache,
                temporaryFolder.root,
                listOf(link.absolutePath, File(temporaryFolder.root, "missing.jsonl").absolutePath),
            )

        assertTrue(shared.isEmpty())
        assertFalse(File(cache, "audit_logs/link.jsonl").exists())
    }

    @Test
    fun prepareAuditLogShareFilesRejectsOutsideRootAndIntermediateSymlinks() {
        val allowed = temporaryFolder.newFolder("allowed")
        val outside = temporaryFolder.newFolder("outside")
        val secret = File(outside, "secret.jsonl").apply { writeText("secret") }
        val linkedDirectory = File(allowed, "linked")
        Files.createSymbolicLink(linkedDirectory.toPath(), outside.toPath())
        val cache = temporaryFolder.newFolder("confined-cache")

        val shared =
            prepareAuditLogShareFiles(
                cache,
                allowed,
                listOf(secret.absolutePath, File(linkedDirectory, secret.name).absolutePath),
            )

        assertTrue(shared.isEmpty())
    }

    @Test
    fun prepareAuditLogShareFilesUsesDistinctSafeNamesAndClearsPriorExports() {
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

        val shared =
            prepareAuditLogShareFiles(
                cache,
                temporaryFolder.root,
                listOf(first.absolutePath, second.absolutePath),
            )

        assertEquals(listOf("audit.jsonl", "audit-2.jsonl"), shared.map(File::getName))
        assertEquals(listOf("one", "two"), shared.map(File::readText))
        assertFalse(stale.exists())
        val firstSession = shared.first().parentFile!!.name
        val nextShared = prepareAuditLogShareFiles(cache, temporaryFolder.root, listOf(first.absolutePath))
        assertNotEquals(firstSession, nextShared.single().parentFile!!.name)
        assertFalse(shared.first().exists())
    }

    @Test
    fun clearPreparedAuditLogSharesRemovesEveryStagedSession() {
        val cache = temporaryFolder.newFolder("cache")
        val first =
            File(cache, "audit_logs/session-a/one.jsonl").apply {
                parentFile!!.mkdirs()
                writeText("one")
            }
        File(cache, "audit_logs/session-b/two.jsonl").apply {
            parentFile!!.mkdirs()
            writeText("two")
        }

        assertTrue(clearPreparedAuditLogShares(cache))
        assertFalse(first.exists())
        assertFalse(File(cache, "audit_logs").exists())
        assertFalse(clearPreparedAuditLogShares(cache))
    }

    @Test
    fun auditLogShareIntentMarksEveryUriReadOnlyAndSensitive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shareDir = File(context.cacheDir, "audit_logs/session").apply { mkdirs() }
        val first = File(shareDir, "first.jsonl").apply { writeText("one") }
        val second = File(shareDir, "second.jsonl").apply { writeText("two") }

        val send = auditLogShareIntent(context, listOf(first, second))

        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("application/octet-stream", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(send.getBooleanExtra(ClipDescription.EXTRA_IS_SENSITIVE, false))
        assertTrue(
            send.clipData!!
                .description.extras!!
                .getBoolean(ClipDescription.EXTRA_IS_SENSITIVE),
        )
        assertEquals(2, send.clipData!!.itemCount)
        val uris = send.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(2, uris.size)
        uris.forEach { uri ->
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertTrue(uri.pathSegments.take(2) == listOf("audit_logs", "session"))
        }
        val chooser = auditLogShareChooserIntent(context, listOf(first, second), "Export")
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, chooser.clipData!!.itemCount)
    }
}
