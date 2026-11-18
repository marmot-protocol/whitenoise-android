package dev.ipf.whitenoise.android.core

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.whitenoise.android.media.MediaTrustDomain

/** The two decode policies the shared profile-image loader serves (#2762). */
internal enum class ProfileImageVariant {
    AVATAR,
    BANNER,
}

/**
 * One profile-image fetch: where the bytes come from, and how large the decode may be.
 *
 * [cacheKey] carries the variant and target bucket, so a cached avatar cannot satisfy a banner
 * request and two banner requests only share a fetch when their decodes would be identical.
 */
internal data class ProfileImageRequest(
    val url: String,
    val variant: ProfileImageVariant,
    val maxDimension: Int,
    val maxBytes: Int,
    /** The worker domain a decode for this request uses; never part of the cache key. */
    val domain: MediaTrustDomain = MediaTrustDomain.Unique,
) {
    val cacheKey: String get() = profileImageCacheKey(url, variant, maxDimension)
}

/** Marks a banner cache key, so the partition an entry belongs to can be read back off its key. */
private const val BANNER_CACHE_KEY_PREFIX = "banner:"

/** Avatar entries keep the bare URL, so durable seeds and every existing caller are unchanged. */
internal fun profileImageCacheKey(
    url: String,
    variant: ProfileImageVariant,
    maxDimension: Int,
): String =
    when (variant) {
        ProfileImageVariant.AVATAR -> url
        ProfileImageVariant.BANNER -> "$BANNER_CACHE_KEY_PREFIX$maxDimension $url"
    }

/** The variant [cacheKey] was built for; an avatar key is the bare profile-image URL. */
internal fun profileImageVariantOf(cacheKey: String): ProfileImageVariant =
    if (cacheKey.startsWith(BANNER_CACHE_KEY_PREFIX)) {
        ProfileImageVariant.BANNER
    } else {
        ProfileImageVariant.AVATAR
    }

/**
 * The loader's in-memory cache, split into one byte budget per decode variant.
 *
 * Separate keys stop a banner request from being *answered* by an avatar-sized bitmap, but on one
 * shared LRU they would not stop it from *evicting* one: a banner entry can be worth eight avatars,
 * so a few of them walking through the cache would push the avatar working set out by ordinary
 * recency, and #2762 requires the avatar ceiling to stay exactly where it was. Two budgets make
 * that structural rather than hopeful — a banner entry can only ever evict banner entries. Callers
 * still see one cache and keep passing the key they already built.
 */
internal class PartitionedProfileImageCache(
    avatarBytes: Int,
    bannerBytes: Int,
    private val onPublished: (String) -> Unit = {},
) {
    private var trimmingMemory = false
    private val avatars =
        byteBudgetedCache(avatarBytes, AvatarCacheKind.PROFILE) {
            if (!trimmingMemory) AvatarCacheChanges.published()
        }
    private val banners = byteBudgetedCache(bannerBytes, null) {}

    /** The image held for [cacheKey], looked up only in the partition its variant owns. */
    fun get(cacheKey: String): ImageBitmap? =
        partitionFor(cacheKey).get(cacheKey)?.image.also { image ->
            if (profileImageVariantOf(cacheKey) == ProfileImageVariant.AVATAR) {
                AvatarCacheDiagnostics.lookup(AvatarCacheKind.PROFILE, image != null)
            }
        }

    /** Publishes [image] under [cacheKey], charging it to its own variant's budget. */
    fun put(
        cacheKey: String,
        image: ImageBitmap,
    ) {
        partitionFor(cacheKey).put(cacheKey, CachedProfileFrame(image))
        if (profileImageVariantOf(cacheKey) == ProfileImageVariant.AVATAR) AvatarCacheChanges.published()
        onPublished(cacheKey)
    }

    /** Null means absent or unclassified pixels; a known static frame needs no second original-byte read. */
    fun isAnimated(cacheKey: String): Boolean? = partitionFor(cacheKey).get(cacheKey)?.animated

    /** Called under the loader lock together with publishing the frame and its encoded-source classification. */
    fun classifyAnimation(
        cacheKey: String,
        animated: Boolean?,
    ) {
        partitionFor(cacheKey).get(cacheKey)?.animated = animated
    }

    private class CachedProfileFrame(
        val image: ImageBitmap,
        var animated: Boolean? = null,
    )

    /** Trims each variant independently; shared ImageBitmaps are never recycled. */
    fun trimMemory(trim: dev.ipf.whitenoise.android.media.PresentationMemoryTrim) {
        trimmingMemory = true
        try {
            avatars.trimToSize(trim.targetBytes(avatars.maxSize().toLong()).toInt())
            banners.trimToSize(trim.targetBytes(banners.maxSize().toLong()).toInt())
        } finally {
            trimmingMemory = false
        }
    }

    /** Drops both variants for account teardown without recording a capacity eviction. */
    fun evictAll() {
        // LruCache.evictAll() marks each callback as evicted, just like capacity trimming.
        avatars.snapshot().keys.forEach(avatars::remove)
        banners.evictAll()
    }

    /**
     * Stored MDK avatar keys hold account-scoped plaintext; public URL keys retain their warm pixels. [retired] picks
     * which stored keys go.
     */
    fun evictStoredAvatars(retired: (String) -> Boolean) {
        avatars
            .snapshot()
            .keys
            .filter(retired)
            .forEach(avatars::remove)
    }

    /** Bytes currently charged to [variant]'s budget, so each ceiling can be asserted directly. */
    fun byteSize(variant: ProfileImageVariant): Int = partition(variant).size()

    private fun partitionFor(cacheKey: String) = partition(profileImageVariantOf(cacheKey))

    private fun partition(variant: ProfileImageVariant) =
        when (variant) {
            ProfileImageVariant.AVATAR -> avatars
            ProfileImageVariant.BANNER -> banners
        }

    private companion object {
        /** An LRU bounded by the decoded bytes it holds rather than by entry count. */
        fun byteBudgetedCache(
            maxBytes: Int,
            kind: AvatarCacheKind?,
            publishChange: () -> Unit,
        ) = object : LruCache<String, CachedProfileFrame>(maxBytes) {
            override fun sizeOf(
                key: String,
                value: CachedProfileFrame,
            ): Int =
                value.image
                    .asAndroidBitmap()
                    .byteCount
                    .coerceAtLeast(1)

            /**
             * Records bounded cache eviction diagnostics and notifies avatar observers when their
             * cached pixels change.
             */
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: CachedProfileFrame,
                newValue: CachedProfileFrame?,
            ) {
                if (evicted && kind != null) AvatarCacheDiagnostics.evicted(kind)
                publishChange()
            }
        }
    }
}
