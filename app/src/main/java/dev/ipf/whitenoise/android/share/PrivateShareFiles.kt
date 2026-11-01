package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_DISK_RESERVE_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_TAG_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

internal const val PRIVATE_SHARE_DIRECTORY = "inbound-share-files"
internal const val PRIVATE_SHARE_MAX_BYTES = 64L * 1024 * 1024
internal const val PRIVATE_SHARE_DOCUMENT_MAX_BYTES =
    dev.ipf.whitenoise.android.state.ConversationController.MEDIA_RETAINED_MAX_BYTES
internal const val PRIVATE_SHARE_BATCH_MAX_BYTES = 256L * 1024 * 1024
internal const val PRIVATE_SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** Android intake files, never protocol data. Only the non-exported provider can read them. */
internal class PrivateShareFiles private constructor(
    rootForIo: () -> File,
    private val authority: String,
    limitsForIo: () -> ShareImportLimits,
    private val usableBytes: (File) -> Long,
) {
    constructor(
        root: File,
        authority: String,
        limits: ShareImportLimits = LEGACY_SHARE_IMPORT_LIMITS,
        usableBytes: (File) -> Long = ::usableSpaceFor,
    ) : this({ root }, authority, { limits }, usableBytes)

    constructor(context: Context) : this(
        privateShareRootForIo(context),
        "${context.packageName}.private-share",
        ::nativeShareImportLimits,
        ::usableSpaceFor,
    )

    private val root by lazy(rootForIo)
    val limits by lazy(limitsForIo)
    val leases by lazy { PrivateShareLeases(root, ::owns, ::metadata, ::delete) { limits } }

    /** Checks only the app-private provider identity, without touching storage or granting external access. */
    fun owns(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == authority

    /** Allocates an owner-only original within the global quota; allocation/permission failures reject intake. */
    fun newFile(): Pair<Uri, File> =
        synchronized(privateShareLock) {
            privateShareDirectory(root)
            if (availableBytes() <= FILE_BACKED_TAG_BYTES) {
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

    /** Only serialized intake writes source bytes; retained shelves and sends can release them concurrently. */
    fun availableBytes(): Long =
        synchronized(privateShareLock) {
            val used = storedBytes()
            minOf(
                (limits.storageBytes - used).coerceAtLeast(0),
                (usableBytes(root) - FILE_BACKED_DISK_RESERVE_BYTES).coerceAtLeast(0),
            )
        }

    /** Includes each retained source's future encryption tag in the shared staging quota. */
    private fun storedBytes(): Long =
        root.listFiles().orEmpty().sumOf { file ->
            if (file.extension == "bin") file.length() + FILE_BACKED_TAG_BYTES else 0L
        }

    /**
     * Resolves only a regular, non-symlink UUID original directly under the private root; missing sources return
     * null.
     */
    fun resolve(uri: Uri): File? {
        val id = uri.pathSegments.singleOrNull()
        val file = if (owns(uri) && id != null && UUID_PATTERN.matches(id)) File(root, "$id.bin") else null
        return file?.takeIf {
            it.isFile && !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile
        }
    }

    /** Commits bounded metadata after copying completes; failed storage does not make an original ready for staging. */
    fun finish(
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
    ) {
        val file = requireNotNull(resolve(uri))
        if (size != file.length() || size !in 1..limits.fileBytes(mime)) throw IOException("Invalid share size")
        if (storedBytes() > limits.storageBytes || usableBytes(root) < FILE_BACKED_DISK_RESERVE_BYTES) {
            throw IOException("Share storage reserve exhausted")
        }
        writePrivateShareJson(
            File(root, "${file.nameWithoutExtension}.json"),
            JSONObject()
                .put("name", name)
                .put("mime", mime)
                .put("size", size),
        )
    }

    /** Accepts metadata only while the original exists and its actual length matches the committed size budget. */
    fun metadata(uri: Uri): JSONObject? =
        resolve(uri)?.let { file ->
            readPrivateShareJson(File(root, "${file.nameWithoutExtension}.json"))?.takeIf {
                it.optLong("size", -1) == file.length() &&
                    file.length() in 1..limits.fileBytes(it.optString("mime"))
            }
        }

    /**
     * Removes an original and its metadata after the caller has reconciled ownership; foreign/missing URIs are
     * ignored.
     */
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

/** Composition can create an ownership checker without creating Android's no-backup directory on Main. */
private fun privateShareRootForIo(context: Context): () -> File {
    val app = context.applicationContext
    return { File(app.noBackupFilesDir, PRIVATE_SHARE_DIRECTORY) }
}

/**
 * Revalidates recovered private originals on I/O, reporting expired/missing entries as interruption without
 * reopening grants.
 */
internal fun validateImportedShare(
    context: Context,
    request: ShareRequest,
): ShareRequest {
    val files = PrivateShareFiles(context)
    files.cleanStale()
    if (request.payload.streamUris.isEmpty() && ShareImportError.Interrupted in request.payload.importErrors) {
        files.leases.releaseRequest(request.requestId)
    }
    val retained =
        capPrivateShareShelf(
            request.payload.streamUris.filter(files::owns),
            files::metadata,
            files.limits,
        )
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
