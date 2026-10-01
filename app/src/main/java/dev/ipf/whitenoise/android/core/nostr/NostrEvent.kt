package dev.ipf.whitenoise.android.core.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun firstTagValue(name: String): String? = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

    /** Complete event object; MDK owns canonical ID and signature validation. */
    fun toJson(): String =
        JSONObject()
            .put("id", id)
            .put("pubkey", pubkey)
            .put("created_at", createdAt)
            .put("kind", kind)
            .put("tags", JSONArray(tags.map { JSONArray(it) }))
            .put("content", content)
            .put("sig", sig)
            .toString()

    companion object {
        fun fromJson(json: JSONObject): NostrEvent? {
            val tags = json.optJSONArray("tags") ?: return null
            val createdAt =
                (json.opt("created_at") as? Number)
                    ?.toString()
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?: return null
            val kindLong =
                (json.opt("kind") as? Number)
                    ?.toString()
                    ?.toLongOrNull()
                    ?.takeIf { it in 0..Int.MAX_VALUE }
                    ?: return null
            return NostrEvent(
                id = (json.opt("id") as? String)?.lowercase(Locale.US)?.takeIf { it.isHex(64) } ?: return null,
                pubkey = (json.opt("pubkey") as? String)?.lowercase(Locale.US)?.takeIf { it.isHex(64) } ?: return null,
                createdAt = createdAt,
                kind = kindLong.toInt(),
                tags = tags.toStringListsOrNull() ?: return null,
                content = json.opt("content") as? String ?: return null,
                sig = (json.opt("sig") as? String)?.lowercase(Locale.US)?.takeIf { it.isHex(128) } ?: return null,
            )
        }
    }
}

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0 || !isHex(length)) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

private fun String.isHex(expectedLength: Int): Boolean = length == expectedLength && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun JSONArray.toStringListsOrNull(): List<List<String>>? =
    buildList {
        for (index in 0 until length()) {
            val tagArray = optJSONArray(index) ?: return null
            add(
                buildList {
                    for (tagIndex in 0 until tagArray.length()) {
                        add(tagArray.opt(tagIndex) as? String ?: return null)
                    }
                },
            )
        }
    }
