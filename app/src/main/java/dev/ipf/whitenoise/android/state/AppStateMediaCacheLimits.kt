package dev.ipf.whitenoise.android.state

import android.app.ActivityManager
import android.content.Context

// 24 MiB cap on decrypted attachment bytes resident in memory — roughly ten
// 1920px JPEGs. Persists across conversation re-entry.
internal const val MEDIA_PLAINTEXT_CACHE_MAX_BYTES: Long = 24L * 1024L * 1024L

// Admit ordinary photos while keeping large documents on the file-lease path.
internal const val MEDIA_PLAINTEXT_CACHE_MAX_ENTRY_BYTES: Long = 8L * 1024L * 1024L

// Decoded thumbnails (sampled to <=1280px): the floor keeps visible bubbles spinner-free on a low-memory device, the
// ceiling bounds what a long photo chat keeps decoded while it is scrolled back through.
internal const val MEDIA_THUMBNAIL_CACHE_MIN_BYTES: Long = 48L * 1024L * 1024L
internal const val MEDIA_THUMBNAIL_CACHE_MAX_BYTES: Long = 256L * 1024L * 1024L
private const val MEDIA_THUMBNAIL_CACHE_MEMORY_SHARE = 16L

@Volatile private var mediaThumbnailCacheBytes = 0L

/**
 * The decoded-thumbnail budget for this device, read once: a sixteenth of its RAM within the floor and ceiling, and the
 * floor on a low-RAM device. Hiding the UI halves it and backgrounding empties it, so the share is held only while the
 * app is in use.
 */
internal fun mediaThumbnailCacheMaxBytes(context: Context): Long =
    mediaThumbnailCacheBytes.takeIf { it > 0L } ?: run {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        mediaThumbnailCacheBudget(memory.totalMem, manager?.isLowRamDevice != false).also {
            mediaThumbnailCacheBytes = it
        }
    }

/** A sixteenth of [totalMemoryBytes] within the thumbnail floor and ceiling; a low-RAM device keeps the floor. */
internal fun mediaThumbnailCacheBudget(
    totalMemoryBytes: Long,
    lowRam: Boolean,
): Long =
    if (lowRam) {
        MEDIA_THUMBNAIL_CACHE_MIN_BYTES
    } else {
        (totalMemoryBytes / MEDIA_THUMBNAIL_CACHE_MEMORY_SHARE)
            .coerceIn(MEDIA_THUMBNAIL_CACHE_MIN_BYTES, MEDIA_THUMBNAIL_CACHE_MAX_BYTES)
    }

// ~256 MiB of persistent decrypted media on disk. OS may trim it earlier under
// device-wide cache pressure.
internal const val DISK_MEDIA_CACHE_MAX_BYTES: Long = 256L * 1024L * 1024L

// Match MDK's encrypted receive ceiling. L1 stays capped at 24 MiB so large
// documents remain durable without staying on the JVM heap after an open.
internal const val DISK_MEDIA_CACHE_MAX_ENTRY_BYTES: Long = 64L * 1024L * 1024L

/**
 * 32 MiB ceiling on the plaintext a presentation read may hold on the JVM heap: an image bubble, an album tile,
 * inline emoji artwork or a full-screen viewer page. It is the same figure as the sender cap
 * [ConversationController.MEDIA_RETAINED_MAX_BYTES], so every image this client can send can also be previewed. It
 * sits above [MEDIA_PLAINTEXT_CACHE_MAX_ENTRY_BYTES] (8 MiB), so an image too large for the L1 cache is still shown
 * from its file lease, and below MDK's 64 MiB default automatic transfer limit ([DISK_MEDIA_CACHE_MAX_ENTRY_BYTES]),
 * which bounds transport and disk rather than decoded heap. A verified image above this ceiling stays on disk and is
 * presented as too large to preview, while explicit Save and Share stream the whole file instead of reading it.
 */
internal const val ATTACHMENT_PRESENTATION_MAX_BYTES: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES
