package dev.ipf.whitenoise.android.share

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal val privateShareLock = Any()
private const val PRIVATE_SHARE_METADATA_LIMIT = 16 * 1024

/**
 * Writes one owner-only metadata/lease record on I/O; failed commits must propagate before ownership can be
 * released.
 */
internal fun writePrivateShareJson(
    file: File,
    json: JSONObject,
) {
    val encoded = json.toString().toByteArray(Charsets.UTF_8)
    if (encoded.size > PRIVATE_SHARE_METADATA_LIMIT) throw IOException("Private share record exceeds its budget")
    privateShareDirectory(file.parentFile!!)
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    var committed = false
    try {
        val pending = File("${file.path}.new")
        privateShareFile(if (pending.exists()) pending else file)
        out.write(encoded)
        // AtomicFile logs its own sync/rename failures; neither is a successful ownership commit.
        out.fd.sync()
        atomic.finishWrite(out)
        privateShareFile(file)
        if (
            !file.isFile ||
            Files.isSymbolicLink(file.toPath()) ||
            file.length() != encoded.size.toLong()
        ) {
            throw IOException("Private share record was not committed")
        }
        if (!file.readBytes().contentEquals(encoded)) throw IOException("Private share record was not committed")
        committed = true
    } finally {
        if (!committed) runCatching { atomic.failWrite(out) }
    }
}

/**
 * Reads bounded regular metadata on I/O; unsafe, oversized, missing or malformed records are treated as
 * unavailable.
 */
internal fun readPrivateShareJson(file: File): JSONObject? =
    if (!file.isFile ||
        Files.isSymbolicLink(file.toPath()) ||
        file.length() > PRIVATE_SHARE_METADATA_LIMIT
    ) {
        null
    } else {
        runCatching { JSONObject(file.readText()) }.getOrNull()
    }

/** Creates or restores an owner-only no-backup directory, propagating unsupported or failed permission changes. */
internal fun privateShareDirectory(file: File) {
    Files.createDirectories(
        file.toPath(),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwx------"))
}

/** Restricts a pending or committed intake record to its app owner before it is considered usable. */
internal fun privateShareFile(file: File) {
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
}
