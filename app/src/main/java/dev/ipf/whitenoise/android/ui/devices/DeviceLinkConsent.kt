@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.media.AudioManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.DeviceLinkRequest
import dev.ipf.whitenoise.android.state.DeviceLinkRequestWatcher
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ProtectDialogInput
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBarNavigation
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.findActivity
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val DEVICE_LINK_WITH_ME_TAG = "device_link.consent.with_me"
internal const val DEVICE_LINK_DID_NOT_START_TAG = "device_link.consent.did_not_start"
internal const val DEVICE_LINK_SOMEONE_ASKED_TAG = "device_link.consent.someone_asked"
private const val CALL_CONSENT_DELAY_MS = 10_000L
private const val CALL_MODE_CHECK_MS = 1_000L

internal data class DeviceLinkConsentProfile(
    val name: String,
    val seed: String,
    val avatarUrl: String?,
)

@Suppress("LongParameterList")
@Composable
internal fun DeviceLinkConsentDialog(
    appState: WhiteNoiseAppState,
    watcher: DeviceLinkRequestWatcher,
    request: DeviceLinkRequest,
    prompt: DeviceLinkPromptFfi,
    canRespond: () -> Boolean,
    onReport: () -> Unit,
    content: @Composable (canApprove: () -> Boolean) -> Unit,
) {
    val shownAt = rememberShownUptime()
    Dialog(
        onDismissRequest = watcher::close,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        ProtectDialogInput()
        Box(Modifier.ignoringTouchesBefore(shownAt)) {
            if (request.repeated) {
                content { false }
            } else {
                val accountId = appState.accounts.firstOrNull { it.label == request.accountRef }?.accountIdHex
                DeviceLinkConsentGate(
                    prompt = prompt,
                    profile =
                        DeviceLinkConsentProfile(
                            appState.profileName(request.accountRef),
                            accountId ?: request.accountRef,
                            accountId?.let(appState::avatarUrl),
                        ),
                    stillCurrent = {
                        canRespond() &&
                            watcher.queue.presented?.repeated == false &&
                            watcher.presentedPrompt?.state == prompt.state
                    },
                    onClose = watcher::close,
                    onReport = onReport,
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun DeviceLinkConsentGate(
    prompt: DeviceLinkPromptFfi,
    profile: DeviceLinkConsentProfile,
    stillCurrent: () -> Boolean,
    onClose: () -> Unit,
    onReport: () -> Unit,
    content: @Composable (canApprove: () -> Boolean) -> Unit,
) {
    val comparing = prompt.state as? DeviceLinkPromptStateFfi.Comparing
    if (comparing == null) {
        content { false }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = context.findActivity()
    val device = remember(activity, prompt.promptId, comparing.code) { DeviceLinkCredentialPrompt(activity) }
    DisposableEffect(device) { onDispose(device::cancel) }
    var verified by remember(prompt.promptId, comparing.code) { mutableStateOf(false) }
    var error by remember(prompt.promptId, comparing.code) { mutableStateOf<Int?>(null) }
    val audio = context.getSystemService(AudioManager::class.java)
    var call by rememberLinkCallWarning(audio, lifecycle, verified, prompt.promptId)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        device.cancel()
        verified = false
        call = LinkCallWarning(onCall = false, warnedAt = 0L, canContinue = true)
    }
    val canReveal = {
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && stillCurrent()
    }
    if (verified) {
        content { verified && canReveal() }
    } else {
        DeviceLinkConsentPage(
            profile = profile,
            time = IdentityFormatter.clockTime(prompt.requestedAt),
            onCall = call.onCall,
            canContinue = call.canContinue,
            error = error,
            onContinue = {
                val currentCall = linkCallWarning(audio, call)
                call = currentCall
                if (currentCall.canContinue && canReveal()) {
                    device.request { outcome ->
                        if (canReveal()) {
                            verified = outcome == CredentialOutcome.Verified
                            error =
                                R.string.link_consent_lock_required.takeIf {
                                    outcome == CredentialOutcome.Unavailable
                                }
                        }
                    }
                }
            },
            onClose = onClose,
            onReport = onReport,
        )
    }
}

@Composable
private fun rememberLinkCallWarning(
    audio: AudioManager?,
    lifecycle: Lifecycle,
    verified: Boolean,
    promptId: String,
): MutableState<LinkCallWarning> {
    val call = remember(promptId) { mutableStateOf(linkCallWarning(audio)) }
    LaunchedEffect(audio, lifecycle, verified, promptId) {
        if (!verified) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    call.value = linkCallWarning(audio, call.value)
                    delay(CALL_MODE_CHECK_MS)
                }
            }
        }
    }
    return call
}

/** Keeps a report in flight across dialog recreation and opens Devices only for the same request. */
internal fun launchLinkReport(
    appState: WhiteNoiseAppState,
    watcher: DeviceLinkRequestWatcher,
    request: DeviceLinkRequest,
    generation: Int,
    reporting: MutableState<Boolean>,
    onReviewed: () -> Unit,
) {
    if (reporting.value) return
    reporting.value = true
    appState.launchMutation {
        try {
            if (reportLinkRequest(appState, watcher, request, generation)) {
                val current = watcher.queue.presented
                if (current == null || current.id == request.id) {
                    onReviewed()
                    appState.present(R.string.link_consent_report_failed)
                }
            }
        } finally {
            reporting.value = false
        }
    }
}

/**
 * Declines the exact native prompt. MDK needs a prompt-scoped Reject action before Android can safely record
 * which sign-in was unrecognized, so send the user to Devices for that separate review (MDK #3684).
 */
private suspend fun reportLinkRequest(
    appState: WhiteNoiseAppState,
    watcher: DeviceLinkRequestWatcher,
    request: DeviceLinkRequest,
    generation: Int,
): Boolean {
    fun ownsAccount() = appState.activeAccountRef == request.accountRef && appState.runtimeGeneration == generation
    if (!ownsAccount() || watcher.blocked || watcher.queue.presented?.id != request.id) return false
    val previous = watcher.snapshots[request.accountRef]
    val result =
        runCatchingCancellable { appState.marmotIo { declineDeviceLink(request.accountRef, request.promptId) } }
    return if (ownsAccount() && !watcher.blocked) {
        result.fold(
            onSuccess = { snapshot -> watcher.accept(request.accountRef, snapshot, beforeCommand = previous) },
            onFailure = {
                if (watcher.queue.presented?.id == request.id) {
                    appState.presentFailure(R.string.link_device_action_failed, "DEVICE_LINK", it)
                }
            },
        )
        if (result.isSuccess) watcher.reread(request.accountRef)
        result.isSuccess && ownsAccount() && !watcher.blocked
    } else {
        false
    }
}

@Suppress("LongParameterList")
@Composable
internal fun DeviceLinkConsentPage(
    profile: DeviceLinkConsentProfile,
    time: String,
    onCall: Boolean,
    canContinue: Boolean,
    error: Int?,
    onContinue: () -> Unit,
    onClose: () -> Unit,
    onReport: () -> Unit,
) {
    WhiteNoiseScaffold(
        topBar = {
            WhiteNoiseTopBar(
                title = stringResource(R.string.link_request_title),
                onBack = onClose,
                navigation = WhiteNoiseTopBarNavigation.Close,
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .whiteNoiseVerticalScroll()
                    .padding(WhiteNoiseSpacing.CompactScreenMargin),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(title = profile.name, seed = profile.seed, size = 40.dp, pictureUrl = profile.avatarUrl)
                Column {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.link_consent_requested_at, time),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(stringResource(R.string.link_consent_access))
            Text(stringResource(R.string.link_consent_have_device))
            Text(stringResource(R.string.link_consent_scam))
            if (onCall) {
                Text(stringResource(R.string.link_consent_on_call), color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            WhiteNoiseButton(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth().testTag(DEVICE_LINK_WITH_ME_TAG),
            ) { Text(stringResource(R.string.link_consent_with_me)) }
            TextButton(
                onClick = onReport,
                modifier = Modifier.fillMaxWidth().testTag(DEVICE_LINK_DID_NOT_START_TAG),
            ) { Text(stringResource(R.string.link_consent_did_not_start)) }
            TextButton(
                onClick = onReport,
                modifier = Modifier.fillMaxWidth().testTag(DEVICE_LINK_SOMEONE_ASKED_TAG),
            ) { Text(stringResource(R.string.link_consent_someone_asked)) }
        }
    }
}

private data class LinkCallWarning(
    val onCall: Boolean,
    val warnedAt: Long,
    val canContinue: Boolean,
)

private fun linkCallWarning(
    audio: AudioManager?,
    previous: LinkCallWarning? = null,
): LinkCallWarning {
    val onCall = audio.isInCall()
    val now = SystemClock.elapsedRealtime()
    val warnedAt = previous?.takeIf { onCall && it.onCall }?.warnedAt ?: now
    return LinkCallWarning(onCall, warnedAt, !onCall || now - warnedAt >= CALL_CONSENT_DELAY_MS)
}

private fun AudioManager?.isInCall(): Boolean =
    when (this?.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }

private enum class CredentialOutcome {
    Verified,
    Cancelled,
    Unavailable,
}

/** A system challenge scoped to this presentation. A retired or superseded callback cannot reveal a code. */
private class DeviceLinkCredentialPrompt(
    private val activity: Activity?,
) {
    private var active: CancellationSignal? = null

    fun request(onResult: (CredentialOutcome) -> Unit) {
        if (active != null) return
        val host = activity?.takeUnless { it.isFinishing || it.isDestroyed }
        if (host?.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            onResult(CredentialOutcome.Unavailable)
            return
        }
        val session = CancellationSignal()
        active = session

        fun finish(result: CredentialOutcome) {
            if (active !== session) return
            active = null
            onResult(result)
        }
        runCatching {
            BiometricPrompt
                .Builder(host)
                .setTitle(host.getString(R.string.link_consent_unlock))
                .setAllowedAuthenticators(DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    session,
                    host.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val credential = BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
                            val outcome =
                                if (result.authenticationType == credential) {
                                    CredentialOutcome.Verified
                                } else {
                                    CredentialOutcome.Cancelled
                                }
                            finish(outcome)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            finish(
                                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                                    CredentialOutcome.Unavailable
                                } else {
                                    CredentialOutcome.Cancelled
                                },
                            )
                        }
                    },
                )
        }.onFailure { finish(CredentialOutcome.Unavailable) }
    }

    fun cancel() {
        val session = active
        active = null
        session?.cancel()
    }
}
