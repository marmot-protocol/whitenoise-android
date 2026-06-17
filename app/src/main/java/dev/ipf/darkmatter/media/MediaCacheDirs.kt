package dev.ipf.darkmatter.media

/**
 * Names of the `cacheDir` subdirectories that hold decrypted media plaintext.
 *
 * These are privacy-cleanup targets: the conversation UI creates them and the
 * sign-out wipe deletes them. Centralizing the names here keeps the create and
 * wipe sites from drifting apart — a silent rename on one side would otherwise
 * leave decrypted plaintext behind.
 */
object MediaCacheDirs {
    const val VOICE = "voice_attachments"
    const val VIDEO = "video_attachments"
    const val SHARED = "shared_media"
}
