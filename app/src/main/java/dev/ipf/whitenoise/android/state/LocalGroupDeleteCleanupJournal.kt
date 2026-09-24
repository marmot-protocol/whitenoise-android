package dev.ipf.whitenoise.android.state

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Android-owned data to clear only after MDK proves a local group wipe committed. */
internal data class PendingLocalGroupDeleteCleanup(
    val account: String,
    val groupIdHex: String,
    val mediaCacheKeys: List<String>,
    val ciphertextTags: Set<String>,
)

/**
 * A durable pre-delete intent. It lives beside, not inside, MDK because the referenced caches,
 * draft projection and notifications belong to Android. AtomicFile prevents a process death
 * between the native commit and its lost response from losing the cleanup instructions.
 */
internal class LocalGroupDeleteCleanupJournal(
    private val directory: File,
) {
    @Volatile private var pendingKnown: Boolean? = null

    fun hasPending(): Boolean = pendingKnown ?: (directory.listFiles()?.isNotEmpty() == true).also { pendingKnown = it }

    @Suppress("TooGenericExceptionCaught") // Preserve AtomicFile's previous version on every write failure.
    fun stage(pending: PendingLocalGroupDeleteCleanup) {
        require(pending.account.isNotBlank() && pending.groupIdHex.isNotBlank())
        check(directory.isDirectory || directory.mkdirs()) { "local delete journal directory unavailable" }
        val file = atomicFile(pending.account, pending.groupIdHex)
        val output = file.startWrite()
        try {
            output.write(encode(pending).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            pendingKnown = true
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    fun pending(): List<PendingLocalGroupDeleteCleanup> =
        directory.listFiles().orEmpty()
            .mapNotNull { file ->
                val name = file.name.removeSuffix(".bak").removeSuffix(".new")
                name.takeIf { it.endsWith(FILE_SUFFIX) }
            }.distinct()
            .mapNotNull { name ->
                runCatching {
                    val decoded = decode(String(AtomicFile(File(directory, name)).readFully(), Charsets.UTF_8))
                    check(fileName(decoded.account, decoded.groupIdHex) == name) { "local delete journal identity mismatch" }
                    decoded
                }
                    .onFailure { appStateDebug(it) { "local delete cleanup journal read failed" } }
                    .getOrNull()
            }

    fun finish(pending: PendingLocalGroupDeleteCleanup) {
        atomicFile(pending.account, pending.groupIdHex).delete()
        pendingKnown = directory.listFiles()?.isNotEmpty() == true
    }

    private fun atomicFile(
        account: String,
        groupIdHex: String,
    ): AtomicFile = AtomicFile(File(directory, fileName(account, groupIdHex)))

    private fun fileName(
        account: String,
        groupIdHex: String,
    ): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$account\u0000$groupIdHex".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "$digest$FILE_SUFFIX"
    }

    private fun encode(pending: PendingLocalGroupDeleteCleanup): String =
        JSONObject()
            .put("version", 1)
            .put("account", pending.account)
            .put("groupIdHex", pending.groupIdHex)
            .put("mediaCacheKeys", JSONArray(pending.mediaCacheKeys))
            .put("ciphertextTags", JSONArray(pending.ciphertextTags.toList()))
            .toString()

    private fun decode(json: String): PendingLocalGroupDeleteCleanup {
        val value = JSONObject(json)
        require(value.getInt("version") == 1) { "unsupported local delete journal version" }
        fun strings(name: String): List<String> =
            value.getJSONArray(name).let { array ->
                (0 until array.length()).map(array::getString)
            }
        val account = value.getString("account")
        val groupIdHex = value.getString("groupIdHex")
        require(account.isNotBlank() && groupIdHex.isNotBlank()) { "local delete journal identity missing" }
        return PendingLocalGroupDeleteCleanup(
            account = account,
            groupIdHex = groupIdHex,
            mediaCacheKeys = strings("mediaCacheKeys"),
            ciphertextTags = strings("ciphertextTags").toSet(),
        )
    }

    private companion object {
        const val FILE_SUFFIX = ".json"
    }
}

/** A failed read is uncertainty, never proof of a committed wipe. */
@Suppress("ReturnCount") // Each safety gate stops before the Android-owned cleanup mutation.
internal suspend fun reconcilePendingLocalGroupDeleteCleanup(
    pending: PendingLocalGroupDeleteCleanup,
    accountReady: () -> Boolean,
    isGroupPresent: suspend () -> Boolean,
    cleanup: suspend (PendingLocalGroupDeleteCleanup) -> Boolean,
    finish: (PendingLocalGroupDeleteCleanup) -> Unit,
): Boolean {
    if (!accountReady()) return false
    val present = runCatchingCancellable { isGroupPresent() }.getOrElse { return false }
    if (present) {
        finish(pending)
        return true
    }
    if (!accountReady()) return false
    if (!cleanup(pending)) return false
    finish(pending)
    return true
}
