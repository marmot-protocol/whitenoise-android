package dev.ipf.whitenoise.android.ui.group

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
@Composable
internal fun rememberConversationTranscriptSave(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): ConversationTranscriptSaveAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requests = remember { TranscriptSaveRequests() }
    val currentController = rememberUpdatedState(controller)
    val accountRef = appState.activeAccountRef
    val runtimeGeneration = appState.runtimeGeneration
    val groupId = controller.group.groupIdHex
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
            val request = requests.claimResult(accepted = uri != null)
            if (request != null && uri != null) {
                scope.launch {
                    try {
                        saveConversationTranscript(
                            cacheDir = context.cacheDir,
                            owner = request.owner,
                            openOutput = { context.contentResolver.openOutputStream(uri, "wt") },
                            discardOutput = { discardTranscriptDocument(context.contentResolver, uri) },
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (request.owner.isCurrent()) {
                            appState.presentFailure(
                                R.string.toast_couldnt_export_transcript,
                                "CONVERSATION_TRANSCRIPT_SAVE",
                                error,
                            )
                        }
                    } finally {
                        requests.finish(request)
                    }
                }
            }
        }
    return ConversationTranscriptSaveAction(requests) {
        val request = requests.begin(owner)
        if (request != null) {
            try {
                launcher.launch("white-noise-transcript-${groupId.take(TRANSCRIPT_FILENAME_GROUP_PREFIX_LENGTH)}.json")
            } catch (error: Exception) {
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
