package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Maximum local read for an evicted animated source, matching the existing encoded avatar budget. */
private const val GROUP_ANIMATION_LOCAL_READ_MAX_BYTES: ULong = 2_097_152uL

/** A durable group selection keeps viewer bytes and thumbnail admission under the same pinned owner. */
@Suppress("LongParameterList") // Keeps exact pixel metadata attached to the existing selected-avatar presentation.
internal fun WhiteNoiseAppState.durableGroupAvatarPresentation(
    group: AppGroupRecordFfi,
    asset: AvatarAssetFfi,
    accountRef: String?,
    image: ImageBitmap?,
    personPicture: Boolean,
    animationClassification: Boolean? = null,
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
    // Encoded sources have a smaller cache than first-frame pixels. Recover an evicted durable
    // source only from MDK's existing local store, inside the visible thumbnail's source budget.
    // Known static first frames never reread bytes; classification shares their existing bitmap LRU entry.
    val animation =
        if (ownsGroupPicture && durableKey != null && owner != null) {
            val readLocal = retainedAvatarBytesReader(asset, accountRef, GROUP_ANIMATION_LOCAL_READ_MAX_BYTES)
            GroupAvatarAnimation("$runtime|$lifetime|${group.groupIdHex}|$durableKey") {
                if (ownsSource()) {
                    val classification =
                        AvatarImageLoader.cachedAnimationClassification(durableKey)
                            ?: animationClassification
                    val bytes =
                        AvatarImageLoader.peekAnimatedSource(durableKey)
                            ?: readLocal
                                ?.takeIf { classification != false }
                                ?.invoke()
                    bytes?.takeIf { ownsSource() }
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

/** [groupAvatarDecodeDomain] for [group]'s durable selection, member-pinned only when it is the encrypted image. */
internal fun durableGroupPictureDomain(
    group: AppGroupRecordFfi,
    ownsGroupPicture: Boolean,
    accountRef: String?,
): dev.ipf.whitenoise.android.media.MediaTrustDomain =
    groupAvatarDecodeDomain(
        ownsGroupPicture,
        dev.ipf.whitenoise.android.core.GroupProjector
            .pictureIsMemberPinned(group),
        accountRef,
        group.groupIdHex,
    )

/**
 * The worker domain of a legacy presentation's URL: a group's URL picture has host-chosen bytes, so it decodes
 * single-use, and otherwise the URL is a person's published fallback.
 */
internal fun legacyGroupPictureDomain(
    legacyUrl: String?,
    accountRef: String?,
): dev.ipf.whitenoise.android.media.MediaTrustDomain =
    if (legacyUrl != null) {
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
    } else {
        dev.ipf.whitenoise.android.media.MediaTrustDomain
            .publicProfiles(accountRef)
    }

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

/** Classification travels with the exact displayed pixels through decoded-cache capacity eviction. */
@Composable
internal fun rememberGroupAvatarClassification(
    appState: WhiteNoiseAppState,
    asset: AvatarAssetFfi,
    accountRef: String?,
    image: ImageBitmap?,
    seed: ChatListAvatarSeed?,
): Boolean? {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val key = accountRef?.let { asset.takeIf { it.isRenderable() }?.cacheKey(it) }
    val pins =
        LocalPreparedGroupAvatarPixels.current?.takeIf {
            it.accountRef == accountRef && it.runtimeGeneration == appState.runtimeGeneration && it.lifetime == lifetime
        }
    return remember(appState, appState.runtimeGeneration, accountRef, lifetime, key, image) {
        key?.let(AvatarImageLoader::cachedAnimationClassification)
            ?: pins?.animationClassificationFor(asset)
            ?: seed?.takeIf { image != null && it.image === image }?.animationClassification
    }
}

/**
 * Keeps selected native pixels and their exact retained classification under the same presentation owner. The
 * pixels decode in [domain], the selection's [durableGroupPictureDomain].
 */
@Composable
@Suppress("LongParameterList") // Mirrors the existing selected durable-avatar inputs.
internal fun rememberSelectedDurableGroupAvatar(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
    asset: AvatarAssetFfi,
    accountRef: String?,
    seed: ChatListAvatarSeed?,
    personPicture: Boolean,
    domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
): GroupAvatarPresentation {
    val key = accountRef?.let { asset.takeIf { it.isRenderable() }?.cacheKey(it) }
    val ownedSeed = seed?.takeIf { it.source == ChatListAvatarSource.DURABLE && it.key == key }
    val image = rememberDurableAvatar(appState, asset, accountRef, domain) ?: ownedSeed?.image
    val classification = rememberGroupAvatarClassification(appState, asset, accountRef, image, ownedSeed)
    return appState.durableGroupAvatarPresentation(group, asset, accountRef, image, personPicture, classification)
}
