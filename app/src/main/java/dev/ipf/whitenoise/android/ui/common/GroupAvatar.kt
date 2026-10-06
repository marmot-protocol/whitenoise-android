package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.state.ChatListAvatarSeed
import dev.ipf.whitenoise.android.state.ChatListAvatarSource
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.adoptableSelectedAvatarAsset
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.currentGroupAvatarItem
import dev.ipf.whitenoise.android.state.isPeerSourced
import dev.ipf.whitenoise.android.state.isRenderable
import dev.ipf.whitenoise.android.state.retainedAvatarBytesReader

/**
 * All group surfaces consume the selected MDK asset; legacy acquisition is only a compatibility path.
 * [durableAvatarIsPersonPicture] marks a selected asset that is a member's profile picture, which alone
 * may animate under the shared profile-avatar policy.
 */
@Composable
// Ordered private, durable and legacy presentation fallbacks.
@Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod")
internal fun rememberGroupAvatarPresentation(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
    durableAvatar: AvatarAssetFfi?,
    accountRef: String? = appState.activeAccountRef,
    fallbackPictureUrl: String? = null,
    firstFrameAvatar: ChatListAvatarSeed? = null,
    durableAvatarIsPersonPicture: Boolean = false,
): GroupAvatarPresentation {
    val ownedFallback =
        fallbackPictureUrl?.takeUnless { source ->
            PrivateContactAvatarLoader.isPrivate(source) && !appState.ownsPrivateAvatarSource(source, accountRef)
        }
    val groupOwnsPicture =
        !durableAvatarIsPersonPicture &&
            (durableAvatar != null || !group.avatarUrl.isNullOrBlank() || !group.imageHashHex.isNullOrBlank())
    val privateSource =
        ownedFallback
            ?.takeUnless { groupOwnsPicture }
            ?.takeIf(dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader::isPrivate)
    val privateImage by key(appState, accountRef, privateSource, AvatarImageLoader.currentCacheLifetime()) {
        rememberRecoverableAvatar(
            initialImage = privateSource?.let { PrivateContactAvatarLoader.peek(it, accountRef) },
            enabled = privateSource != null,
        ) {
            PrivateContactAvatarLoader.load(checkNotNull(privateSource), accountRef)
        }
    }
    // A missing/corrupt local image may still use this account's native public-avatar selection.
    if (privateSource != null && privateImage != null) return GroupAvatarPresentation(privateImage, privateSource)
    val ownedSeed = firstFrameAvatar?.takeIf { it.matchesAvatarPresentationOwner(accountRef) }
    val durableImage = rememberDurableAvatar(appState, durableAvatar, accountRef)
    if (durableAvatar != null) {
        val durableKey = accountRef?.let { durableAvatar.takeIf { it.isRenderable() }?.cacheKey(it) }
        val seededImage = ownedSeed?.durableImageFor(durableKey)
        // Missing/invalidated assets must not revive a legacy bitmap or launch a second acquisition.
        return GroupAvatarPresentation(
            image = durableImage ?: seededImage,
            pictureUrl = null,
            animationKey = durableKey.takeIf { durableAvatarIsPersonPicture },
        )
    }
    val legacyUrl = ProfileSanitizer.protocolImageUrl(group.avatarUrl)
    val encryptedKey = encryptedGroupAvatarCacheKey(accountRef, group)
    val encryptedImage = rememberEncryptedGroupAvatar(appState, group, accountRef)
    val remoteImage =
        key(appState, appState.runtimeGeneration, accountRef, legacyUrl) {
            val image by rememberRecoverableAvatar(
                initialImage = AvatarImageLoader.peek(legacyUrl),
                enabled = legacyUrl != null,
            ) { AvatarImageLoader.load(checkNotNull(legacyUrl)) }
            image
        }
    val seededImage =
        ownedSeed
            ?.takeIf {
                when (it.source) {
                    ChatListAvatarSource.DURABLE -> false
                    ChatListAvatarSource.LEGACY_URL -> it.key == legacyUrl
                    ChatListAvatarSource.ENCRYPTED_GROUP -> legacyUrl == null && it.key == encryptedKey
                    ChatListAvatarSource.FALLBACK_URL ->
                        legacyUrl == null && encryptedImage == null && it.key == ownedFallback
                }
            }?.image
    return GroupAvatarPresentation(
        image = seededImage ?: remoteImage ?: encryptedImage,
        pictureUrl = legacyUrl ?: ownedFallback?.takeIf { encryptedImage == null },
    )
}

/** A private picture can be displayed only for its own known, signed-in local account. */
private fun WhiteNoiseAppState.ownsPrivateAvatarSource(source: String, accountRef: String?): Boolean =
    accounts.any { it.label == accountRef && !it.signedOut } &&
        PrivateContactAvatarLoader.belongsToAccount(source, accountRef)

/** The public-URL compatibility seed may be unscoped; retained private seeds may not be retired. */
private fun ChatListAvatarSeed.matchesAvatarPresentationOwner(accountRef: String?): Boolean {
    val currentOwner = this.accountRef == null || this.accountRef == accountRef
    val currentLifetime = cacheLifetime == null || cacheLifetime == AvatarImageLoader.currentCacheLifetime()
    return currentOwner && currentLifetime
}

/** A selected native identity may only reuse the exact durable presentation seed. */
private fun ChatListAvatarSeed.durableImageFor(assetKey: String?): ImageBitmap? {
    val selected = source == ChatListAvatarSource.DURABLE && key == assetKey
    return image.takeIf { selected }
}

/** The row's selected asset already includes MDK's group/peer selection and membership checks. */
@Composable
internal fun rememberChatListGroupAvatar(
    appState: WhiteNoiseAppState,
    item: ChatListItem,
    accountRef: String? = appState.activeAccountRef,
    fallbackPictureUrl: String? = null,
): GroupAvatarPresentation =
    rememberGroupAvatarPresentation(
        appState = appState,
        group = item.group,
        durableAvatar = item.selectedAvatarAsset,
        accountRef = accountRef,
        fallbackPictureUrl =
            dev.ipf.whitenoise.android.core.GroupProjector
                .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
                ?.let { appState.contactAvatarSource(it, accountRef) }
                ?.takeIf(dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader::isPrivate)
                ?: fallbackPictureUrl,
        firstFrameAvatar = item.firstFrameAvatar,
        durableAvatarIsPersonPicture = item.selectedPresentation?.avatarSource?.isPeerSourced() == true,
    )

/** Details, editing and full-picture presentation share the conversation's account and current asset. */
@Composable
internal fun rememberConversationGroupAvatar(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): GroupAvatarPresentation {
    val item = appState.currentGroupAvatarItem(controller.boundAccountRef, controller.group.groupIdHex)
    val asset = conversationGroupAvatarAsset(controller, item)
    val selected = controller.window.header?.selected ?: item?.selectedPresentation
    val peerPicture = selected?.avatarSource?.isPeerSourced() == true
    val lendsPeer =
        dev.ipf.whitenoise.android.core.GroupProjector
            .lendsPeerAvatar(controller.group, controller.memberCount)
    val peer = (selected?.peerId?.takeIf { peerPicture } ?: controller.avatarAccount).takeIf { lendsPeer }
    val privatePicture =
        peer
            ?.let { appState.contactAvatarSource(it, controller.boundAccountRef) }
            ?.takeIf(dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader::isPrivate)
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val originalBytes =
        remember(appState, controller, asset, controller.boundAccountRef, appState.runtimeGeneration, lifetime) {
            appState.retainedAvatarBytesReader(asset, controller.boundAccountRef)
        }
    val selectedByNative = controller.window.header != null || item?.selectedAvatarAsset != null
    if (privatePicture == null && asset == null && selectedByNative) {
        // An explicit placeholder, removal or identity mismatch must not resurrect older group bytes.
        return GroupAvatarPresentation(null, null)
    }
    val matchingItem =
        item?.takeIf {
            it.group.avatarUrl == controller.group.avatarUrl &&
                it.group.imageHashHex == controller.group.imageHashHex
        }
    val presentation =
        rememberGroupAvatarPresentation(
            appState,
            controller.group,
            asset,
            controller.boundAccountRef,
            privatePicture ?: controller.avatarUrl,
            matchingItem?.firstFrameAvatar,
            durableAvatarIsPersonPicture = peerPicture,
        )
    return presentation.copy(
        readOriginalBytes =
            originalBytes.takeUnless {
                dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
                    .isPrivate(presentation.pictureUrl)
            },
    )
}

/** Reads current MDK selection without initiating any acquisition or borrowing another account's row. */
internal fun conversationGroupAvatarAsset(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): AvatarAssetFfi? =
    conversationGroupAvatarAsset(
        controller,
        appState.currentGroupAvatarItem(controller.boundAccountRef, controller.group.groupIdHex),
    )

private fun conversationGroupAvatarAsset(
    controller: ConversationController,
    currentItem: ChatListItem?,
): AvatarAssetFfi? {
    val header = controller.window.header
    if (header != null) {
        return adoptableSelectedAvatarAsset(
            header.avatarAsset,
            header.selected.avatarSource,
            controller.group,
            controller.presentedMemberCount,
            header.selected.avatar,
        )
    }
    return currentItem
        ?.takeIf {
            it.group.avatarUrl == controller.group.avatarUrl &&
                it.group.imageHashHex == controller.group.imageHashHex
        }?.selectedAvatarAsset
}

/** Resolves encrypted pixels only within the current owner, runtime, and authoritative image identity. */
@Composable
internal fun rememberEncryptedGroupAvatar(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
    accountRef: String? = appState.activeAccountRef,
): ImageBitmap? {
    val cacheKey = encryptedGroupAvatarCacheKey(accountRef, group)
    val image by key(appState, appState.runtimeGeneration, cacheKey) {
        rememberRecoverableAvatar(
            initialImage = GroupAvatarImageLoader.peek(cacheKey),
            enabled = cacheKey != null && accountRef != null,
        ) {
            GroupAvatarImageLoader.load(checkNotNull(cacheKey)) {
                appState.marmotIo {
                    downloadGroupBlossomImage(checkNotNull(accountRef), group.groupIdHex)
                }
            }
        }
    }
    return image
}

/**
 * Renders the URL component first, then the encrypted MDK image, then an
 * optional DM-peer profile URL, matching AppGroupRecordFfi precedence.
 */
@Composable
// Compatibility and selected-asset presentations share the existing avatar entry point.
@Suppress("FunctionNaming", "LongParameterList")
internal fun GroupAvatar(
    appState: WhiteNoiseAppState,
    group: AppGroupRecordFfi,
    title: String,
    seed: String,
    size: Dp,
    fallbackPictureUrl: String? = null,
    firstFrameAvatar: ChatListAvatarSeed? = null,
    // MarmotKit 0.10.1 keeps avatars durably; its bytes are preferred over fetching the URL, so a row
    // keeps its picture offline and costs no request. Null falls back to the URL path unchanged.
    durableAvatar: AvatarAssetFfi? = null,
    accountRef: String? = appState.activeAccountRef,
    // True only when [durableAvatar] is a member's profile picture (a DM peer), never a group image.
    durableAvatarIsPersonPicture: Boolean = false,
) {
    val presentation =
        rememberGroupAvatarPresentation(
            appState,
            group,
            durableAvatar,
            accountRef,
            fallbackPictureUrl,
            firstFrameAvatar,
            durableAvatarIsPersonPicture,
        )
    Avatar(
        title = title,
        seed = seed,
        size = size,
        pictureUrl = presentation.pictureUrl?.takeIf { presentation.image == null },
        picture = presentation.image,
        animationKey = presentation.animationKey,
    )
}
