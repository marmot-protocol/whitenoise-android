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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

// About 100 decoded stickers, so scrolling a large pack doesn't evict and re-download what was just shown.
// Memory pressure still trims it (StickerAssetCache.trimMemory).
private const val STICKER_IMAGE_CACHE_BYTES = 64L * 1024L * 1024L
private const val STICKER_PACK_LIST_LIMIT = 100u

/**
 * One account's sticker packs and decoded sticker images, shared by all of its conversations so a chat opens with
 * the installed list already read and its stickers already decoded. Main-confined; [close] cancels its downloads.
 */
internal class AccountStickers(
    val account: String?,
    appState: WhiteNoiseAppState,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The account's packs, read and changed through MDK off the main thread. */
    val library: StickerLibrary = stickerLibraryFor(appState, account)

    /** Sticker images fetched and verified by MDK, decoded with the account's other public images. */
    val assets: StickerAssetCache<ImageBitmap> = stickerAssetCacheFor(appState, account, scope)

    /** Cancels this account's sticker downloads once the account is removed. */
    fun close() = scope.cancel()
}

private fun stickerLibraryFor(
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

/**
 * Sticker bytes come from a URL the pack's public author chose, and the pack is a public event anyone can fetch, so
 * they decode in the account's [MediaTrustDomain.PublicProfiles] worker, never a conversation's.
 */
private fun stickerAssetCacheFor(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    scope: CoroutineScope,
): StickerAssetCache<ImageBitmap> {
    val domain = MediaTrustDomain.publicProfiles(accountRef)
    return StickerAssetCache(
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
}

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
