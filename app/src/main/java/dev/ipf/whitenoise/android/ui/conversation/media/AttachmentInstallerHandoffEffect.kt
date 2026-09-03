package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentDownloadWorkState
import dev.ipf.whitenoise.android.state.AttachmentInstallerHandoffCoordinator
import dev.ipf.whitenoise.android.state.AttachmentInstallerHandoffRequest
import dev.ipf.whitenoise.android.state.AttachmentReferenceNotReadyException
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentDownloadWorkState
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.isTransientAttachmentDownloadFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private data class InstallerArtifact(
    val file: File,
    val mediaType: String,
    val fileName: String,
)

private sealed interface InstallerArtifactAttempt {
    data class Ready(
        val artifact: InstallerArtifact,
    ) : InstallerArtifactAttempt

    data object RetryFromDurableWork : InstallerArtifactAttempt

    data object TerminalFailure : InstallerArtifactAttempt
}

private class InstallerDispatchDeferredException : IllegalStateException("installer dispatch deferred")

private data class InstallerHandoffMessages(
    val couldntLoad: String,
    val couldntOpen: String,
    val noInstaller: String,
    val permissionDenied: String,
    val permissionUnavailable: String,
    val installUnsupported: String,
    val invalidPackage: String,
)

private sealed interface InstallerDispatchOutcome {
    data class Completed(
        val result: OpenAttachmentResult,
    ) : InstallerDispatchOutcome

    data class Failed(
        val cause: Throwable,
    ) : InstallerDispatchOutcome

    data object Deferred : InstallerDispatchOutcome

    data object NotClaimed : InstallerDispatchOutcome
}

/**
 * App-shell owner for a received APK tap. It survives chat navigation, waits
 * for verified materialization, and claims the persisted request only while
 * White Noise is foreground and the Activity is RESUMED.
 */
@Composable
internal fun attachmentInstallerHandoffEffect(appState: WhiteNoiseAppState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val openAttachment = rememberAttachmentOpener()
    val messages =
        InstallerHandoffMessages(
            couldntLoad = stringResource(R.string.media_couldnt_load),
            couldntOpen = stringResource(R.string.media_couldnt_open),
            noInstaller = stringResource(R.string.media_apk_no_installer),
            permissionDenied = stringResource(R.string.media_apk_permission_denied),
            permissionUnavailable = stringResource(R.string.media_apk_permission_unavailable),
            installUnsupported = stringResource(R.string.media_apk_install_unsupported),
            invalidPackage = stringResource(R.string.media_apk_invalid),
        )
    val coordinator = appState.attachmentInstallerHandoffs

    LaunchedEffect(appState, coordinator.revision, lifecycleOwner) {
        processInstallerHandoff(
            context = context,
            lifecycle = lifecycleOwner.lifecycle,
            appState = appState,
            openAttachment = openAttachment,
            messages = messages,
        )
    }
}

/** Resolves, lifecycle-gates, claims, and reports one persisted installer handoff. */
private suspend fun processInstallerHandoff(
    context: Context,
    lifecycle: Lifecycle,
    appState: WhiteNoiseAppState,
    openAttachment: AttachmentOpener,
    messages: InstallerHandoffMessages,
) {
    val coordinator = appState.attachmentInstallerHandoffs
    coordinator.pending()?.let { request ->
        coordinator.ensureTransfer(request)
        when (val outcome = awaitInstallerArtifact(context, appState, request)) {
            is InstallerArtifactAttempt.Ready -> {
                if (lifecycle.awaitResumedOrDestroyed()) {
                    val dispatch =
                        dispatchInstallerHandoff(
                            lifecycle,
                            coordinator,
                            request,
                            outcome.artifact,
                            openAttachment,
                        )
                    reportInstallerDispatch(appState, dispatch, messages)
                }
            }
            InstallerArtifactAttempt.RetryFromDurableWork -> Unit
            InstallerArtifactAttempt.TerminalFailure -> {
                if (coordinator.consume(request)) appState.present(messages.couldntLoad)
            }
        }
    }
}

/** Reports the accepted launch result or a failure restored for the next owner. */
private fun reportInstallerDispatch(
    appState: WhiteNoiseAppState,
    outcome: InstallerDispatchOutcome,
    messages: InstallerHandoffMessages,
) {
    when (outcome) {
        is InstallerDispatchOutcome.Completed -> presentInstallerOutcome(appState, outcome.result, messages)
        is InstallerDispatchOutcome.Failed -> {
            Log.w(INSTALLER_HANDOFF_TAG, "installer_handoff_launch_failed", outcome.cause)
            appState.present(messages.couldntOpen, copyable = true)
        }
        InstallerDispatchOutcome.Deferred,
        InstallerDispatchOutcome.NotClaimed,
        -> Unit
    }
}

/** Claims immediately before invoking the permission-aware Android launcher. */
private suspend fun dispatchInstallerHandoff(
    lifecycle: Lifecycle,
    coordinator: AttachmentInstallerHandoffCoordinator,
    request: AttachmentInstallerHandoffRequest,
    artifact: InstallerArtifact,
    openAttachment: AttachmentOpener,
): InstallerDispatchOutcome {
    var openResult: OpenAttachmentResult? = null
    val dispatchAttempt =
        runCatchingCancellable {
            claimAndDispatchAttachmentOpen(
                claim = { coordinator.claim(request) },
                restore = { coordinator.restore(request) },
                dispatch = { claim ->
                    val result =
                        openAttachment(
                            artifact.file,
                            artifact.mediaType,
                            artifact.fileName,
                            InstallerPermissionPersistence(
                                claim = claim,
                                begin = { coordinator.beginInstallPermission(request) },
                                finish = { coordinator.finishInstallPermission(request) },
                                abandon = { coordinator.abandonInstallPermission(request) },
                            ),
                            AttachmentDispatchGuard(
                                canDispatch = {
                                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        coordinator.canDispatch(request)
                                },
                            ),
                        )
                    if (result == OpenAttachmentResult.DestinationNotVisible) {
                        throw InstallerDispatchDeferredException()
                    }
                    openResult = result
                },
            )
        }
    val failure = dispatchAttempt.exceptionOrNull()
    return when {
        failure is InstallerDispatchDeferredException -> InstallerDispatchOutcome.Deferred
        failure != null -> InstallerDispatchOutcome.Failed(failure)
        dispatchAttempt.getOrThrow() -> InstallerDispatchOutcome.Completed(checkNotNull(openResult))
        else -> InstallerDispatchOutcome.NotClaimed
    }
}

/** Maps deterministic platform outcomes to the existing localized attachment errors. */
private fun presentInstallerOutcome(
    appState: WhiteNoiseAppState,
    result: OpenAttachmentResult,
    messages: InstallerHandoffMessages,
) {
    when (result) {
        OpenAttachmentResult.Opened -> Unit
        OpenAttachmentResult.NoInstaller -> appState.present(messages.noInstaller)
        OpenAttachmentResult.InstallPermissionDenied,
        OpenAttachmentResult.InstallPermissionRequired,
        -> appState.present(messages.permissionDenied)
        OpenAttachmentResult.InstallPermissionUnavailable -> {
            appState.present(messages.permissionUnavailable, copyable = true)
        }
        OpenAttachmentResult.InstallUnsupported -> appState.present(messages.installUnsupported)
        OpenAttachmentResult.InvalidPackage -> appState.present(messages.invalidPackage)
        OpenAttachmentResult.NoHandler,
        OpenAttachmentResult.MissingArtifact,
        OpenAttachmentResult.SecurityFailure,
        OpenAttachmentResult.Error,
        -> appState.present(messages.couldntOpen, copyable = true)
        OpenAttachmentResult.DestinationNotVisible -> Unit
    }
}

/** Joins the immediate transfer, then lets durable work own transient retries. */
private suspend fun awaitInstallerArtifact(
    context: Context,
    appState: WhiteNoiseAppState,
    request: AttachmentInstallerHandoffRequest,
): InstallerArtifactAttempt {
    val immediate = materializeInstallerArtifact(context, appState, request)
    if (immediate != InstallerArtifactAttempt.RetryFromDurableWork) return immediate

    val workState =
        attachmentDownloadWorkState(context, request.transfer) {
            appState.attachmentInstallerHandoffs.hasInteractiveTransfer(request)
        }
    return appState.mediaCacheRevision
        .combine(workState) { _, state -> state }
        .map { state ->
            val cachedAttempt =
                if (appState.hasCachedAttachmentAfterHydration(request.transfer)) {
                    materializeInstallerArtifact(context, appState, request)
                } else {
                    null
                }
            when (cachedAttempt) {
                is InstallerArtifactAttempt.Ready -> cachedAttempt
                InstallerArtifactAttempt.TerminalFailure -> cachedAttempt
                InstallerArtifactAttempt.RetryFromDurableWork,
                null,
                ->
                    if (state == AttachmentDownloadWorkState.Finished) {
                        InstallerArtifactAttempt.TerminalFailure
                    } else {
                        null
                    }
            }
        }.filterNotNull()
        .first()
}

/** Resolves metadata from MDK and publishes a reusable private FileProvider source. */
private suspend fun materializeInstallerArtifact(
    context: Context,
    appState: WhiteNoiseAppState,
    request: AttachmentInstallerHandoffRequest,
): InstallerArtifactAttempt {
    val reference =
        appState.resolveAttachmentReference(request.transfer)
    return when {
        reference == null -> InstallerArtifactAttempt.RetryFromDurableWork
        reference.sourceEpoch != request.sourceEpoch -> InstallerArtifactAttempt.TerminalFailure
        !isAndroidPackageOpenCandidate(reference.mediaType, reference.fileName) -> {
            InstallerArtifactAttempt.TerminalFailure
        }
        else ->
            runCatchingCancellable {
                materializeDocumentAttachmentSource(
                    context = context,
                    messageIdHex = request.transfer.messageIdHex,
                    attachmentIndex = request.transfer.attachmentIndex,
                    reference = reference,
                    resolveSource = {
                        appState.downloadAttachmentPlaintextSource(
                            request = request.transfer,
                            reference = reference,
                            priority = AttachmentDownloadPriority.Interactive,
                            persistInteractiveIntent = false,
                        )
                    },
                )
            }.fold(
                onSuccess = { file ->
                    InstallerArtifactAttempt.Ready(
                        InstallerArtifact(file, reference.mediaType, reference.fileName),
                    )
                },
                onFailure = { failure -> installerArtifactFailure(failure) },
            )
    }
}

/** Keeps only reference-readiness and conservative network failures retryable. */
private fun installerArtifactFailure(failure: Throwable): InstallerArtifactAttempt =
    if (
        failure is AttachmentReferenceNotReadyException ||
        isTransientAttachmentDownloadFailure(failure)
    ) {
        InstallerArtifactAttempt.RetryFromDurableWork
    } else {
        InstallerArtifactAttempt.TerminalFailure
    }

private const val INSTALLER_HANDOFF_TAG = "AttachmentInstaller"
