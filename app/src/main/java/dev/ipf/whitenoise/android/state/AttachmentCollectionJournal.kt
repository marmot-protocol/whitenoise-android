package dev.ipf.whitenoise.android.state

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The largest serialized journal record one operation may write. */
internal const val ATTACHMENT_COLLECTION_JOURNAL_MAX_BYTES = 256 * 1024

/** How long a journal record outlives its last update before it is deleted unread. */
internal const val ATTACHMENT_COLLECTION_JOURNAL_TTL_MS = 24L * 60 * 60 * 1000

private const val JOURNAL_VERSION = 1
private const val JOURNAL_DIRECTORY = "attachment_collections"
private const val JOURNAL_SUFFIX = ".json"
private const val JOURNAL_NAME_HEX_CHARS = 32

/**
 * Metadata-only record of in-progress collection sends, so a restarted process can tell what MDK already
 * accepted and what still needs sending. It holds ids, provider URIs, states and MDK send tokens — never
 * attachment bytes, captions or file names — and lives under no-backup storage, one file per operation,
 * grouped by hashed account and conversation. Records older than [ttlMs] are deleted on every read.
 */
internal class AttachmentCollectionJournal(
    noBackupDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = ATTACHMENT_COLLECTION_JOURNAL_TTL_MS,
) {
    private val root = File(noBackupDirectory, JOURNAL_DIRECTORY)

    /**
     * Writes [operation] atomically and reports whether it fit. A record over the size cap is written again
     * without its URIs, so a restart still learns what MDK accepted and names the rest as unrecoverable.
     */
    @Synchronized
    fun write(operation: AttachmentCollectionOperation): Boolean {
        val full = encodeCollectionOperation(operation, withUris = true)
        val bytes =
            full.takeIf { it.size <= ATTACHMENT_COLLECTION_JOURNAL_MAX_BYTES }
                ?: encodeCollectionOperation(operation, withUris = false)
        val withinCount = operation.items.size <= ATTACHMENT_COLLECTION_MAX_ITEMS
        if (!withinCount || bytes.size > ATTACHMENT_COLLECTION_JOURNAL_MAX_BYTES) return false
        val target = fileFor(operation.accountRef, operation.groupIdHex, operation.id)
        return runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(target)) { "journal rename failed" }
        }.isSuccess
    }

    /** Every live operation recorded for one conversation, oldest first; expired or unreadable records go. */
    @Synchronized
    fun read(
        accountRef: String,
        groupIdHex: String,
    ): List<AttachmentCollectionOperation> {
        val directory = conversationDirectory(accountRef, groupIdHex)
        val now = clock()
        return directory
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(JOURNAL_SUFFIX) }
            .mapNotNull { file ->
                val operation = runCatching { decodeCollectionOperation(file.readBytes()) }.getOrNull()
                val live =
                    operation?.takeIf {
                        it.accountRef == accountRef && it.groupIdHex == groupIdHex && now - it.updatedAtMs <= ttlMs
                    }
                if (live == null) file.delete()
                live
            }.sortedBy { it.createdAtMs }
    }

    /** Deletes one operation's record, after terminal success, discard or destination invalidation. */
    @Synchronized
    fun delete(
        accountRef: String,
        groupIdHex: String,
        operationId: String,
    ) {
        fileFor(accountRef, groupIdHex, operationId).delete()
    }

    /** Deletes every record of a conversation whose destination is gone. */
    @Synchronized
    fun deleteConversation(
        accountRef: String,
        groupIdHex: String,
    ) {
        conversationDirectory(accountRef, groupIdHex).deleteRecursively()
    }

    /** Deletes every record of a signed-out account. */
    @Synchronized
    fun deleteAccount(accountRef: String) {
        File(root, journalHash(accountRef)).deleteRecursively()
    }

    /** Deletes every expired or unreadable record in every conversation. */
    @Synchronized
    fun sweepExpired() {
        val now = clock()
        root.walkBottomUp().filter { it.isFile }.forEach { file ->
            val operation = runCatching { decodeCollectionOperation(file.readBytes()) }.getOrNull()
            if (operation == null || now - operation.updatedAtMs > ttlMs) file.delete()
        }
        root.walkBottomUp().filter { it.isDirectory && it != root && it.list().isNullOrEmpty() }.forEach(File::delete)
    }

    /** The directory that holds one conversation's records, named by hashes rather than raw ids. */
    private fun conversationDirectory(
        accountRef: String,
        groupIdHex: String,
    ): File = File(File(root, journalHash(accountRef)), journalHash(groupIdHex))

    /** One operation's record file. */
    private fun fileFor(
        accountRef: String,
        groupIdHex: String,
        operationId: String,
    ): File = File(conversationDirectory(accountRef, groupIdHex), journalHash(operationId) + JOURNAL_SUFFIX)
}

/** A short, filesystem-safe name for an identifier. */
private fun journalHash(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(JOURNAL_NAME_HEX_CHARS)

/** Serializes the metadata of [operation]; without [withUris] every URI is left blank. */
internal fun encodeCollectionOperation(
    operation: AttachmentCollectionOperation,
    withUris: Boolean,
): ByteArray {
    val items = JSONArray()
    operation.items.forEach { item ->
        items.put(
            JSONObject()
                .put("id", item.id)
                .put("uri", if (withUris) item.uri else "")
                .put("kind", item.kind.name)
                .put("state", item.state.name),
        )
    }
    val chunks = JSONArray()
    operation.chunks.forEach { chunk ->
        chunks.put(
            JSONObject()
                .put("id", chunk.id)
                .put("index", chunk.index)
                .put("items", JSONArray(chunk.itemIds))
                .put("caption", chunk.carriesCaption)
                .put("token", chunk.clientToken ?: JSONObject.NULL)
                .put("state", chunk.state.name),
        )
    }
    return JSONObject()
        .put("v", JOURNAL_VERSION)
        .put("id", operation.id)
        .put("account", operation.accountRef)
        .put("group", operation.groupIdHex)
        .put("created", operation.createdAtMs)
        .put("updated", operation.updatedAtMs)
        .put("items", items)
        .put("chunks", chunks)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)
}

/** Reads a record written by [encodeCollectionOperation]; a restored operation always starts paused. */
internal fun decodeCollectionOperation(bytes: ByteArray): AttachmentCollectionOperation {
    val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
    require(json.getInt("v") == JOURNAL_VERSION) { "unknown journal version" }
    val items = json.getJSONArray("items")
    val chunks = json.getJSONArray("chunks")
    return AttachmentCollectionOperation(
        id = json.getString("id"),
        accountRef = json.getString("account"),
        groupIdHex = json.getString("group"),
        items =
            List(items.length()) { index ->
                val item = items.getJSONObject(index)
                CollectionItem(
                    id = item.getString("id"),
                    uri = item.getString("uri"),
                    kind = CollectionItemKind.valueOf(item.getString("kind")),
                    state = CollectionItemState.valueOf(item.getString("state")),
                )
            },
        chunks =
            List(chunks.length()) { index ->
                val chunk = chunks.getJSONObject(index)
                val ids = chunk.getJSONArray("items")
                CollectionChunk(
                    id = chunk.getString("id"),
                    index = chunk.getInt("index"),
                    itemIds = List(ids.length()) { ids.getString(it) },
                    carriesCaption = chunk.getBoolean("caption"),
                    clientToken = chunk.optString("token").takeIf { !chunk.isNull("token") && it.isNotEmpty() },
                    state = CollectionChunkState.valueOf(chunk.getString("state")),
                )
            },
        createdAtMs = json.getLong("created"),
        updatedAtMs = json.getLong("updated"),
        paused = true,
    )
}
