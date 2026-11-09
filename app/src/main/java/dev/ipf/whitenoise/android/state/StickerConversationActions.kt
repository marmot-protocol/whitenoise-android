package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.ui.conversation.stickers.StickerMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STICKER_IMAGE_CACHE_BYTES = 24L * 1024L * 1024L
private const val STICKER_PACK_LIST_LIMIT = 100u

/** The bound account's sticker packs, read and changed through MDK off the main thread. */
internal fun stickerLibraryFor(
    appState: WhiteNoiseAppState,
    accountRef: String?,
): StickerLibrary {
    fun account() = checkNotNull(accountRef)
    return StickerLibrary(
        packs = { installedOnly, search ->
            appState.marmotIo { stickerPacks(account(), installedOnly, search, STICKER_PACK_LIST_LIMIT) }
        },
        syncPacks = { appState.marmotIo { syncStickerPacks(account()) } },
        fetchPack = { input -> appState.marmotIo { fetchStickerPack(account(), input) } },
        installPack = { input -> appState.marmotIo { installStickerPack(account(), input) } },
        uninstallPack = { input -> appState.marmotIo { uninstallStickerPack(account(), input) } },
    )
}

/** Decoded sticker images for one conversation, fetched and verified by MDK and decoded in its trust domain. */
internal fun stickerAssetCacheFor(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    domain: MediaTrustDomain,
    scope: CoroutineScope,
): StickerAssetCache<ImageBitmap> =
    StickerAssetCache(
        scope = scope,
        load = { key ->
            val account = accountRef ?: return@StickerAssetCache null
            val asset = appState.marmotIo { fetchStickerAsset(account, key.ref) }
            val bitmap =
                withContext(Dispatchers.IO) {
                    MediaPipeline.decodeSampledBitmap(asset.bytes, StickerMetrics.DECODE_EDGE_PX, domain = domain)
                }
            bitmap?.let { LoadedSticker(it.asImageBitmap(), asset.sticker) }
        },
        maxBytes = STICKER_IMAGE_CACHE_BYTES,
        sizeOf = { it.asAndroidBitmap().byteCount },
    )

/** Sends [sticker] into this conversation through MDK; cancellation propagates, failures are shown. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConversationController.sendSticker(sticker: StickerFfi): Boolean {
    val account = boundAccountRef?.takeIf { canSendMessages } ?: return false
    return try {
        appState.withGroupCommitLock(account, group.groupIdHex) {
            check(canSendMessages)
            appState.marmotIo { sendSticker(account, group.groupIdHex, StickerKey.from(sticker).ref) }
        }
        true
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        appState.presentFailure(
            R.string.sticker_send_failed,
            "STICKER_SEND",
            throwable,
            stickerFailureDetail(throwable),
        )
        false
    }
}

/** The user-facing reason a sticker or pack operation failed. */
internal fun stickerFailureDetail(throwable: Throwable): AppText =
    AppText.Resource(
        when (throwable) {
            is MarmotKitException.InvalidSticker -> R.string.sticker_error_invalid
            is MarmotKitException.StickerNotFound -> R.string.sticker_error_not_found
            is MarmotKitException.StickerNetwork -> R.string.sticker_error_network
            is MarmotKitException.StickerImport,
            is MarmotKitException.StickerImportUnsupported,
            -> R.string.sticker_error_import
            else -> R.string.error_try_again
        },
    )
