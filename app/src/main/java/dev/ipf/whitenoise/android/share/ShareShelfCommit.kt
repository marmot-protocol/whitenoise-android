package dev.ipf.whitenoise.android.share

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Captured Android draft ownership, rolled back when the UI cannot commit the exact destination. */
internal suspend fun retainShareAtDestination(
    context: Context,
    account: String,
    groups: List<String>,
    payload: SharePayload,
    commit: suspend (droppedCount: Int) -> Boolean,
): Boolean {
    val files = PrivateShareFiles(context)
    val previous = mutableMapOf<String, List<android.net.Uri>>()
    var committed = false
    return try {
        val dropped =
            withContext(Dispatchers.IO) {
                synchronized(privateShareLock) {
                    groups
                        .distinct()
                        .map { group ->
                            previous[group] = files.leases.loadShelf(account, group)
                            val retained = files.leases.changeShelf(account, group, emptyList(), payload.streamUris)
                            payload.streamUris.count { files.owns(it) && it !in retained }
                        }.maxOrNull() ?: 0
                }
            }
        commit(dropped).also { committed = it }
    } finally {
        if (!committed) {
            withContext(NonCancellable + Dispatchers.IO) {
                previous.forEach { (group, shelf) ->
                    files.leases.changeShelf(account, group, shelf + payload.streamUris, shelf)
                }
            }
        }
    }
}
