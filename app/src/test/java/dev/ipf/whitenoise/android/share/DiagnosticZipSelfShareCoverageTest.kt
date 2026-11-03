package dev.ipf.whitenoise.android.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.state.AUDIT_LOG_ARCHIVE_MIME_TYPE
import dev.ipf.whitenoise.android.state.AUDIT_LOG_ARCHIVE_NAME
import dev.ipf.whitenoise.android.state.auditLogShareChooserIntent
import dev.ipf.whitenoise.android.state.clearPreparedAuditLogShares
import dev.ipf.whitenoise.android.state.prepareAuditLogArchive
import dev.ipf.whitenoise.android.state.stagedDiagnosticExportSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Invariant gate for #3556: a diagnostic ZIP this process staged can be shared back into White Noise
 * through the real producer → chooser intent → importer path, while every other URI on the app's own
 * FileProvider is still rejected before any read.
 *
 * Each case drives the production export ([prepareAuditLogArchive], [auditLogShareChooserIntent]) and
 * the production intake rule ([ShareFileImporter] with [stagedDiagnosticExportSource]). The provider
 * callbacks fail if called, so an admitted archive is shown to come from the verified file, never
 * from resolving the inbound URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticZipSelfShareCoverageTest {
    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority get() = "${context.packageName}.fileprovider"
    private lateinit var privateRoot: File
    private lateinit var files: PrivateShareFiles

    /** Starts from no staged export and an empty private intake directory. */
    @Before
    fun setUp() {
        clearPreparedAuditLogShares(context.cacheDir)
        privateRoot = Files.createTempDirectory("self-share-intake").toFile()
        files = PrivateShareFiles(privateRoot, "test.private-share")
    }

    /** Removes staged exports and intake copies so no case inherits another's archive. */
    @After
    fun tearDown() {
        clearPreparedAuditLogShares(context.cacheDir)
        privateRoot.deleteRecursively()
    }

    /** The staged archive arrives as one private copy with its exact bytes, name and ZIP type. */
    @Test
    fun stagedDiagnosticArchiveSharedToItselfIsAdmittedWithExactBytes() =
        runBlocking<Unit> {
            val archive = stage()
            val request = chooserRequest(archive)

            val imported = importer().import(request)

            assertEquals(emptyList<ShareImportError>(), imported.payload.importErrors)
            val copy = imported.payload.streamUris.single()
            assertTrue(files.owns(copy))
            assertArrayEquals(archive.readBytes(), files.resolve(copy)!!.readBytes())
            val metadata = files.metadata(copy)!!
            assertEquals(AUDIT_LOG_ARCHIVE_NAME, metadata.getString("name"))
            assertEquals(AUDIT_LOG_ARCHIVE_MIME_TYPE, metadata.getString("mime"))
            assertTrue("The staged export stays for other recipients", archive.isFile)
        }

    /**
     * A bound URI admits its archive once: an app the archive was shared with cannot replay the same URI to stage
     * it again, while an admission that failed verification leaves the binding for a real retry.
     */
    @Test
    fun aBoundUriIsSingleUseAfterASuccessfulAdmission() =
        runBlocking<Unit> {
            val archive = stage("single-use")
            val request = chooserRequest(archive)
            val original = archive.readBytes()
            archive.setWritable(true)
            archive.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
            assertEquals(listOf(ShareImportError.Unreadable), importer().import(request).payload.importErrors)

            archive.writeBytes(original)
            val admitted = importer().import(request)
            assertEquals(emptyList<ShareImportError>(), admitted.payload.importErrors)
            assertEquals(1, admitted.payload.streamUris.size)

            val replayed = importer().import(request)
            assertEquals(listOf(ShareImportError.Scheme), replayed.payload.importErrors)
            assertTrue(replayed.payload.streamUris.isEmpty())
        }

    /** Any self-provider URI that is not the exact bound one is rejected before metadata or open. */
    @Test
    fun forgedAndNeighbouringSelfProviderUrisStayRejected() =
        runBlocking<Unit> {
            val archive = stage()
            val bound = chooserRequest(archive).payload.streamUris.single()
            val session = bound.pathSegments[1]
            val forged =
                listOf(
                    Uri.parse("content://0@$authority/${bound.encodedPath!!.trimStart('/')}"),
                    Uri.parse("content://$authority/audit_logs/$UNKNOWN_SESSION/$AUDIT_LOG_ARCHIVE_NAME"),
                    Uri.parse("content://$authority/audit_logs/$session/../$session/$AUDIT_LOG_ARCHIVE_NAME"),
                    Uri.parse("content://$authority/startup_diagnostics/$AUDIT_LOG_ARCHIVE_NAME"),
                    Uri.parse("content://test.private-share/00000000-0000-0000-0000-000000000000"),
                    Uri.parse("file://${archive.absolutePath}"),
                )

            val imported = importer().import(request(forged))

            assertEquals(List(forged.size) { ShareImportError.Scheme }, imported.payload.importErrors)
            assertTrue(imported.payload.streamUris.isEmpty())
        }

    /** Clearing diagnostic logs, rotating past retention, or losing the process retires the handle. */
    @Test
    fun clearedRotatedOrForgottenExportsAreRecoverableRejections() =
        runBlocking<Unit> {
            val first = chooserRequest(stage("first"))
            chooserRequest(stage("second"))
            chooserRequest(stage("third"))
            assertEquals(listOf(ShareImportError.Scheme), importer().import(first).payload.importErrors)

            val latest = chooserRequest(stage("latest"))
            clearPreparedAuditLogShares(context.cacheDir)
            val cleared = importer().import(latest)
            assertEquals(listOf(ShareImportError.Scheme), cleared.payload.importErrors)
            assertTrue(cleared.payload.importReady)
        }

    /** A missing, resized, rewritten or symlinked archive is never read in place of the staged bytes. */
    @Test
    fun replacedMissingOrSymlinkedArchivesAreRejected() =
        runBlocking<Unit> {
            val missing = stage("missing")
            val missingRequest = chooserRequest(missing)
            missing.delete()
            assertEquals(listOf(ShareImportError.Scheme), importer().import(missingRequest).payload.importErrors)

            val resized = stage("resized")
            val resizedRequest = chooserRequest(resized)
            resized.setWritable(true)
            resized.appendBytes(byteArrayOf(1))
            assertEquals(listOf(ShareImportError.Scheme), importer().import(resizedRequest).payload.importErrors)

            val rewritten = stage("rewritten")
            val rewrittenRequest = chooserRequest(rewritten)
            val sameLength = rewritten.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            rewritten.setWritable(true)
            rewritten.writeBytes(sameLength)
            val rewrittenResult = importer().import(rewrittenRequest)
            assertEquals(listOf(ShareImportError.Unreadable), rewrittenResult.payload.importErrors)
            val retained = privateRoot.listFiles().orEmpty().filter { it.extension == "bin" }
            assertTrue("A rejected copy is not retained", retained.isEmpty())

            val linked = stage("linked")
            val linkedRequest = chooserRequest(linked)
            val outside = File(context.cacheDir, "outside.zip").apply { writeBytes(linked.readBytes()) }
            linked.delete()
            Files.createSymbolicLink(linked.toPath(), outside.toPath())
            assertEquals(listOf(ShareImportError.Scheme), importer().import(linkedRequest).payload.importErrors)
            outside.delete()
        }

    /** Writes one real export through the production staging function. */
    private fun stage(marker: String = "event"): File =
        prepareAuditLogArchive(
            cacheDir = context.cacheDir,
            allowedSourceRoot = context.filesDir,
            sourcePaths = emptyList(),
            performanceLogBytes = "schema=2 op=$marker\n".toByteArray(),
        )

    /** Parses the inner ACTION_SEND the chooser delivers when White Noise is picked as the target. */
    private fun chooserRequest(archive: File): ShareRequest {
        val chooser = auditLogShareChooserIntent(context, archive, "Share")
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        return requireNotNull(parseShareRequest(send))
    }

    /** The production admission rule, with provider callbacks that fail if the URI is ever resolved. */
    private fun importer() =
        ShareFileImporter(
            files,
            { _, _ -> error("Must not query the app's own provider") },
            { _, _ -> error("Must not open the app's own provider") },
            isAppOwnedProvider = { it.authority == authority },
            trustedSource = { stagedDiagnosticExportSource(context, it) },
        )

    /** Wraps arbitrary inbound URIs as one share request carrying the generic binary type. */
    private fun request(uris: List<Uri>): ShareRequest {
        val payload = SharePayload(null, uris, "application/octet-stream")
        return ShareRequest(payload, null, "forged")
    }

    private companion object {
        const val UNKNOWN_SESSION = "00000000-0000-0000-0000-000000000000"
    }
}
