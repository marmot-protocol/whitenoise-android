package dev.ipf.whitenoise.android.maestro

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.share.PRIVATE_SHARE_DIRECTORY
import dev.ipf.whitenoise.android.share.PrivateShareFiles
import dev.ipf.whitenoise.android.share.ShareRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Snapshot actual completed private copies, never fixture-injected importer results. */
internal suspend fun captureMaestroShareFiles(
    context: Context,
    request: ShareRequest,
    fixture: String,
): List<MaestroImportedFileSnapshot> =
    withContext(Dispatchers.IO) {
        val names =
            if (fixture.startsWith("share-external-") && !fixture.contains("denied") && !fixture.contains("empty")) {
                maestroExternalShareNames(fixture).distinct()
            } else {
                emptyList()
            }
        check(request.payload.streamUris.size == names.size)
        val files = PrivateShareFiles(context)
        val snapshots =
            request.payload.streamUris.zip(names).map { (uri, name) ->
                val mime = if (name.endsWith(".md")) "text/markdown" else "text/csv"
                MaestroImportedFileSnapshot(uri, name, mime).also { check(maestroPrivateCopyMatches(files, it)) }
            }
        if (fixture == "share-external-expired-grant") revokeMaestroSourceReadGrant(context)
        snapshots
    }

/** Revoke the sender's real URI grant only after copying, then require Android's permission check to deny access. */
private fun revokeMaestroSourceReadGrant(context: Context) {
    val external = InstrumentationRegistry.getInstrumentation().context
    val source =
        Uri.parse("content://${external.packageName}.external-share-test-files/shared-files/maestro-document.md")
    context.contentResolver.query(source, arrayOf("revoke_read_grant"), null, null, null)?.use {
        check(it.moveToFirst() && it.getInt(0) == 1)
    } ?: error("Missing external grant-revocation receipt")
    check(
        context.checkUriPermission(source, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_DENIED,
    )
}

/** Fresh production lease reads require exact owner/order/bytes or deletion, with no orphan copies after removal. */
internal suspend fun verifyMaestroShareFiles(
    context: Context,
    before: MaestroInboundShareBaseline,
    postcondition: String,
): Boolean =
    withContext(Dispatchers.IO) {
        val files = PrivateShareFiles(context)
        val retained = if (postcondition == "share-request-staged") before.files else emptyList()
        val ownerShelf = retained.map { it.uri }
        val shelvesMatch =
            before.accountIds.all { (account, id) ->
                files.leases.loadShelf(id, before.group) == if (account == before.owner) ownerShelf else emptyList()
            }
        val copiesMatch = retained.all { maestroPrivateCopyMatches(files, it) }
        val removed = before.files.filterNot(retained::contains).all { files.resolve(it.uri) == null }
        shelvesMatch &&
            copiesMatch &&
            removed &&
            maestroShareDiskOwnershipMatches(context, files, retained, checkNotNull(before.accountIds[before.owner]))
    }

private fun maestroShareDiskOwnershipMatches(
    context: Context,
    files: PrivateShareFiles,
    retained: List<MaestroImportedFileSnapshot>,
    owner: String,
): Boolean {
    val entries = File(context.noBackupFilesDir, PRIVATE_SHARE_DIRECTORY).listFiles().orEmpty()
    if (retained.isEmpty()) return entries.isEmpty()
    val originals = retained.mapNotNull { files.resolve(it.uri) }
    if (originals.size != retained.size) return false
    val expected = originals.flatMap { listOf(it.name, "${it.nameWithoutExtension}.json") }.toSet()
    val leases = entries.filter { it.extension == "lease" }
    if (leases.size != 1 || !leases.single().name.startsWith("shelf-")) return false
    val lease = JSONObject(leases.single().readText())
    val uris = lease.getJSONArray("uris")
    return entries.map { it.name }.toSet() == expected + leases.single().name &&
        lease.optString("account") == owner &&
        List(uris.length()) { uris.getString(it) } == retained.map { it.uri.toString() }
}

private fun maestroPrivateCopyMatches(
    files: PrivateShareFiles,
    expected: MaestroImportedFileSnapshot,
): Boolean {
    val original = files.resolve(expected.uri) ?: return false
    val metadata = files.metadata(expected.uri) ?: return false
    return original.length() == 3L &&
        original.readBytes().contentEquals(byteArrayOf(1, 2, 3)) &&
        metadata.optLong("size") == 3L &&
        metadata.optString("name") == expected.name &&
        metadata.optString("mime") == expected.mime
}
