package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.runtime.saveable.Saver
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.share.ForeignContentSource
import dev.ipf.whitenoise.android.state.ConversationController
import java.io.File

// Persist a multi-pick selection across rotation / process death. Empty list
// encodes "no preview shown" so the parent re-render skips the sheet on
// restore. Uses '\n' as the separator — content URIs don't contain newlines.
internal val UriListSaver: Saver<List<android.net.Uri>, String> =
    Saver(
        save = { encodeUriListTokens(it.map { uri -> uri.toString() }) },
        restore = { s -> decodeUriListTokens(s).map(android.net.Uri::parse) },
    )

/**
 * Pure string codec backing [UriListSaver], split out from the [android.net.Uri]
 * conversion so the separator and empty-list contract is unit-testable on the
 * JVM (the Android `Uri` stubs are non-functional in local unit tests). Joins
 * tokens with '\n'; an empty list encodes to "".
 */
internal fun encodeUriListTokens(tokens: List<String>): String = tokens.joinToString("\n")

/**
 * Inverse of [encodeUriListTokens]. An empty input decodes to an empty list
 * (the "no preview shown" sentinel); blank tokens are dropped so a trailing or
 * doubled separator can't yield empty URI strings.
 */
internal fun decodeUriListTokens(encoded: String): List<String> =
    if (encoded.isEmpty()) {
        emptyList()
    } else {
        encoded.split('\n').filter { it.isNotEmpty() }
    }

/** Returns and promotes a still-present plaintext cache entry, or null after eviction. */
internal fun validatedAttachmentCacheFile(file: java.io.File?): java.io.File? =
    file
        ?.takeIf { it.isFile && it.length() > 0L }
        ?.also(AttachmentPlaintextCache::touch)

/** Create a cache file for a camera photo. Returns null if it can't be made. */
internal fun createImageCaptureFile(context: Context): File? = createCaptureFile(context, ".jpg")

/** Create a cache file for a camera video, whose `.mp4` name gives it a video type, or null if it can't be made. */
internal fun createVideoCaptureFile(context: Context): File? = createCaptureFile(context, ".mp4")

/** Create an empty file in the camera cache directory the file provider shares, or null if it can't be made. */
private fun createCaptureFile(
    context: android.content.Context,
    suffix: String,
): java.io.File? =
    try {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        java.io.File.createTempFile("capture_", suffix, dir)
    } catch (_: Throwable) {
        null
    }

/**
 * Copy a receive-content image into app-owned cache while the platform's
 * URI read grant is still active.
 *
 * The provider may revoke access once the drop's grant ends. The composer
 * staging shelf previews and sends later, so it must hold a local FileProvider
 * URI rather than the provider's raw URI. This reads from the provider, so call
 * it off the main thread; [resolvedMime] is the type already resolved for [uri].
 *
 * The keyboard or clipboard app chooses [uri], so it passes the same foreign-source gate as share intents: a
 * `file:` URI or one of our own providers is refused before any read.
 */
internal fun materializeReceiveContentImageUri(
    context: android.content.Context,
    uri: android.net.Uri,
    resolvedMime: String,
    maxBytes: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES,
    isAppOwnedProvider: (android.net.Uri) -> Boolean = { ForeignContentSource.isAppOwned(context, it) },
): android.net.Uri? =
    runCatching {
        if (!ForeignContentSource.isForeign(uri, isAppOwnedProvider)) return@runCatching null
        val resolver = context.contentResolver
        val suffix = receiveContentImageCacheSuffix(resolvedMime)
        val dir = java.io.File(context.cacheDir, MediaCacheDirs.COMPOSER_PASTE).apply { mkdirs() }
        val file = java.io.File.createTempFile("paste_", suffix, dir)
        val copied =
            resolver.openInputStream(uri)?.use { input ->
                MediaPipeline.copyStreamToFileWithinCap(input, file, maxBytes)
            } == true
        if (!copied || file.length() <= 0L) {
            runCatching { file.delete() }
            null
        } else {
            fileProviderUri(context, file)
        }
    }.getOrNull()

internal fun receiveContentImageCacheSuffix(resolvedMime: String): String {
    val extension =
        when {
            resolvedMime.equals("image/jpeg", ignoreCase = true) -> "jpg"
            resolvedMime.startsWith("image/", ignoreCase = true) ->
                android.webkit.MimeTypeMap
                    .getSingleton()
                    .getExtensionFromMimeType(resolvedMime.lowercase())
            else -> null
        }?.takeIf { candidate ->
            candidate.length in 1..8 && candidate.all { it.isLetterOrDigit() }
        } ?: "img"
    return ".$extension"
}

internal fun fileProviderUri(
    context: android.content.Context,
    file: java.io.File,
): android.net.Uri =
    androidx.core.content.FileProvider
        .getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * Best-effort wipe of decrypted camera-capture temp files from cache.
 *
 * Intentionally does NOT touch `shared_media`. Those entries back live
 * FileProvider URIs the system may still be reading after the user backs
 * out of a chat (an external PDF reader holding the granted URI, the
 * system share-sheet target, etc.). Yanking the file out from under
 * those readers caused the "opened PDF goes blank when I leave the chat"
 * class of bug — the [sweepStaleSharedMedia] janitor cleans those on a
 * stale-age basis at app start instead.
 */
internal fun clearMediaTempFiles(context: android.content.Context) {
    runCatching { AttachmentPlaintextCache.clearUnheldFiles(java.io.File(context.cacheDir, "camera")) }
    runCatching { java.io.File(context.cacheDir, MediaCacheDirs.COMPOSER_PASTE).deleteRecursively() }
}

/**
 * Delete `shared_media` files older than [maxAgeMillis]. Called once at
 * app start so transient FileProvider temps for opened/shared
 * attachments don't accumulate across sessions, without racing the
 * external readers that may still be using them in the current session.
 */
internal fun sweepStaleSharedMedia(
    context: android.content.Context,
    maxAgeMillis: Long,
) {
    runCatching {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        // Same age-based reaper covers the decrypted voice cache too —
        // those bytes are plaintext E2EE-decrypted audio and shouldn't
        // linger past the last MediaPlayer that opened them.
        listOf(
            MediaCacheDirs.SHARED,
            ConversationTranscriptExport.CacheDirName,
            MediaCacheDirs.VOICE,
            MediaCacheDirs.VIDEO,
            MediaCacheDirs.COMPOSER_PASTE,
        ).forEach { name ->
            val dir = java.io.File(context.cacheDir, name)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.lastModified() < cutoff) {
                    runCatching { entry.delete() }
                }
            }
        }
        AttachmentPlaintextCache.trimKnownDirectories(context.cacheDir)
    }
    // Large-send snapshots belong to one process's retry state; an earlier process's are orphans at any age.
    // Run on its own so a failure above cannot leave hundreds of MiB of plaintext behind.
    runCatching { sweepOrphanedUploadSources(context.cacheDir) }
    runCatching { sweepOrphanedDocumentReaderFiles(context.cacheDir) }
}

/** Files in `shared_media` older than this are considered safe to delete —
 *  any external reader has had ample time to finish loading the bytes. */
internal const val SHARED_MEDIA_MAX_AGE_MS: Long = AttachmentPlaintextCache.SHARED_READER_WINDOW_MILLIS

/** Read the user-visible filename a content Uri exposes via OpenableColumns,
 *  falling back to the Uri's path segment. Null when neither is available.
 *
 *  Guarded against a revoked grant: a Photo Picker / SAF Uri staged before
 *  process death (issue #531) comes back as a ghost whose session-scoped read
 *  permission is gone, so `query()` throws `SecurityException` (or the backing
 *  provider may be dead — `IllegalArgumentException` / `NullPointerException`).
 *  We swallow it and fall through to the path-segment fallback so the staging
 *  preview renders a placeholder name instead of crashing; the actual decode
 *  still fails gracefully into the existing toast path. */
internal fun queryDisplayName(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
    }
    return uri.lastPathSegment
}

/**
 * Best-effort byte size of a content Uri, queried via `OpenableColumns.SIZE`.
 * Returns -1 when the provider doesn't report a size (some virtual / streamed
 * providers omit it); callers must then enforce a cap via the bounded read.
 *
 * Also returns -1 when the Uri's grant has been revoked (a ghost Uri restored
 * after process death — see [queryDisplayName] / issue #531): the bounded read
 * downstream is itself `SecurityException`-guarded and will reject the file, so
 * treating a revoked grant as "size unknown" routes it into the same graceful
 * rejection rather than crashing the send coroutine.
 */
internal fun queryContentSize(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): Long {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0)
                }
            }
    }
    return -1L
}

/** `ContentResolver.getType` for a content Uri whose read grant may have been
 *  revoked (a ghost staging Uri restored after process death — issue #531).
 *  The platform docs say `getType` can throw `SecurityException` for a Uri the
 *  caller can no longer access; an unguarded call on a ghost Uri crashes the
 *  preview composition or the send coroutine before the already-guarded decode
 *  gets a chance to degrade. Returns "" on any failure so callers treat the
 *  ghost as an unknown / non-video type and let the guarded decode reject it
 *  into the existing decode-failure toast. */
internal fun safeGetType(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String = coerceResolvedMime { contentResolver.getType(uri) }

/** Pure swallow-and-default kernel behind [safeGetType], split out so the
 *  ghost-Uri contract (issue #531) — a throwing or null resolver lookup must
 *  collapse to "" rather than propagate — is unit-testable on the JVM without
 *  Robolectric, mirroring the `UriListSaver` codec split. */
internal inline fun coerceResolvedMime(getType: () -> String?): String = runCatching(getType).getOrNull().orEmpty()

/**
 * Predicate for image payloads delivered through Compose's receive-content path.
 *
 * Prefer the resolver's concrete MIME when available: it is per-Uri and catches
 * mixed clip payloads. Fall back to the clip's declared image MIME only when
 * the resolver is silent/guarded, which keeps text/document paste flowing to
 * the text field while still accepting clipboard providers that expose only a
 * clip-level image description.
 */
internal fun receiveContentMimeIsImage(
    resolvedMime: String,
    clipDeclaresImage: Boolean,
): Boolean =
    resolvedMime.startsWith("image/", ignoreCase = true) ||
        (resolvedMime.isBlank() && clipDeclaresImage)

internal fun <T> receiveContentImageValueOrNull(
    value: T?,
    clipDeclaresImage: Boolean,
    resolveMime: (T) -> String?,
): T? {
    val nonNullValue = value ?: return null
    val resolvedMime = coerceResolvedMime { resolveMime(nonNullValue) }
    return nonNullValue.takeIf { receiveContentMimeIsImage(resolvedMime, clipDeclaresImage) }
}
