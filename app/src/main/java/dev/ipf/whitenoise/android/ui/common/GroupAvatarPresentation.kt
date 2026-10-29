package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.isRenderable
import dev.ipf.whitenoise.android.state.retainedAvatarBytesReader

/**
 * Pixels and a legacy URL belong to the same current, explicitly owned presentation. [animationKey] is
 * set only when [image] is a stored person's profile picture, never a group-owned image, and names the
 * loader entry whose animated source may play over it.
 */
internal data class GroupAvatarPresentation(
    val image: ImageBitmap?,
    val pictureUrl: String?,
    val readOriginalBytes: (suspend () -> ByteArray?)? = null,
    val animationKey: String? = null,
    val groupAnimation: GroupAvatarAnimation? = null,
    /**
     * The worker domain for loading [pictureUrl]: single-use for a group's URL picture, whose host chooses its bytes,
     * and the public profiles for a person's published picture. A private contact handle decodes in its own domain.
     */
    val pictureDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
)

/** A durable group selection keeps viewer bytes and thumbnail admission under the same pinned owner. */
internal fun WhiteNoiseAppState.durableGroupAvatarPresentation(
    group: AppGroupRecordFfi,
    asset: AvatarAssetFfi,
    accountRef: String?,
    image: ImageBitmap?,
    personPicture: Boolean,
): GroupAvatarPresentation {
    val durableKey = accountRef?.let { asset.takeIf { it.isRenderable() }?.cacheKey(it) }
    val ownsGroupPicture = GroupProjector.ownsGroupPicture(group, asset, personPicture)
    val owner = captureHostPerformanceRuntimeOwner()
    val runtime = runtimeGeneration
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val ownsSource = {
        val sameRuntime = owner?.let { ownsHostPerformanceRuntimeOwner(it) } == true
        val sameAccount = activeAccountRef == accountRef && runtimeGeneration == runtime
        sameAccount && sameRuntime && AvatarImageLoader.currentCacheLifetime() == lifetime
    }
    // The first-frame loader already retains bounded GIF bytes. A thumbnail must never re-read
    // a warm static asset merely to discover its format; evicted sources retain the static frame.
    val animation =
        if (ownsGroupPicture && durableKey != null && owner != null) {
            GroupAvatarAnimation("$runtime|$lifetime|${group.groupIdHex}|$durableKey") {
                if (ownsSource()) {
                    AvatarImageLoader.peekAnimatedSource(durableKey)?.takeIf { ownsSource() }
                } else {
                    null
                }
            }
        } else {
            null
        }
    return GroupAvatarPresentation(
        image = image,
        pictureUrl = null,
        animationKey = durableKey.takeIf { personPicture },
        readOriginalBytes = retainedAvatarBytesReader(asset, accountRef),
        groupAnimation = animation,
    )
}

/** A seed is usable only for the exact selected legacy source, never for an authoritative durable miss. */
internal fun ChatListAvatarSeed.legacyImageFor(
    legacyUrl: String?,
    encryptedKey: String?,
    encryptedImage: ImageBitmap?,
    ownedFallback: String?,
): ImageBitmap? =
    takeIf {
        when (source) {
            ChatListAvatarSource.DURABLE -> false
            ChatListAvatarSource.LEGACY_URL -> key == legacyUrl
            ChatListAvatarSource.ENCRYPTED_GROUP -> legacyUrl == null && key == encryptedKey
            ChatListAvatarSource.FALLBACK_URL -> legacyUrl == null && encryptedImage == null && key == ownedFallback
        }
    }?.image

/** A private picture can be displayed only for its own known, signed-in local account. */
internal fun WhiteNoiseAppState.ownsPrivateAvatarSource(
    source: String,
    accountRef: String?,
): Boolean =
    accounts.any { it.label == accountRef && !it.signedOut } &&
        PrivateContactAvatarLoader.belongsToAccount(source, accountRef)

/**
 * A group's member-pinned (encrypted) picture decodes in its conversation's worker, any other picture a group owns
 * single-use, as its URL's host chooses the bytes, and a person's in the account's public profiles.
 */
internal fun groupAvatarDecodeDomain(
    ownsGroupPicture: Boolean,
    memberPinned: Boolean,
    accountRef: String?,
    groupIdHex: String,
): dev.ipf.whitenoise.android.media.MediaTrustDomain =
    when {
        ownsGroupPicture && memberPinned ->
            dev.ipf.whitenoise.android.media.MediaTrustDomain
                .conversation(accountRef, groupIdHex)
        ownsGroupPicture -> dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
        else ->
            dev.ipf.whitenoise.android.media.MediaTrustDomain
                .publicProfiles(accountRef)
    }
