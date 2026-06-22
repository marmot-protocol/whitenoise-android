package dev.ipf.darkmatter.updates

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
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
    fun hasTag(
        name: String,
        value: String,
    ): Boolean = tags.any { it.firstOrNull() == name && it.getOrNull(1) == value }

    fun firstTagValue(name: String): String? = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

    fun tagValues(name: String): List<String> = tags.mapNotNull { tag -> tag.takeIf { it.firstOrNull() == name }?.getOrNull(1) }

    fun canonicalJson(): String =
        buildString {
            append('[')
            append('0')
            append(',')
            append(JSONObject.quote(pubkey))
            append(',')
            append(createdAt)
            append(',')
            append(kind)
            append(',')
            append('[')
            tags.forEachIndexed { index, tag ->
                if (index > 0) append(',')
                append('[')
                tag.forEachIndexed { tagIndex, value ->
                    if (tagIndex > 0) append(',')
                    append(JSONObject.quote(value))
                }
                append(']')
            }
            append(']')
            append(',')
            append(JSONObject.quote(content))
            append(']')
        }

    fun computedIdHex(): String = sha256(canonicalJson().toByteArray(Charsets.UTF_8)).toHex()

    companion object {
        fun fromJson(json: JSONObject): NostrEvent? {
            val tags = json.optJSONArray("tags") ?: return null
            return NostrEvent(
                id = json.optString("id").lowercase(Locale.US).takeIf { it.isHex(64) } ?: return null,
                pubkey = json.optString("pubkey").lowercase(Locale.US).takeIf { it.isHex(64) } ?: return null,
                createdAt = json.optLong("created_at"),
                kind = json.optInt("kind"),
                tags = tags.toStringLists(),
                content = json.optString("content"),
                sig = json.optString("sig").lowercase(Locale.US).takeIf { it.isHex(128) } ?: return null,
            )
        }
    }
}

internal object NostrEventVerifier {
    fun verifies(event: NostrEvent): Boolean {
        val message = event.computedIdHex()
        if (!message.equals(event.id, ignoreCase = true)) return false
        return BIP340.verify(
            publicKeyHex = event.pubkey,
            messageHex = message,
            signatureHex = event.sig,
        )
    }
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0 || !isHex(length)) return null
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

private fun String.isHex(expectedLength: Int): Boolean = length == expectedLength && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun JSONArray.toStringLists(): List<List<String>> =
    buildList {
        for (index in 0 until length()) {
            val tagArray = optJSONArray(index) ?: continue
            add(
                buildList {
                    for (tagIndex in 0 until tagArray.length()) {
                        add(tagArray.optString(tagIndex))
                    }
                },
            )
        }
    }
