package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.STORED_AVATAR_KEY_PREFIX
import dev.ipf.whitenoise.android.media.DisplayAvatarGeneration
import dev.ipf.whitenoise.android.media.DisplayAvatarKey
import dev.ipf.whitenoise.android.media.DisplayAvatarPixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private data class RestoredDisplayAvatar(
    val key: DisplayAvatarKey,
    val pixels: DisplayAvatarPixels,
    val fromPointEntry: Boolean,
)

/** Disk I/O cannot extend the first-frame deadline or publish pixels after the waiter leaves. */
internal suspend fun WhiteNoiseAppState.restoreStartupDisplayAvatars(
    keys: List<DisplayAvatarKey>,
    generation: DisplayAvatarGeneration,
    stillCurrent: () -> Boolean = { true },
    readPack: Boolean = true,
    onRestored: (DisplayAvatarKey, Boolean) -> Unit = { _, _ -> },
) {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val missing = keys.filter { AvatarImageLoader.cachedImage(it.storedKey()) == null }
    if (missing.isEmpty()) return
    val ready =
        Channel<RestoredDisplayAvatar>(
            capacity = missing.size,
            onUndeliveredElement = { it.pixels.bitmap.recycle() },
        )
    val reader =
        mutationsScope.launch(Dispatchers.IO) {
            try {
                runCatchingCancellable {
                    val pack =
                        if (readPack) {
                            displayAvatars.readStartupPack(generation.accountRef, missing, generation)
                        } else {
                            emptyMap()
                        }
                    pack.forEach { (key, pixels) ->
                        if (ready.trySend(RestoredDisplayAvatar(key, pixels, fromPointEntry = false)).isFailure) {
                            pixels.bitmap.recycle()
                        }
                    }
                    (missing - pack.keys).forEach { key ->
                        currentCoroutineContext().ensureActive()
                        displayAvatars.read(key, generation)?.let { pixels ->
                            ready.send(RestoredDisplayAvatar(key, pixels, fromPointEntry = true))
                        }
                    }
                }
            } finally {
                ready.close()
            }
        }
    try {
        for ((key, display, fromPointEntry) in ready) {
            if (!stillCurrent()) {
                display.bitmap.recycle()
                break
            }
            if (publishDisplayAvatar(key, display, generation, lifetime)) onRestored(key, fromPointEntry)
        }
    } finally {
        ready.cancel()
        reader.cancel()
    }
}

internal fun WhiteNoiseAppState.publishDisplayAvatar(
    key: DisplayAvatarKey,
    pixels: DisplayAvatarPixels,
    generation: DisplayAvatarGeneration,
    lifetime: Long,
): Boolean {
    var installed = false
    displayAvatars.publishIfCurrent(generation) {
        installed =
            AvatarImageLoader.restoreStoredAvatar(
                key.storedKey(),
                pixels.bitmap.asImageBitmap(),
                animatedSource = null,
                animationClassification = pixels.animated,
                expectedCacheLifetime = lifetime,
            )
    }
    if (!installed) pixels.bitmap.recycle()
    return installed
}

/** Persist pixels already admitted by the normal decoder. Updates do not hold the frame. */
internal fun WhiteNoiseAppState.refreshStartupDisplayAvatars(
    accountRef: String,
    selections: List<FirstFrameAvatarSelection>,
    restored: Set<DisplayAvatarKey>,
    generation: DisplayAvatarGeneration,
    rebuildPack: Boolean,
) {
    val keys = selections.mapNotNull(FirstFrameAvatarSelection::displayKey)
    val decoded =
        (keys - restored).mapNotNull { key ->
            val image = AvatarImageLoader.cachedImage(key.storedKey()) ?: return@mapNotNull null
            val animated = AvatarImageLoader.cachedAnimationClassification(key.storedKey())
            key to DisplayAvatarPixels(image.asAndroidBitmap(), animated)
        }
    if (decoded.isEmpty() && !rebuildPack) return
    mutationsScope.launch(Dispatchers.IO) {
        runCatchingCancellable {
            decoded.forEach { (key, pixels) -> displayAvatars.write(key, pixels, generation) }
            displayAvatars.writeStartupPack(accountRef, keys, generation)
            displayAvatars.prune()
        }
    }
}

internal fun DisplayAvatarKey.storedKey(): String = "$STORED_AVATAR_KEY_PREFIX$accountRef:$reference@$revision"

internal fun durableDisplayAvatarKey(
    accountRef: String?,
    groupIdHex: String,
    asset: AvatarAssetFfi,
): DisplayAvatarKey? {
    val reference = asset.reference
    return if (accountRef == null || reference == null || !asset.isRenderable()) {
        null
    } else {
        DisplayAvatarKey(accountRef, groupIdHex.lowercase(Locale.ROOT), asset.target, reference, asset.contentRevision)
    }
}

internal suspend fun WhiteNoiseAppState.restoreDisplayAvatar(
    key: DisplayAvatarKey,
    generation: DisplayAvatarGeneration,
    lifetime: Long,
) {
    val pixels =
        withContext(Dispatchers.IO) {
            runCatchingCancellable { displayAvatars.read(key, generation) }.getOrNull()
        }
    if (pixels != null) publishDisplayAvatar(key, pixels, generation, lifetime)
}

internal fun WhiteNoiseAppState.persistDisplayAvatar(
    key: DisplayAvatarKey,
    generation: DisplayAvatarGeneration,
    keepExisting: Boolean = false,
    prepared: DisplayAvatarPixels? = null,
) {
    val pixels = prepared ?: cachedDisplayAvatarPixels(key) ?: return
    mutationsScope.launch(Dispatchers.IO) {
        runCatchingCancellable {
            if (!keepExisting || !displayAvatars.hasEntry(key)) {
                displayAvatars.write(key, pixels, generation)
                displayAvatars.prune()
            }
        }
    }
}

private fun cachedDisplayAvatarPixels(key: DisplayAvatarKey): DisplayAvatarPixels? {
    val image = AvatarImageLoader.cachedImage(key.storedKey()) ?: return null
    val animated = AvatarImageLoader.cachedAnimationClassification(key.storedKey())
    return DisplayAvatarPixels(image.asAndroidBitmap(), animated)
}

internal fun ChatListItem.displayAvatarKey(
    accountRef: String?,
    asset: AvatarAssetFfi,
): DisplayAvatarKey? {
    if (!GroupProjector.ownsGroupPicture(this)) return null
    return durableDisplayAvatarKey(accountRef, foldedId, asset)
}

/** After a memory trim, repopulate the current chat-list window without starting native workers. */
internal fun WhiteNoiseAppState.refillForegroundDisplayAvatars() {
    val account = activeAccountRef ?: return
    val keys =
        chatListItemsForAvatarDomains().take(FIRST_FRAME_DURABLE_AVATAR_ROWS).mapNotNull { item ->
            item.selectedAvatarAsset?.let { item.displayAvatarKey(account, it) }
        }
    if (keys.isEmpty()) return
    val generation = displayAvatars.generation(account)
    profileScope.launch {
        withTimeoutOrNull(FIRST_FRAME_DURABLE_AVATAR_BUDGET_MS) {
            if (activeAccountRef == account) restoreStartupDisplayAvatars(keys, generation)
        }
    }
}

/** A current authoritative row can retire the prior revision even when it now presents a placeholder. */
internal fun WhiteNoiseAppState.retireReplacedDisplayAvatars(
    accountRef: String,
    previous: Map<String, AvatarAssetFfi>,
    rows: List<PresentedChatRowFfi>,
) {
    val retired =
        rows.mapNotNull { row ->
            val groupId = row.row.groupIdHex.lowercase(Locale.ROOT)
            val old = previous[groupId] ?: return@mapNotNull null
            val next = row.avatarAsset?.takeIf(AvatarAssetFfi::isRenderable)
            val oldKey = durableDisplayAvatarKey(accountRef, groupId, old)
            oldKey?.takeUnless { next?.let { asset -> durableDisplayAvatarKey(accountRef, groupId, asset) } == it }
        }
    if (retired.isEmpty()) return
    displayAvatars.retireSelections(accountRef, retired)
    mutationsScope.launch(Dispatchers.IO) {
        runCatchingCancellable { displayAvatars.evictRetiredSelections(accountRef) }
    }
}
