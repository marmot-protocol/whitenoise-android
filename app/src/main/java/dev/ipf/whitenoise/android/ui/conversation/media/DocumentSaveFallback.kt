package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Last-resort destination picker for general documents when MediaStore cannot
 * publish into Downloads. Picker cancellation propagates to the live caller.
 * Saved metadata, launcher registration and the original continuation share this composition owner.
 * A request orphaned by lock-driven disposal never blocks a later save once no result for it is waiting.
 */
@Suppress("LongMethod")
@Composable
internal fun rememberDocumentSaveFallback(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
): DocumentSaveFallback {
    val context = LocalContext.current
    val origin = "${appState.runtimeGeneration}:${appState.activeAccountRef}:$groupIdHex"
    var requestedOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var resultReady by rememberSaveable { mutableStateOf(false) }
    var acceptedDestination by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = remember { AtomicReference<kotlinx.coroutines.CancellableContinuation<Uri>?>(null) }
    DisposableEffect(pending) {
        onDispose { pending.getAndSet(null)?.cancel() }
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (requestedOwner == null || resultReady) return@rememberLauncherForActivityResult
            acceptedDestination =
                result.data
                    ?.data
                    ?.takeIf { result.resultCode == Activity.RESULT_OK }
                    ?.toString()
            resultReady = true
        }
    LaunchedEffect(resultReady, appState.appLockScreenVisible, origin) {
        if (!resultReady || appState.appLockScreenVisible) return@LaunchedEffect
        val owner = requestedOwner
        val account = requestedAccount
        val destination = acceptedDestination?.let(Uri::parse)
        val continuation = pending.getAndSet(null)
        requestedOwner = null
        requestedAccount = null
        acceptedDestination = null
        resultReady = false
        val currentOrigin = "${appState.runtimeGeneration}:${appState.activeAccountRef}:$groupIdHex"
        settleDocumentDestination(
            context = context,
            continuation = continuation,
            liveOwner = owner == origin && owner == currentOrigin,
            destination = destination,
            presentInterruption = {
                if (account == appState.activeAccountRef) appState.present(R.string.document_save_interrupted)
            },
        )
    }
    val requestDestination: suspend (String, String) -> Uri = { fileName, mediaType ->
        suspendCancellableCoroutine { continuation ->
            // Only a live caller or an unprocessed lock-time result blocks a new save. A saved owner whose
            // continuation died with the disposed screen, and whose picker never answered, is superseded.
            val staleOwnerBlocks = requestedOwner != null && resultReady
            if (staleOwnerBlocks || !pending.compareAndSet(null, continuation)) {
                continuation.resumeWithException(IllegalStateException("another document save is pending"))
            } else {
                acceptedDestination = null
                requestedOwner = origin
                requestedAccount = appState.activeAccountRef
                continuation.invokeOnCancellation { pending.compareAndSet(continuation, null) }
                val failure =
                    launchDocumentDestination(
                        launch = { launcher.launch(createDocumentIntent(fileName, mediaType)) },
                        onFailure = {
                            requestedOwner = null
                            requestedAccount = null
                            pending.compareAndSet(continuation, null)
                        },
                    )
                if (failure != null && continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }
    return { source, fileName, mediaType ->
        saveDocumentToChosenDestination(context, source) { requestDestination(fileName, mediaType) }
    }
}

/**
 * Copies [source] to the destination the user chooses, keeping it protected from cache cleanup until the copy finishes.
 */
private suspend fun saveDocumentToChosenDestination(
    context: Context,
    source: File,
    selectDestination: suspend () -> Uri,
) {
    AttachmentPlaintextCache.protectPublicationFile(source)
    try {
        val destination = selectDestination()
        withContext(Dispatchers.IO) { copyDocumentToDestination(context, source, destination) }
    } finally {
        AttachmentPlaintextCache.unprotectPublicationFile(source)
    }
}

/** Best-effort cleanup only when the provider proves this unused CREATE_DOCUMENT output is empty. */
internal fun discardEmptyCreatedDocument(
    context: Context,
    destination: Uri,
) {
    runCatching {
        val empty =
            context.contentResolver.query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst() && column >= 0 && !cursor.isNull(column) && cursor.getLong(column) == 0L
            } == true
        if (empty) DocumentsContract.deleteDocument(context.contentResolver, destination)
    }
}

/** Finishes accepted empty-output cleanup even if clearing its result key or relocking retires the UI owner. */
internal suspend fun discardInterruptedCreatedDocument(
    context: Context,
    destination: Uri,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    withContext(NonCancellable + dispatcher) { discardEmptyCreatedDocument(context, destination) }
}

/** Builds the system create-document request with a normalized MIME type and a safe suggested name. */
internal fun createDocumentIntent(
    fileName: String,
    mediaType: String,
): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = normalizeDocumentMime(mediaType)
        putExtra(Intent.EXTRA_TITLE, safeDocumentDisplayName(fileName))
    }

/**
 * Runs [launch] and returns any expected platform launch failure, calling [onFailure] whenever the request was not
 * dispatched.
 */
private inline fun launchDocumentDestination(
    launch: () -> Unit,
    onFailure: () -> Unit,
): RuntimeException? {
    var dispatched = false
    return try {
        launch()
        dispatched = true
        null
    } catch (failure: ActivityNotFoundException) {
        failure
    } catch (failure: SecurityException) {
        failure
    } catch (failure: IllegalStateException) {
        failure
    } catch (failure: IllegalArgumentException) {
        failure
    } finally {
        // Also retire pending ownership if an unexpected platform failure propagates.
        if (!dispatched) onFailure()
    }
}

/** Completes a live owner's wait, or cancels a stale one and cleans up its unused destination. */
private suspend fun settleDocumentDestination(
    context: Context,
    continuation: CancellableContinuation<Uri>?,
    liveOwner: Boolean,
    destination: Uri?,
    presentInterruption: () -> Unit,
) {
    if (continuation?.isActive == true && liveOwner) {
        completeDocumentDestination(continuation, destination)
        return
    }
    // A caller still waiting under a stale owner must not suspend forever.
    continuation?.cancel(DocumentDestinationCancelledException())
    if (destination != null) {
        // Lock disposal intentionally cancelled the source's lifetime/security
        // guards. Do not revive stale plaintext or bypass message-expiry checks.
        // Remove the empty CREATE_DOCUMENT output and invite a fresh guarded save.
        presentInterruption()
        discardInterruptedCreatedDocument(context, destination)
    }
}

/** Resumes the live caller with its destination, or cancels it when the picker returned none. */
private fun completeDocumentDestination(
    continuation: kotlinx.coroutines.CancellableContinuation<Uri>,
    destination: Uri?,
) {
    if (destination == null) {
        continuation.cancel(DocumentDestinationCancelledException())
    } else {
        continuation.resume(destination)
    }
}
