package dev.ipf.whitenoise.android.share

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal val privateShareLock = Any()
private const val PRIVATE_SHARE_METADATA_LIMIT = 16 * 1024

internal fun writePrivateShareJson(
    file: File,
    json: JSONObject,
) {
    privateShareDirectory(file.parentFile!!)
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    var committed = false
    try {
        val pending = File("${file.path}.new")
        privateShareFile(if (pending.exists()) pending else file)
        out.write(json.toString().toByteArray())
        atomic.finishWrite(out)
        committed = true
        privateShareFile(file)
    } finally {
        if (!committed) runCatching { atomic.failWrite(out) }
    }
}

internal fun readPrivateShareJson(file: File): JSONObject? =
    if (!file.isFile ||
        Files.isSymbolicLink(file.toPath()) ||
        file.length() > PRIVATE_SHARE_METADATA_LIMIT
    ) {
        null
    } else {
        runCatching { JSONObject(file.readText()) }.getOrNull()
    }

internal fun privateShareDirectory(file: File) {
    Files.createDirectories(
        file.toPath(),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwx------"))
}

internal fun privateShareFile(file: File) {
    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
}
