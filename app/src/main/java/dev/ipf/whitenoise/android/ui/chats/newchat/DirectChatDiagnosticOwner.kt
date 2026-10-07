package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt

/** Attributes an interrupted tap to its lost owner, while a completed navigation may dispose that owner normally. */
internal suspend fun <T> withDmCreationOwner(
    attempt: DmCreationAttempt,
    isCurrent: () -> Boolean,
    action: suspend (markOpened: () -> Unit) -> T,
): T {
    var opened = false
    try {
        return action { opened = true }
    } finally {
        if (!opened && !isCurrent()) attempt.ownerReplaced()
    }
}
