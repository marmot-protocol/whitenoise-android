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
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
                val mime = maestroExpectedFileMime(name)
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
    val expected = originals.flatMap { listOf(it.name, "${it.nameWithoutExtension}.json") }.toSet()
    val lease = entries.filter { it.extension == "lease" }.singleOrNull()?.takeIf { it.name.startsWith("shelf-") }
    return originals.size == retained.size &&
        lease != null &&
        entries.map { it.name }.toSet() == expected + lease.name &&
        maestroShareLeaseMatches(lease, owner, retained)
}

/** A concurrent shelf removal or unreadable/corrupt lease remains a false proof inside the outer deadline. */
private fun maestroShareLeaseMatches(
    file: File,
    owner: String,
    retained: List<MaestroImportedFileSnapshot>,
): Boolean =
    try {
        val lease = JSONObject(file.readText())
        val uris = lease.getJSONArray("uris")
        lease.optString("account") == owner &&
            List(uris.length()) { uris.getString(it) } == retained.map { it.uri.toString() }
    } catch (_: IOException) {
        false
    } catch (_: JSONException) {
        false
    }

private fun maestroPrivateCopyMatches(
    files: PrivateShareFiles,
    expected: MaestroImportedFileSnapshot,
): Boolean {
    val original = files.resolve(expected.uri) ?: return false
    val metadata = files.metadata(expected.uri)
    return metadata != null &&
        original.length() == 3L &&
        original.readBytes().contentEquals(byteArrayOf(1, 2, 3)) &&
        metadata.optLong("size") == 3L &&
        metadata.optString("name") == expected.name &&
        metadata.optString("mime") == expected.mime
}

private fun maestroExpectedFileMime(name: String): String =
    when (name.substringAfterLast('.')) {
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "log" -> "text/x-log"
        "ics" -> "text/calendar"
        "ttf" -> "font/ttf"
        "gltf" -> "model/gltf+json"
        "fixture" -> "application/octet-stream"
        else -> error("Unknown expected provider file type")
    }
