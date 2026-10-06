package dev.ipf.whitenoise.android.core

import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.whitenoise.android.state.ContactPictureReference
import dev.ipf.whitenoise.android.state.ContactPictureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Display-only image handles. They contain opaque record identifiers, never private filesystem paths.
 * They are intercepted before the public profile fetch boundary and share its bounded bitmap cache.
 */
@Suppress("ReturnCount") // Untrusted handles and expired owners fail closed before decoding.
internal object PrivateContactAvatarLoader {
    private const val PREFIX = "private-contact-avatar:"
    private val HASH = Regex("[a-f0-9]{64}")
    private val FILE = Regex("[a-f0-9-]{36}\\.png")

    @Volatile private var store: ContactPictureStore? = null

    @Volatile private var activeAccount: (() -> String?)? = null

    /** Installs only the process application's private store, never a screen or activity reference. */
    fun attach(
        value: ContactPictureStore,
        account: (() -> String?)? = null,
    ) {
        store = value
        activeAccount = account
    }

    /** A protocol sanitizer deliberately rejects this non-network display handle. */
    fun isPrivate(source: String?): Boolean = source?.startsWith(PREFIX) == true

    /** The original published fallback stays separate and is fetched under MDK policy. */
    fun source(
        reference: ContactPictureReference,
        publishedUrl: String?,
    ): String {
        val fallback = ProfileSanitizer.protocolImageUrl(publishedUrl).orEmpty()
        val encoded = Base64.encodeToString(fallback.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return "$PREFIX${reference.owner}:${reference.contact}:${reference.fileName}:$encoded"
    }

    /** A viewer must immediately drop a handle when its originating account is no longer displayed. */
    fun belongsToActiveAccount(source: String): Boolean {
        val reference = parse(source)?.first ?: return false
        val account = activeAccount ?: return true
        return store?.belongsToAccount(reference, account()) == true
    }

    /** First-frame cache-only read; decoding and filesystem access remain on the loader's IO lane. */
    fun peek(source: String): ImageBitmap? {
        val selection = parse(source) ?: return null
        val current = currentReference(selection.first)
        return current?.cacheKey?.let(AvatarImageLoader::cachedImage) ?: selection.second?.let(AvatarImageLoader::peek)
    }

    /** A missing or corrupt local file falls back to the current published URL without erasing its record. */
    suspend fun load(source: String): ImageBitmap? {
        val selection = parse(source) ?: return null
        val lifetime = AvatarImageLoader.currentCacheLifetime()
        val current = currentReference(selection.first)
        val image =
            current?.let { ref ->
                AvatarImageLoader.loadStored(ref.cacheKey, lifetime) {
                    withContext(Dispatchers.IO) { store?.read(ref) }
                }
            }
        val expired = AvatarImageLoader.currentCacheLifetime() != lifetime
        if (expired || current != currentReference(selection.first)) return null
        return image ?: selection.second?.let { AvatarImageLoader.load(it) }
    }

    /** Revalidates display ownership before resolving a newer immutable selection for a captured handle. */
    private fun currentReference(reference: ContactPictureReference): ContactPictureReference? {
        val selectedStore = store ?: return null
        val account = activeAccount
        if (account != null && !selectedStore.belongsToAccount(reference, account())) return null
        return selectedStore.current(reference)
    }

    /** Accepts only the private-handle namespace and separates its opaque record from a public fallback. */
    private fun parse(source: String): Pair<ContactPictureReference, String?>? {
        if (!isPrivate(source) || source.length > MAX_SOURCE_LENGTH) return null
        val parts = source.removePrefix(PREFIX).split(':', limit = HANDLE_PARTS)
        if (parts.size != HANDLE_PARTS) return null
        if (!HASH.matches(parts[0]) || !HASH.matches(parts[1]) || !FILE.matches(parts[2])) return null
        val fallback =
            runCatching { String(Base64.decode(parts[3], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8) }
                .getOrNull()
                ?.let(ProfileSanitizer::protocolImageUrl)
        return ContactPictureReference(parts[0], parts[1], parts[2]) to fallback
    }

    private const val HANDLE_PARTS = 4
    private const val MAX_SOURCE_LENGTH = 16_384
}
