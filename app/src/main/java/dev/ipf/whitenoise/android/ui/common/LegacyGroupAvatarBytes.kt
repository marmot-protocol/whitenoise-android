package dev.ipf.whitenoise.android.ui.common

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GROUP_AVATAR_MAX_PAYLOAD_BYTES
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/**
 * A bounded-list miss proves neither selection nor deletion. Read MDK's current snapshot in that case,
 * and verify it again after the byte read so account/source changes never publish stale animation.
 */
@Suppress("LongParameterList")
internal suspend fun readLegacyGroupAvatarBytes(
    expected: AppGroupRecordFfi,
    accountRef: String,
    ownerIsCurrent: () -> Boolean,
    currentGroup: () -> AppGroupRecordFfi?,
    readSnapshotGroup: suspend () -> AppGroupRecordFfi?,
    readBytes: suspend () -> ByteArray?,
): ByteArray? {
    /** Rechecks the owning session and exact image identity, using MDK when the retained row is absent. */
    suspend fun sourceIsCurrent(): Boolean {
        if (!ownerIsCurrent()) return false
        val current = currentGroup() ?: readSnapshotGroup()
        return ownerIsCurrent() &&
            current != null &&
            current.groupIdHex.equals(expected.groupIdHex, ignoreCase = true) &&
            ProfileSanitizer.protocolImageUrl(current.avatarUrl) ==
            ProfileSanitizer.protocolImageUrl(expected.avatarUrl) &&
            encryptedGroupAvatarCacheKey(accountRef, current) == encryptedGroupAvatarCacheKey(accountRef, expected)
    }
    val bytes = if (sourceIsCurrent()) readBytes() else null
    return bytes?.takeIf { it.size <= GROUP_AVATAR_MAX_PAYLOAD_BYTES && sourceIsCurrent() }
}

/**
 * URL thumbnail acquisition belongs to the ordinary first-frame loader. Re-entering a static, missing
 * or source-evicted avatar keeps its warm pixels instead of starting a second media fetch. The legacy
 * encrypted path also reads retained first-frame bytes; full viewers keep their original reader.
 */
internal fun WhiteNoiseAppState.legacyGroupAnimationReader(
    accountRef: String?,
    legacyUrl: String?,
    encryptedCachedSource: () -> ByteArray?,
): suspend () -> ByteArray? {
    val owner = captureHostPerformanceRuntimeOwner()
    val generation = runtimeGeneration
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val ownsSource = {
        val sameAccount = accountRef != null && activeAccountRef == accountRef
        val sameRuntime = runtimeGeneration == generation && owner?.let { ownsHostPerformanceRuntimeOwner(it) } == true
        sameAccount && sameRuntime && AvatarImageLoader.currentCacheLifetime() == lifetime
    }
    return {
        if (ownsSource()) {
            val bytes =
                if (legacyUrl == null) encryptedCachedSource() else AvatarImageLoader.peekAnimatedSource(legacyUrl)
            bytes?.takeIf { ownsSource() }
        } else {
            null
        }
    }
}
