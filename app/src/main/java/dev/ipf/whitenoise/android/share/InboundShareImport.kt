package dev.ipf.whitenoise.android.share

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Intake runs above bootstrap/app lock. Only a request id enters saved state. */
@Composable
internal fun rememberInboundShareImport(
    context: Context,
    holder: MainShellStateHolder,
    inbound: ShareRequest?,
): InboundShareImportState {
    val state = remember(context, holder) { InboundShareImportState() }
    createInboundShareImportStore(context, holder, inbound?.requestId, state)
    persistInboundShareImport(holder, inbound, state)
    restorePendingShareImport(holder, inbound?.requestId, state)
    return state
}

/** Creates encrypted recovery and private-source adapters off Main; creation failure remains explicit user feedback. */
@Composable
private fun createInboundShareImportStore(
    context: Context,
    holder: MainShellStateHolder,
    requestId: String?,
    state: InboundShareImportState,
) {
    LaunchedEffect(context, holder, requestId) {
        if (state.store != null) return@LaunchedEffect
        state.creationFailed = false
        val delegate =
            runCatchingCancellable {
                withContext(Dispatchers.IO) { PrivateShareFiles(context).cleanStale() }
                createPendingShareRequestStore(context)
            }.getOrNull()
        state.creationFailed = delegate == null
        if (delegate == null) return@LaunchedEffect
        val importer = ShareFileImporter(context)
        state.store =
            SerializedPendingShareRequestStore(
                delegate,
                importRequest = { request ->
                    importer.import(request) {
                        if (holder.inboundShareRequest.value?.requestId == request.requestId) state.progress = it
                    }
                },
                releaseRequest = PrivateShareFiles(context).leases::releaseRequest,
                validateRequest = { validateImportedShare(context, it) },
                releaseAllRequests = PrivateShareFiles(context).leases::releasePendingRequests,
            )
    }
}

/** Persists the interruption marker before reads and rejects completion for a superseded inbound request. */
@Composable
private fun persistInboundShareImport(
    holder: MainShellStateHolder,
    inbound: ShareRequest?,
    state: InboundShareImportState,
) {
    LaunchedEffect(state.store, state.creationFailed, inbound?.requestId) {
        val request = inbound?.takeUnless { it.payload.importReady } ?: return@LaunchedEffect
        val store = state.store
        if (store == null) {
            if (state.creationFailed) {
                holder.acceptInboundShareRequest(failedShareImport(request, ShareImportError.Storage))
            }
            return@LaunchedEffect
        }
        // The token also recovers the narrow pre-marker process-death window.
        if (!holder.markInboundSharePersisted(request.requestId)) return@LaunchedEffect
        state.progress = null
        val ready =
            runCatchingCancellable {
                if (store.save(request)) store.load(request.requestId) else null
            }.getOrNull()
        if (holder.inboundShareRequest.value?.requestId == request.requestId) {
            holder.acceptInboundShareRequest(ready ?: failedShareImport(request, ShareImportError.Storage))
        }
        state.progress = null
    }
}

/** Restores only the retained route token when no newer inbound request owns it, without reopening external grants. */
@Composable
private fun restorePendingShareImport(
    holder: MainShellStateHolder,
    inboundId: String?,
    state: InboundShareImportState,
) {
    LaunchedEffect(state.store, holder.pendingShareRequestId, inboundId) {
        val store = state.store ?: return@LaunchedEffect
        if (inboundId != null) return@LaunchedEffect
        val id = holder.pendingShareRequestId ?: return@LaunchedEffect
        if (holder.pendingShareRequest.value?.requestId != id) {
            val restored = runCatchingCancellable { store.load(id) }.getOrNull()
            holder.restorePendingShareRequest(
                id,
                restored ?: ShareRequest(
                    SharePayload(null, emptyList(), null, true, listOf(ShareImportError.Interrupted)),
                    shortcutId = null,
                    requestId = id,
                ),
            )
        }
    }
}

/** Converts an intake failure into bounded recoverable status, never a send or an empty accepted source list. */
private fun failedShareImport(
    request: ShareRequest,
    error: ShareImportError,
): ShareRequest =
    request.copy(
        payload =
            request.payload.copy(
                streamUris = emptyList(),
                importReady = true,
                importErrors = listOf(error),
                importRejectedCount = request.payload.streamUris.size,
            ),
    )
