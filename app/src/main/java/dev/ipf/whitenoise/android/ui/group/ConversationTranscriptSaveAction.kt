package dev.ipf.whitenoise.android.ui.group

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.conversation.media.discardInterruptedCreatedDocument
import kotlinx.coroutines.CancellationException
import java.io.IOException

private const val TRANSCRIPT_FILENAME_GROUP_PREFIX_LENGTH = 12

/** The native details row's observable progress and guarded document-picker action. */
internal class ConversationTranscriptSaveAction(
    private val requests: TranscriptSaveRequests,
    val save: () -> Unit,
) {
    val busy: Boolean get() = requests.busy
}

/**
 * Binds Android's single document result to its requesting controller across recomposition.
 * Picker and coroutine callbacks stay together with the exact source owner they fence.
 * A request orphaned by lock-driven disposal never blocks a later export once no result for it is waiting.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
internal fun rememberConversationTranscriptSave(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): ConversationTranscriptSaveAction {
    val context = LocalContext.current
    val requests = remember { TranscriptSaveRequests() }
    val currentController = rememberUpdatedState(controller)
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val groupId = controller.group.groupIdHex
    val origin = "$runtimeGeneration:$accountRef:$groupId"
    var pendingOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var resultReady by rememberSaveable { mutableStateOf(false) }
    var acceptedDestination by rememberSaveable { mutableStateOf<String?>(null) }
    val owner =
        remember(controller, accountRef, runtimeGeneration, groupId) {
            TranscriptSaveOwner(
                current = {
                    currentController.value === controller &&
                        controller.group.groupIdHex == groupId &&
                        accountRef != null &&
                        controller.boundAccountRef == accountRef &&
                        appState.activeAccountRef == accountRef &&
                        appState.runtimeGeneration == runtimeGeneration &&
                        !appState.appLockScreenVisible &&
                        !appState.signOutInProgress &&
                        !appState.wipeInProgress
                },
                export = controller::exportConversationTranscriptFile,
            )
        }
    DisposableEffect(owner) {
        onDispose { owner.invalidate() }
    }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (pendingOrigin != null && !resultReady) {
                acceptedDestination = uri?.toString()
                resultReady = true
            }
        }
    LaunchedEffect(resultReady, appState.appLockScreenVisible, origin) {
        if (!resultReady || appState.appLockScreenVisible) return@LaunchedEffect
        val requestedOrigin = pendingOrigin
        val requestedAccount = pendingAccount
        val uri = acceptedDestination?.let(Uri::parse)
        val currentOrigin = "${appState.runtimeGeneration}:${appState.activeAccountRef}:$groupId"
        val request = requests.claimResult(accepted = uri != null && requestedOrigin == currentOrigin)
        if (request == null || uri == null) {
            pendingOrigin = null
            pendingAccount = null
            acceptedDestination = null
            resultReady = false
            if (uri != null) {
                if (requestedAccount == appState.activeAccountRef) appState.present(R.string.document_save_interrupted)
                discardInterruptedCreatedDocument(context, uri)
            }
            return@LaunchedEffect
        }
        try {
            saveConversationTranscript(
                cacheDir = context.cacheDir,
                owner = request.owner,
                openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                discardOutput = { discardTranscriptDocument(context.contentResolver, uri) },
            )
            pendingOrigin = null
            pendingAccount = null
            acceptedDestination = null
            resultReady = false
        } catch (error: CancellationException) {
            // Leave the accepted destination marker for an actionable cancellation
            // after authentication; the source owner and partial export are revoked.
            throw error
        } catch (error: Exception) {
            pendingOrigin = null
            pendingAccount = null
            acceptedDestination = null
            resultReady = false
            if (request.owner.isCurrent()) {
                appState.presentFailure(R.string.toast_couldnt_export_transcript, "CONVERSATION_TRANSCRIPT_SAVE", error)
            }
        } finally {
            requests.finish(request)
        }
    }
    return ConversationTranscriptSaveAction(requests) {
        // A saved origin whose request died with a disposed screen, and whose picker never answered, is stale.
        val staleOrigin = pendingOrigin != null && !requests.busy && !resultReady
        val request = if (pendingOrigin == null || staleOrigin) requests.begin(owner) else null
        if (request != null) {
            try {
                acceptedDestination = null
                pendingOrigin = origin
                pendingAccount = accountRef
                launcher.launch("white-noise-transcript-${groupId.take(TRANSCRIPT_FILENAME_GROUP_PREFIX_LENGTH)}.json")
            } catch (error: Exception) {
                pendingOrigin = null
                pendingAccount = null
                requests.finish(request)
                if (owner.isCurrent()) {
                    appState.presentFailure(
                        R.string.toast_couldnt_export_transcript,
                        "TRANSCRIPT_DOCUMENT_PICKER",
                        error,
                    )
                }
            }
        }
    }
}

/** Removes an incomplete SAF document, or truncates it when the provider does not support deletion. */
private fun discardTranscriptDocument(
    resolver: ContentResolver,
    uri: Uri,
) {
    val deleted =
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
            false
        }
    if (!deleted) {
        val output =
            resolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not clear the incomplete transcript")
        output.close()
    }
}
