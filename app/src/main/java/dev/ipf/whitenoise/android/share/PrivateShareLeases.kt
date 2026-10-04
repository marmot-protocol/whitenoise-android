package dev.ipf.whitenoise.android.share

import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Durable ownership references; one picker, destination shelf or queued send can release independently. */
internal class PrivateShareLeases(
    private val root: File,
    private val owns: (Uri) -> Boolean,
    private val metadata: (Uri) -> JSONObject?,
    private val delete: (Uri) -> Unit,
) {
    /** One recoverable shelf per explicitly selected account/chat. Calls run on I/O. */
    fun saveShelf(
        account: String,
        group: String,
        uris: List<Uri>,
    ) = synchronized(privateShareLock) {
        val shelf = privateShareLeaseFile(root, "shelf", "$account $group")
        val previous = loadShelf(account, group)
        val owned = capPrivateShareShelf(uris, metadata)
        if (owned.isEmpty()) {
            if (shelf.exists() && !shelf.delete()) throw IOException("Cannot release private share shelf")
        } else {
            writePrivateShareJson(
                shelf,
                JSONObject().put("account", account).put("uris", org.json.JSONArray(owned.map(Uri::toString))),
            )
        }
        previous.filterNot(owned::contains).forEach(::deleteIfUnreferenced)
        owned
    }

    /** A late composer write applies only its actual removals/additions, preserving a newer inbound import. */
    fun changeShelf(
        account: String,
        group: String,
        previous: List<Uri>,
        current: List<Uri>,
    ) = synchronized(privateShareLock) {
        val removed = previous.filterNot(current::contains)
        val added = current.filterNot(previous::contains)
        saveShelf(account, group, loadShelf(account, group).filterNot(removed::contains) + added)
    }

    fun loadShelf(
        account: String,
        group: String,
    ): List<Uri> =
        synchronized(privateShareLock) {
            val json =
                readPrivateShareJson(privateShareLeaseFile(root, "shelf", "$account $group"))
                    ?: return@synchronized emptyList()
            val rows = json.optJSONArray("uris") ?: return@synchronized emptyList()
            List(rows.length().coerceAtMost(MAX_PENDING_SHARE_URIS)) { Uri.parse(rows.optString(it)) }
                .filter { metadata(it) != null }
        }

    fun holdSend(
        id: String,
        uris: List<Uri>,
        account: String? = null,
    ) = synchronized(privateShareLock) {
        writePrivateShareJson(
            File(root, "send-$id.lease"),
            JSONObject().put("account", account).put("uris", org.json.JSONArray(uris.filter(owns).map(Uri::toString))),
        )
    }

    fun releaseSend(id: String) =
        synchronized(privateShareLock) {
            val file = File(root, "send-$id.lease")
            val rows = readPrivateShareJson(file)?.optJSONArray("uris")
            file.delete()
            if (rows != null) repeat(rows.length()) { deleteIfUnreferenced(Uri.parse(rows.optString(it))) }
        }

    /** The unresolved picker holds a separate lease until staging commits or cancellation finishes. */
    fun holdRequest(
        requestId: String,
        uris: List<Uri>,
    ) = synchronized(privateShareLock) {
        val owned = uris.filter(owns)
        if (owned.isEmpty()) {
            releaseRequest(requestId)
        } else {
            writePrivateShareJson(
                privateShareLeaseFile(root, "request", requestId),
                JSONObject().put("uris", org.json.JSONArray(owned.map(Uri::toString))),
            )
        }
    }

    fun releaseRequest(requestId: String) =
        synchronized(privateShareLock) {
            val file = privateShareLeaseFile(root, "request", requestId)
            val uris = readPrivateShareJson(file)?.optJSONArray("uris")
            file.delete()
            if (uris != null) repeat(uris.length()) { deleteIfUnreferenced(Uri.parse(uris.optString(it))) }
        }

    fun releasePendingRequests() =
        synchronized(privateShareLock) {
            val uris =
                root
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.startsWith("request-") && it.extension == "lease" }
                    .flatMap { file ->
                        val rows = readPrivateShareJson(file)?.optJSONArray("uris")
                        file.delete()
                        if (rows == null) emptyList() else List(rows.length()) { Uri.parse(rows.optString(it)) }
                    }
            uris.forEach(::deleteIfUnreferenced)
        }

    /** Wipe only this account's destination/send ownership; shared bytes stay with their other owners. */
    fun releaseAccount(account: String) =
        synchronized(privateShareLock) {
            require(account.isNotBlank())
            root.listFiles().orEmpty().filter { it.extension == "lease" }.forEach { file ->
                val json = readPrivateShareJson(file) ?: return@forEach
                if (json.optString("account") == account) {
                    if (!file.delete()) throw IOException("Cannot release private share account")
                    val rows = json.optJSONArray("uris")
                    if (rows != null) repeat(rows.length()) { deleteIfUnreferenced(Uri.parse(rows.optString(it))) }
                }
            }
        }

    private fun deleteIfUnreferenced(uri: Uri) {
        val referenced =
            root.listFiles().orEmpty().filter { it.extension == "lease" }.any { file ->
                readPrivateShareJson(file)?.optJSONArray("uris")?.let { rows ->
                    (0 until rows.length()).any { rows.optString(it) == uri.toString() }
                } == true
            }
        if (!referenced) delete(uri)
    }
}
