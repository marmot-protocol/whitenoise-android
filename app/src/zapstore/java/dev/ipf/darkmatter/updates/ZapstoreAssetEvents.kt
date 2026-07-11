package dev.ipf.darkmatter.updates

import java.util.Locale

private const val KIND_ZAPSTORE_ASSET = 3063
private const val KIND_ZAPSTORE_RELEASE = 30063
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private sealed class UniqueTagValue {
    data object Absent : UniqueTagValue()

    data object Ambiguous : UniqueTagValue()

    data class Present(
        val value: String,
    ) : UniqueTagValue()
}

private sealed class SizeTagValue {
    data object Absent : SizeTagValue()

    data object Invalid : SizeTagValue()

    data object Ambiguous : SizeTagValue()

    data class Present(
        val bytes: Long,
    ) : SizeTagValue()
}

internal object ZapstoreAssetEvents {
    fun assetEventIdsFromReleaseEvent(
        event: NostrEvent,
        appId: String,
        publisherPubkey: String,
        releaseDTag: String,
    ): Set<String>? {
        if (event.kind != KIND_ZAPSTORE_RELEASE) return null
        if (event.pubkey != publisherPubkey) return null
        if (event.firstTagValue("d") != releaseDTag) return null
        if (!NostrEventVerifier.verifies(event)) return null
        if (ZapstoreAddress.versionFromReleaseDTag(releaseDTag, appId) == null) return null
        val ids =
            event.tags
                .asSequence()
                .filter { tag -> tag.firstOrNull() == "e" }
                .mapNotNull { tag -> tag.getOrNull(1)?.lowercase(Locale.US)?.takeIf { id -> id.isSha256Hex() } }
                .toSet()
        return ids.takeIf { it.isNotEmpty() }
    }

    fun parseVerifiedApkAsset(
        event: NostrEvent,
        referencedId: String,
        appId: String,
        version: String,
        platformId: String,
        publisherPubkey: String,
    ): ZapstoreApkAsset? {
        if (event.kind != KIND_ZAPSTORE_ASSET) return null
        if (event.pubkey != publisherPubkey) return null
        if (!event.id.equals(referencedId, ignoreCase = true)) return null
        if (!NostrEventVerifier.verifies(event)) return null
        if (!event.computedIdHex().equals(referencedId, ignoreCase = true)) return null
        return parseApkAssetTags(
            event = event,
            appId = appId,
            version = version,
            platformId = platformId,
        )
    }

    internal fun parseApkAssetTags(
        event: NostrEvent,
        appId: String,
        version: String,
        platformId: String,
    ): ZapstoreApkAsset? {
        val taggedAppId = event.requiredUniqueTagValue("i") ?: return null
        if (taggedAppId != appId) return null

        val taggedVersion = event.requiredUniqueTagValue("version") ?: return null
        if (taggedVersion != version) return null

        val sha256Hex =
            event
                .requiredUniqueTagValue("x")
                ?.lowercase(Locale.US)
                ?.takeIf { it.isSha256Hex() }
                ?: return null

        val mime = event.requiredUniqueTagValue("m") ?: return null
        if (mime != AndroidAbi.APK_MIME) return null

        val platformIds =
            event.tags
                .asSequence()
                .filter { tag -> tag.firstOrNull() == "f" }
                .mapNotNull { tag -> tag.getOrNull(1)?.takeIf { it.isNotBlank() } }
                .toSet()
        if (!platformIds.contains(platformId)) return null

        val downloadUrl =
            event
                .requiredUniqueTagValue("url")
                ?.takeIf { it.isTrustedHttpsUrl() }
                ?: return null

        val sizeBytes =
            when (val sizeTag = event.parseSizeTag()) {
                SizeTagValue.Absent -> null
                is SizeTagValue.Present -> sizeTag.bytes
                SizeTagValue.Ambiguous,
                SizeTagValue.Invalid,
                -> return null
            }

        return ZapstoreApkAsset(
            eventId = event.id.lowercase(Locale.US),
            appId = taggedAppId,
            version = taggedVersion,
            sha256Hex = sha256Hex,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes,
            platformIds = platformIds,
        )
    }

    fun selectUniqueApkAsset(
        events: List<NostrEvent>,
        referencedIds: Set<String>,
        appId: String,
        version: String,
        platformId: String,
        publisherPubkey: String,
    ): ZapstoreApkAsset? {
        val matches =
            events
                .asSequence()
                .filter { event -> event.id.lowercase(Locale.US) in referencedIds }
                .mapNotNull { event ->
                    parseVerifiedApkAsset(
                        event = event,
                        referencedId = event.id.lowercase(Locale.US),
                        appId = appId,
                        version = version,
                        platformId = platformId,
                        publisherPubkey = publisherPubkey,
                    )
                }.toList()
        return matches.singleOrNull()
    }
}

private fun NostrEvent.uniqueTagValue(name: String): UniqueTagValue {
    val matchingTags = tags.filter { tag -> tag.firstOrNull() == name }
    return when (matchingTags.size) {
        0 -> UniqueTagValue.Absent
        1 -> matchingTags[0].getOrNull(1)?.let(UniqueTagValue::Present) ?: UniqueTagValue.Ambiguous
        else -> UniqueTagValue.Ambiguous
    }
}

private fun NostrEvent.requiredUniqueTagValue(name: String): String? =
    when (val tag = uniqueTagValue(name)) {
        is UniqueTagValue.Present -> tag.value
        UniqueTagValue.Absent,
        UniqueTagValue.Ambiguous,
        -> null
    }

private fun NostrEvent.parseSizeTag(): SizeTagValue {
    val sizeTags = tags.filter { tag -> tag.firstOrNull() == "size" }
    return when (sizeTags.size) {
        0 -> SizeTagValue.Absent
        1 -> {
            val raw = sizeTags[0].getOrNull(1)
            if (raw.isNullOrBlank()) return SizeTagValue.Invalid
            val value = raw.toLongOrNull() ?: return SizeTagValue.Invalid
            if (value <= 0L) SizeTagValue.Invalid else SizeTagValue.Present(value)
        }
        else -> SizeTagValue.Ambiguous
    }
}

private fun String.isSha256Hex(): Boolean = length == 64 && SHA256_HEX.matches(lowercase(Locale.US))

private fun String.isTrustedHttpsUrl(): Boolean {
    if (!startsWith("https://", ignoreCase = true)) return false
    val host = runCatching { java.net.URI(this).host }.getOrNull() ?: return false
    return host.isNotBlank()
}
