package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.discardInterruptedCreatedDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

internal class KeyFileExport {
    internal var picking by mutableStateOf(false)
    internal var request by mutableStateOf<RequestedKeyFile?>(null)
    internal var destination by mutableStateOf<Uri?>(null)
    internal var saving by mutableStateOf(false)
    internal var feedback by mutableStateOf<KeyFileExportFeedback?>(null)
    internal val saveOperation = ProfileKeyOperation()

    val idle: Boolean
        get() = !picking && destination == null && !saving

    /** Drop in-flight provider feedback on stop while retaining the picker handoff. */
    internal fun cancelSave() {
        saveOperation.cancel()
        saving = false
    }
}

internal enum class KeyFileExportFeedback { SaveFailed, Expired }

internal data class KeyFileExportOwner(
    val accountId: String,
    val accountRef: String?,
    val runtimeGeneration: Int,
)

internal data class RequestedKeyFile(
    val owner: KeyFileExportOwner,
    val export: PendingKeyExport,
)

internal class KeyFileExportLauncher(
    val state: KeyFileExport,
    val launch: (PendingKeyExport, String) -> Unit,
)

internal val LocalKeyFileExportLauncher = compositionLocalOf<KeyFileExportLauncher?> { null }

private fun WhiteNoiseAppState.keyFileExportOwner(): KeyFileExportOwner? =
    activeAccount
        ?.takeIf { it.localSigning && !signOutInProgress && !wipeInProgress }
        ?.let { KeyFileExportOwner(it.accountIdHex, activeAccountRef, runtimeGeneration) }

/** Owns the picker and its in-memory secret above the protected shell, which App Lock removes from composition. */
@Composable
internal fun rememberKeyFileExport(appState: WhiteNoiseAppState): KeyFileExportLauncher {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val state = remember(appState) { KeyFileExport() }

    fun canDeliver(owner: KeyFileExportOwner): Boolean =
        appState.keyFileExportOwner() == owner &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            !appState.appLock.screenVisible &&
            !appState.appLock.evaluationPending

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            state.picking = false
            state.destination = uri
            if (uri == null) state.request = null
        }

    val locked = appState.appLock.screenVisible || appState.appLock.evaluationPending
    KeyFileExportEffects(appState, state, lifecycle, lifecycleState, locked, ::canDeliver) { uri, request ->
        saveExport(state, scope, context, uri, request, ::canDeliver)
    }
    if (!locked && lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
        val dismiss = { state.feedback = null }
        ProfileKeyFeedbackDialogs(
            nativeExportError = null,
            shareErrorDialog = false,
            saveErrorDialog = state.feedback == KeyFileExportFeedback.SaveFailed,
            expiredExportDialog = state.feedback == KeyFileExportFeedback.Expired,
            onNativeErrorDismiss = {},
            onShareErrorDismiss = {},
            onSaveErrorDismiss = dismiss,
            onExpiryDismiss = dismiss,
        )
    }

    return KeyFileExportLauncher(state) launch@{ export, filename ->
        val current = appState.keyFileExportOwner() ?: return@launch
        if (!state.idle || !canDeliver(current)) return@launch
        state.request = RequestedKeyFile(current, export)
        state.picking = true
        if (runCatching { exportLauncher.launch(filename) }.isFailure) {
            state.picking = false
            state.request = null
            state.feedback = KeyFileExportFeedback.SaveFailed
        }
    }
}

private fun saveExport(
    state: KeyFileExport,
    scope: CoroutineScope,
    context: Context,
    uri: Uri,
    request: RequestedKeyFile,
    canDeliver: (KeyFileExportOwner) -> Boolean,
) {
    state.saving = true
    val started =
        state.saveOperation.start(
            scope = scope,
            canDeliver = { canDeliver(request.owner) },
            load = {
                val saved = writeKeyExport(request.export.content) { context.contentResolver.openOutputStream(uri) }
                if (!saved) discardInterruptedCreatedDocument(context, uri)
                saved
            },
            onResult = { saved -> if (!saved) state.feedback = KeyFileExportFeedback.SaveFailed },
            onFinished = { state.saving = false },
        )
    if (!started) state.saving = false
}

@Composable
@Suppress("LongParameterList")
private fun KeyFileExportEffects(
    appState: WhiteNoiseAppState,
    state: KeyFileExport,
    lifecycle: Lifecycle,
    lifecycleState: Lifecycle.State,
    locked: Boolean,
    canDeliver: (KeyFileExportOwner) -> Boolean,
    save: (Uri, RequestedKeyFile) -> Unit,
) {
    val context = LocalContext.current
    val owner = appState.keyFileExportOwner()
    LaunchedEffect(state, state.request) {
        val request = state.request ?: return@LaunchedEffect
        delay(request.export.remainingMillis())
        if (state.request === request) state.request = null
    }
    LaunchedEffect(state, owner) {
        if (state.request?.owner != owner) state.request = null
        state.cancelSave()
        state.feedback = null
    }
    LaunchedEffect(state, lifecycleState, locked, owner, state.request, state.destination) {
        val uri = state.destination ?: return@LaunchedEffect
        val request = state.request
        if (request == null || request.owner != appState.keyFileExportOwner() || request.export.isExpired()) {
            state.request = null
            state.destination = null
            state.feedback = KeyFileExportFeedback.Expired
            discardInterruptedCreatedDocument(context, uri)
        } else if (canDeliver(request.owner)) {
            state.request = null
            state.destination = null
            save(uri, request)
        }
    }
    DisposableEffect(state, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) state.cancelSave()
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.cancelSave()
            state.request = null
            val uri = state.destination
            if (uri != null) appState.launchMutation { discardInterruptedCreatedDocument(context, uri) }
        }
    }
}
