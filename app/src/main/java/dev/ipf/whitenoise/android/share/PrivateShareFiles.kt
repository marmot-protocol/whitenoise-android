package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

internal const val PRIVATE_SHARE_DIRECTORY = "inbound-share-files"
internal const val PRIVATE_SHARE_MAX_BYTES = 32L * 1024 * 1024
private const val PRIVATE_SHARE_STORAGE_BUDGET = 256L * 1024 * 1024
internal const val PRIVATE_SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Android intake files, never protocol data. Only the non-exported provider can read them. */
internal class PrivateShareFiles(
    private val root: File,
    private val authority: String,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, PRIVATE_SHARE_DIRECTORY),
        "${context.packageName}.private-share",
    )

    val leases = PrivateShareLeases(root, ::owns, ::metadata, ::delete)

    fun owns(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == authority

    fun newFile(): Pair<Uri, File> =
        synchronized(privateShareLock) {
            privateShareDirectory(root)
            if (root.listFiles().orEmpty().sumOf { if (it.extension == "bin") it.length() else 0L } +
                PRIVATE_SHARE_MAX_BYTES > PRIVATE_SHARE_STORAGE_BUDGET
            ) {
                throw IOException("Share storage budget exhausted")
            }
            val id = UUID.randomUUID().toString()
            val file = File(root, "$id.bin")
            if (!file.createNewFile()) throw IOException("Cannot create private share")
            try {
                privateShareFile(file)
            } catch (expected: IOException) {
                file.delete()
                throw expected
            }
            Uri.parse("content://$authority/$id") to file
        }

    fun resolve(uri: Uri): File? {
        val id = uri.pathSegments.singleOrNull()
        val file = if (owns(uri) && id != null && UUID_PATTERN.matches(id)) File(root, "$id.bin") else null
        return file?.takeIf {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile
        }
    }

    fun finish(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
    ) {
        val file = requireNotNull(resolve(uri))
        writePrivateShareJson(
            File(root, "${file.nameWithoutExtension}.json"),
            JSONObject()
                .put("name", name)
                .put("mime", mime)
                .put("size", size),
        )
    }

    fun metadata(uri: Uri): JSONObject? =
        resolve(uri)?.let { file ->
            readPrivateShareJson(File(root, "${file.nameWithoutExtension}.json"))?.takeIf {
                it.optLong("size", -1) == file.length() && file.length() in 1..PRIVATE_SHARE_MAX_BYTES
            }
        }

    fun delete(uri: Uri) {
        val file = resolve(uri) ?: return
        File(root, "${file.nameWithoutExtension}.json").delete()
        file.delete()
    }

    /** Called only by the serialized importer, before opening any new source. */
    fun recoverIncomplete() =
        synchronized(privateShareLock) {
            root
                .listFiles()
                .orEmpty()
                .filter {
                    it.extension == "bin" &&
                        !File(root, "${it.nameWithoutExtension}.json").isFile
                }.forEach(File::delete)
        }

    /** Bound stale plaintext retention, including partial files left by process death. */
    fun cleanStale(now: Long = System.currentTimeMillis()) =
        synchronized(privateShareLock) {
            root
                .listFiles()
                .orEmpty()
                .filter { now - it.lastModified() > PRIVATE_SHARE_MAX_AGE_MS }
                .forEach(File::delete)
        }

    private companion object {
        val UUID_PATTERN = Regex("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")
    }
}

internal fun validateImportedShare(
    context: Context,
    request: ShareRequest,
): ShareRequest {
    val files = PrivateShareFiles(context)
    files.cleanStale()
    if (request.payload.streamUris.isEmpty() && ShareImportError.Interrupted in request.payload.importErrors) {
        files.leases.releaseRequest(request.requestId)
    }
    val retained = request.payload.streamUris.filter { files.owns(it) && files.metadata(it) != null }
    return if (retained.size == request.payload.streamUris.size) {
        request
    } else {
        request.copy(
            payload =
                request.payload.copy(
                    streamUris = retained,
                    importReady = true,
                    importErrors = request.payload.importErrors + ShareImportError.Interrupted,
                    importRejectedCount =
                        request.payload.importRejectedCount + request.payload.streamUris.size - retained.size,
                ),
        )
    }
}
