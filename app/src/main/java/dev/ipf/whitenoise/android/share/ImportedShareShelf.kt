package dev.ipf.whitenoise.android.share

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Restores only the captured account/chat intake shelf, then commits each explicit shelf change. */
@Suppress("FunctionNaming")
@Composable
internal fun ImportedShareShelf(
    account: String,
    group: String,
    uris: List<Uri>,
    revision: Int,
    restore: (Result<ShareStreamStaging>) -> Unit,
) {
    if (account.isBlank() || group.isBlank()) return
    val currentUris by rememberUpdatedState(uris)
    val context = LocalContext.current
    var loadedRevision by remember(account, group) { mutableStateOf<Int?>(null) }
    var previous by remember(account, group) { mutableStateOf<List<Uri>>(emptyList()) }
    val files = remember(context) { PrivateShareFiles(context) }
    LaunchedEffect(account, group, revision) {
        val baseline = if (loadedRevision == null) uris.filter(files::owns) else previous
        val retained =
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    files.cleanStale()
                    val restoredUris = files.leases.loadShelf(account, group)
                    val staging = classifyShareStreams(restoredUris, { shareResolveMime(context, it) })
                    if (staging.mediaUris.isNotEmpty() && staging.documentUris.isNotEmpty()) {
                        ShareStreamStaging(emptyList(), restoredUris)
                    } else {
                        staging
                    }
                }
            }
        retained.onSuccess {
            previous = it.mediaUris + it.documentUris
            loadedRevision = revision
        }
        restore(
            retained.map { staging ->
                // A completed disk read must not undo a removal made while it was in flight.
                val removed = baseline.toSet() - currentUris.toSet()
                ShareStreamStaging(
                    staging.mediaUris.filterNot { it in removed },
                    staging.documentUris.filterNot { it in removed },
                )
            },
        )
    }
    LaunchedEffect(account, group, loadedRevision, revision, uris) {
        if (loadedRevision == revision) {
            val owned = uris.filter(files::owns)
            val prior = previous
            runCatchingCancellable {
                withContext(Dispatchers.IO) { files.leases.changeShelf(account, group, prior, owned) }
            }.onSuccess { previous = owned }
                .onFailure { restore(Result.failure(it)) }
        }
    }
}
