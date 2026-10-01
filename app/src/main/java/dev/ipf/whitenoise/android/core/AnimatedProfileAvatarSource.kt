package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.media.isGif
import dev.ipf.whitenoise.android.media.u16le

/** GIF logical-screen width and height are little-endian u16s straight after the six-byte signature. */
private const val GIF_LOGICAL_WIDTH_OFFSET = 6
private const val GIF_LOGICAL_HEIGHT_OFFSET = 8
private const val GIF_LOGICAL_SCREEN_END = 10

/**
 * Largest GIF canvas edge an avatar may animate. Every frame of an animation is decoded at source size
 * before it is scaled, so this bounds the per-frame work a 2 MiB file can ask for; a larger canvas keeps
 * its static first frame.
 */
internal const val MAX_ANIMATED_PROFILE_AVATAR_EDGE: Int = 1024

/**
 * True when [bytes] are a GIF by content, not by URL suffix or response label, with a non-empty
 * logical screen no larger than [MAX_ANIMATED_PROFILE_AVATAR_EDGE] on either edge.
 */
internal fun isAnimatableProfileAvatar(bytes: ByteArray): Boolean {
    if (!isGif(bytes) || bytes.size < GIF_LOGICAL_SCREEN_END) return false
    val width = u16le(bytes, GIF_LOGICAL_WIDTH_OFFSET)
    val height = u16le(bytes, GIF_LOGICAL_HEIGHT_OFFSET)
    return width in 1..MAX_ANIMATED_PROFILE_AVATAR_EDGE && height in 1..MAX_ANIMATED_PROFILE_AVATAR_EDGE
}

/** Encoded-byte ceiling for any animated profile picture, matching the avatar fetch bound. */
private const val MAX_ANIMATED_PROFILE_AVATAR_BYTES = 2 * 1024 * 1024

/** [bytes] when they may animate as a profile picture — a bounded GIF by content — otherwise null. */
internal fun profileAvatarAnimationSource(bytes: ByteArray?): ByteArray? =
    bytes?.takeIf { it.size <= MAX_ANIMATED_PROFILE_AVATAR_BYTES && isAnimatableProfileAvatar(it) }
