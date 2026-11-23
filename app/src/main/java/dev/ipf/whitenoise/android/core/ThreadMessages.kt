package dev.ipf.whitenoise.android.core

private const val THREAD_MESSAGE_KIND = 1111uL

/**
 * Whether a row of [kind] is a NIP-22 thread message: kind 1111 that MDK placed [inThread] under a parent, so its
 * projected `thread` is set. MDK counts these as chat wherever kind 9 counts (unread, mentions, search), while a
 * kind 1111 without a parent stays a custom row.
 */
internal fun isThreadMessage(
    kind: ULong,
    inThread: Boolean,
): Boolean = kind == THREAD_MESSAGE_KIND && inThread
