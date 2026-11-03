package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.R

/**
 * Icon for a read-state command, shared by the row menu, selection bar, swipe cue and notification action (#3566).
 * The open envelope reads the chat and the dotted closed envelope marks it unread, so neither reuses the Select
 * checkmark.
 */
internal fun chatReadIcon(markRead: Boolean): Int = if (markRead) R.drawable.ic_mark_read else R.drawable.ic_mark_unread
