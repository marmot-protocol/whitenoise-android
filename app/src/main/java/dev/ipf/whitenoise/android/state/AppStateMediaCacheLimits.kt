package dev.ipf.whitenoise.android.state

// 24 MiB cap on decrypted attachment bytes resident in memory — roughly ten
// 1920px JPEGs. Persists across conversation re-entry.
internal const val MEDIA_PLAINTEXT_CACHE_MAX_BYTES: Long = 24L * 1024L * 1024L

// Admit ordinary photos while keeping large documents on the file-lease path.
internal const val MEDIA_PLAINTEXT_CACHE_MAX_ENTRY_BYTES: Long = 8L * 1024L * 1024L

// ~48 MiB of decoded thumbnails (sampled to <=1280px). Enough to keep visible
// bubbles spinner-free; bounded so it cannot grow unbounded.
internal const val MEDIA_THUMBNAIL_CACHE_MAX_BYTES: Long = 48L * 1024L * 1024L

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
 * presented as too large to preview, while explicit Save and Share keep their own budget.
 */
internal const val ATTACHMENT_PRESENTATION_MAX_BYTES: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES

/**
 * Budget for an explicit Save or Share of an image, which still hands one whole array to Android's MediaStore and
 * FileProvider APIs. It is the largest array the JVM can address, so it adds no policy of its own, it only keeps
 * those explicit reads on the same checked primitive as previews instead of an unchecked whole-file read. Streaming
 * them from the file lease would remove the array altogether.
 */
internal const val ATTACHMENT_EXPLICIT_READ_MAX_BYTES: Long = Int.MAX_VALUE.toLong()
