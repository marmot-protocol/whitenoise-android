package dev.ipf.whitenoise.android.ui.common

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.core.GROUP_AVATAR_MAX_PAYLOAD_BYTES
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey

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
