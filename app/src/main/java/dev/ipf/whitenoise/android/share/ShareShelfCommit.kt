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
    val previous = withContext(Dispatchers.IO) { groups.associateWith { files.leases.loadShelf(account, it) } }
    var committed = false
    return try {
        val dropped =
            withContext(Dispatchers.IO) {
                previous
                    .map { (group, shelf) ->
                        val retained = files.leases.saveShelf(account, group, shelf + payload.streamUris)
                        payload.streamUris.count { files.owns(it) && it !in retained }
                    }.maxOrNull() ?: 0
            }
        commit(dropped).also { committed = it }
    } finally {
        if (!committed) {
            withContext(NonCancellable + Dispatchers.IO) {
                previous.forEach { (group, shelf) -> files.leases.saveShelf(account, group, shelf) }
            }
        }
    }
}
