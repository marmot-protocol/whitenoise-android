package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.DisplayAvatarGeneration
import dev.ipf.whitenoise.android.media.DisplayAvatarKey
import dev.ipf.whitenoise.android.media.DisplayAvatarPixels
import dev.ipf.whitenoise.android.ui.common.PreparedGroupAvatarPixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private const val ENTERING_AVATARS_BUDGET_MS = 20L

// Leave room in the shared bitmap cache for pictures near the viewport.
private const val IDLE_AVATAR_ROW_LIMIT = 8
private const val IDLE_AVATAR_YIELD_MS = 20L

/** Pages and folder windows can admit disk hits before publishing the new rows. */
internal suspend fun WhiteNoiseAppState.prepareEnteringDisplayAvatars(
    accountRef: String,
    rows: List<PresentedChatRowFfi>,
    alreadyShown: Set<String>,
) {
    val keys =
        rows
            .asSequence()
            .filterNot { it.row.groupIdHex.lowercase(Locale.ROOT) in alreadyShown }
            .mapNotNull { row ->
                val item = chatListItemFromProjection(row.row, row.presentation, row.avatarAsset)
                item.selectedAvatarAsset?.let { item.displayAvatarKey(accountRef, it) }
            }.distinct()
            .take(FIRST_FRAME_DURABLE_AVATAR_ROWS)
            .toList()
    prepareDisplayAvatarWindow(accountRef, keys)
}

/** A folder chip can expose rows that the controller already holds. */
internal suspend fun WhiteNoiseAppState.prepareFolderDisplayAvatars(
    accountRef: String,
    rows: List<ChatListItem>,
) {
    val keys =
        rows.take(FIRST_FRAME_DURABLE_AVATAR_ROWS).mapNotNull { item ->
            item.selectedAvatarAsset?.let { item.displayAvatarKey(accountRef, it) }
        }
    prepareDisplayAvatarWindow(accountRef, keys)
}

private suspend fun WhiteNoiseAppState.prepareDisplayAvatarWindow(
    accountRef: String,
    keys: List<DisplayAvatarKey>,
) {
    withTimeoutOrNull(ENTERING_AVATARS_BUDGET_MS) {
        restoreStartupDisplayAvatars(keys, displayAvatars.generation(accountRef), readPack = false)
    }
}

/** Outside the visible prep window, read exact cached pixels and leave native misses to existing row demand. */
internal suspend fun WhiteNoiseAppState.hydrateChatListDisplayAvatars(
    accountRef: String,
    rows: Iterable<ChatListItem>,
) {
    val generation = displayAvatars.generation(accountRef)
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    for (item in rows) {
        val key = item.selectedAvatarAsset?.let { item.displayAvatarKey(accountRef, it) } ?: continue
        if (AvatarImageLoader.cachedImage(key.storedKey()) == null) restoreDisplayAvatar(key, generation, lifetime)
    }
}

/** Backfill disk entries after the list settles, with one abandonable local decode at a time. */
internal suspend fun WhiteNoiseAppState.backfillChatListDisplayAvatars(
    generation: DisplayAvatarGeneration,
    rows: List<ChatListItem>,
    attempted: MutableSet<DisplayAvatarKey>,
    retained: PreparedGroupAvatarPixels?,
    ownsWindow: () -> Boolean,
) {
    val accountRef = generation.accountRef
    val candidates =
        rows
            .asSequence()
            .mapNotNull { item -> item.selectedAvatarAsset?.let { item.displayAvatarKey(accountRef, it) } }
            .take(IDLE_AVATAR_ROW_LIMIT)
            .toSet()
    attempted.retainAll(candidates)
    for (key in candidates - attempted) {
        val present = withContext(Dispatchers.IO) { displayAvatars.hasEntry(key) }
        if (!ownsWindow() || generation != displayAvatars.generation(accountRef)) return
        val attemptedFill = !present && fillMissingDisplayAvatar(key, generation, retained)
        if (present || attemptedFill) attempted.add(key)
        if (attemptedFill) delay(IDLE_AVATAR_YIELD_MS)
    }
}

private suspend fun WhiteNoiseAppState.fillMissingDisplayAvatar(
    key: DisplayAvatarKey,
    generation: DisplayAvatarGeneration,
    retained: PreparedGroupAvatarPixels?,
): Boolean {
    // The authoritative row can change before Compose receives the new list.
    val item = currentGroupAvatarItem(key.accountRef, key.groupIdHex)
    val selected = item?.selectedAvatarAsset
    if (item == null || selected == null || item.displayAvatarKey(key.accountRef, selected) != key) return false
    val prepared = retained?.displayPixelsFor(selected, key)
    if (prepared != null || AvatarImageLoader.cachedImage(key.storedKey()) != null) {
        persistDisplayAvatar(key, generation, prepared = prepared)
    } else {
        durableAvatar(
            selected,
            key.accountRef,
            acquireMissing = false,
            domain = chatListAvatarDomain(item, key.accountRef),
            abandonable = true,
            displayKey = key,
        )
    }
    return true
}

private fun PreparedGroupAvatarPixels.displayPixelsFor(
    asset: AvatarAssetFfi,
    key: DisplayAvatarKey,
): DisplayAvatarPixels? {
    val image = imageFor(asset, key.storedKey()) ?: return null
    return DisplayAvatarPixels(image.asAndroidBitmap(), animationClassificationFor(asset))
}
