package dev.ipf.whitenoise.android.share

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val context = LocalContext.current
    var loadedRevision by remember(account, group) { mutableStateOf<Int?>(null) }
    var previous by remember(account, group) { mutableStateOf<List<Uri>>(emptyList()) }
    val files = remember(context) { PrivateShareFiles(context) }
    LaunchedEffect(account, group, revision) {
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
        restore(retained)
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
