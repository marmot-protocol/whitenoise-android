package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.AttachmentOpenIntentClaim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal typealias DocumentSaveFallback = suspend (source: File, fileName: String, mediaType: String) -> Unit
private typealias ExternalAttachmentOpen = suspend (File, String, String) -> OpenAttachmentResult
internal typealias AttachmentOpener =
    suspend (File, String, String, InstallerPermissionPersistence?) -> OpenAttachmentResult
private typealias InstallPermissionRequester = suspend () -> Boolean

internal data class InstallerPermissionPersistence(
    val claim: AttachmentOpenIntentClaim,
    val begin: suspend () -> Boolean,
    val finish: suspend () -> Boolean,
    val abandon: () -> Unit,
)

/** User dismissed the destination picker; unlike parent cancellation, a batch may continue. */
internal class DocumentDestinationCancelledException : CancellationException("document destination cancelled")

/**
 * Opens an attachment and, for Zapstore APKs, resumes the same tap after the
 * per-source package-install permission screen returns.
 */
@Composable
internal fun rememberAttachmentOpener(): AttachmentOpener {
    val context = LocalContext.current
    val requestInstallPermission =
        rememberStartActivityForResult { _ ->
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        }
    return remember(context, requestInstallPermission) {
        { source, mediaType, fileName, persistence ->
            val open: ExternalAttachmentOpen = { requestedSource, requestedMediaType, requestedFileName ->
                openAttachmentExternally(context, requestedSource, requestedMediaType, requestedFileName)
            }
            val requestPermission: InstallPermissionRequester = {
                requestInstallPermission(androidPackageInstallPermissionIntent(context))
            }
            if (persistence == null) {
                openAttachmentWithInstallerPermission(
                    source = source,
                    mediaType = mediaType,
                    fileName = fileName,
                    open = open,
                    requestInstallPermission = requestPermission,
                )
            } else {
                openAttachmentWithPersistedInstallerPermission(
                    source = source,
                    mediaType = mediaType,
                    fileName = fileName,
                    open = open,
                    requestInstallPermission = requestPermission,
                    persistence = persistence,
                )
            }
        }
    }
}

internal suspend fun openAttachmentWithInstallerPermission(
    source: File,
    mediaType: String,
    fileName: String,
    open: suspend (File, String, String) -> OpenAttachmentResult,
    requestInstallPermission: suspend () -> Boolean,
): OpenAttachmentResult {
    val initial = open(source, mediaType, fileName)
    return if (initial == OpenAttachmentResult.InstallPermissionRequired) {
        openAfterInstallerPermission(source, mediaType, fileName, open, requestInstallPermission)
    } else {
        initial
    }
}

private suspend fun openAfterInstallerPermission(
    source: File,
    mediaType: String,
    fileName: String,
    open: ExternalAttachmentOpen,
    requestInstallPermission: InstallPermissionRequester,
): OpenAttachmentResult {
    AttachmentPlaintextCache.protectPublicationFile(source)
    return try {
        requestInstallerPermissionFailure(requestInstallPermission)
            ?: open(source, mediaType, fileName)
    } finally {
        AttachmentPlaintextCache.unprotectPublicationFile(source)
    }
}

/**
 * Permission-aware opener for a persisted conversation tap. A fresh claim is
 * moved to a durable Settings handoff before White Noise leaves the foreground.
 * A claim recovered after process recreation only rechecks permission: denial
 * is reported instead of reopening Settings in a loop.
 */
internal suspend fun openAttachmentWithPersistedInstallerPermission(
    source: File,
    mediaType: String,
    fileName: String,
    open: ExternalAttachmentOpen,
    requestInstallPermission: suspend () -> Boolean,
    persistence: InstallerPermissionPersistence,
): OpenAttachmentResult {
    val initial = open(source, mediaType, fileName)
    if (initial != OpenAttachmentResult.InstallPermissionRequired) return initial
    if (persistence.claim == AttachmentOpenIntentClaim.InstallPermissionRecovery) {
        return OpenAttachmentResult.InstallPermissionDenied
    }
    check(persistence.begin()) { "installer permission handoff could not be persisted" }

    AttachmentPlaintextCache.protectPublicationFile(source)
    var permissionRequestActive = true
    return try {
        val permissionFailure = requestInstallerPermissionFailure(requestInstallPermission)
        check(persistence.finish()) { "installer permission handoff could not be completed" }
        permissionRequestActive = false
        permissionFailure ?: open(source, mediaType, fileName)
    } catch (cancellation: CancellationException) {
        persistence.abandon()
        permissionRequestActive = false
        throw cancellation
    } finally {
        if (permissionRequestActive) persistence.abandon()
        AttachmentPlaintextCache.unprotectPublicationFile(source)
    }
}

private suspend fun requestInstallerPermissionFailure(request: InstallPermissionRequester): OpenAttachmentResult? =
    try {
        if (request()) null else OpenAttachmentResult.InstallPermissionDenied
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: ActivityNotFoundException) {
        OpenAttachmentResult.InstallPermissionUnavailable
    } catch (_: SecurityException) {
        OpenAttachmentResult.SecurityFailure
    } catch (_: RuntimeException) {
        OpenAttachmentResult.InstallPermissionUnavailable
    }

internal fun androidPackageInstallPermissionIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

/**
 * Last-resort destination picker for general documents when MediaStore cannot
 * publish into Downloads. Cancellation is propagated without an error toast.
 */
@Composable
internal fun rememberDocumentSaveFallback(): DocumentSaveFallback {
    val context = LocalContext.current
    val launchCreateDocument = rememberStartActivityForResult { result -> result }
    return remember(context, launchCreateDocument) {
        { source, fileName, mediaType ->
            AttachmentPlaintextCache.protectPublicationFile(source)
            try {
                val result = launchCreateDocument(createDocumentIntent(fileName, mediaType))
                val destination =
                    result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
                        ?: throw DocumentDestinationCancelledException()
                withContext(Dispatchers.IO) {
                    copyDocumentToDestination(context, source, destination)
                }
            } finally {
                AttachmentPlaintextCache.unprotectPublicationFile(source)
            }
        }
    }
}

internal fun createDocumentIntent(
    fileName: String,
    mediaType: String,
): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mediaType.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_TITLE, MediaPipeline.safeDisplayName(fileName))
    }

internal suspend fun saveDocumentWithFallback(
    context: Context,
    source: File,
    fileName: String,
    mediaType: String,
    fallback: DocumentSaveFallback?,
): Boolean =
    try {
        withContext(Dispatchers.IO) {
            saveDocumentToDownloads(
                context = context,
                source = source,
                fileName = fileName,
                mediaType = mediaType,
            )
        }
    } catch (failure: AttachmentSaveException) {
        val selectDestination = fallback ?: throw failure
        selectDestination(source, fileName, mediaType)
        true
    }

@Composable
private fun <T> rememberStartActivityForResult(mapResult: (ActivityResult) -> T): suspend (Intent) -> T {
    val currentMapResult = rememberUpdatedState(mapResult)
    val pending = remember { AtomicReference<kotlinx.coroutines.CancellableContinuation<T>?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val continuation = pending.getAndSet(null) ?: return@rememberLauncherForActivityResult
            if (continuation.isActive) continuation.resume(currentMapResult.value(result))
        }
    DisposableEffect(pending) {
        onDispose {
            pending.getAndSet(null)?.cancel()
        }
    }
    return remember(launcher, pending) {
        { intent ->
            suspendCancellableCoroutine { continuation ->
                if (!pending.compareAndSet(null, continuation)) {
                    continuation.resumeWithException(
                        IllegalStateException("another platform activity request is already pending"),
                    )
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    pending.compareAndSet(continuation, null)
                }
                try {
                    launcher.launch(intent)
                } catch (failure: ActivityNotFoundException) {
                    pending.compareAndSet(continuation, null)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } catch (failure: SecurityException) {
                    pending.compareAndSet(continuation, null)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } catch (failure: IllegalStateException) {
                    pending.compareAndSet(continuation, null)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        }
    }
}
