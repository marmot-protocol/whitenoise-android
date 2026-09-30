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
