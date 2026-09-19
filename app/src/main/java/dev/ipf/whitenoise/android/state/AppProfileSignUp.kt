package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.ui.onboarding.SignUpController
import dev.ipf.whitenoise.android.ui.onboarding.SignUpOwner
import dev.ipf.whitenoise.android.ui.onboarding.SignUpStage
import kotlinx.coroutines.launch

/**
 * Binds the process-owned profile form to native account creation and publication.
 * Private account activation, privacy configuration and phase mutation stay owned by AppState.
 */
internal class AppProfileSignUp(
    private val appState: WhiteNoiseAppState,
    private val activateCreatedIdentity: (AccountSummaryFfi) -> Unit,
    private val configurePrivacyRuntime: suspend () -> Unit,
    private val warmProfile: (String) -> Unit,
    private val markReady: () -> Unit,
) {
    /** Accepted native receipt survives recreation; only this adapter can replace it. */
    var pending by mutableStateOf<SignUpController?>(null)
        private set

    /** An accepted but unfinished form remains visible even after bootstrap resumes Ready. */
    val forPresentation: SignUpController?
        get() = pending?.takeIf { it.canPresent() }

    /** Opens a draft without native work, preserving busy or still-owned existing attempts. */
    fun begin() {
        val existing = pending
        if (existing != null && (existing.stage.busy || existing.canPresent())) return
        pending =
            SignUpController(
                scope = appState.mutationsScope,
                currentOwner = { SignUpOwner(appState.runtimeGeneration, appState.activeAccountRef) },
                ownerAvailable = ::ownerAvailable,
                create = {
                    runCatchingCancellable {
                        appState.marmotIo { createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays) }
                    }.onFailure {
                        appState.presentFailure(R.string.toast_couldnt_create_identity, "IDENTITY_CREATE", it)
                    }.getOrThrow()
                },
                accept = ::accept,
                upload = { account, image ->
                    runCatchingCancellable {
                        appState.marmotIo { uploadProfileImage(account, image.plaintext, image.mediaType, null) }
                    }.onFailure {
                        appState.presentFailure(R.string.toast_couldnt_upload_profile_image, "PROFILE_IMAGE_UPLOAD", it)
                    }.getOrThrow()
                },
                publish = { account, metadata ->
                    if (appState.activeAccountRef == account && ownerAvailable()) {
                        appState.publishProfile(metadata)
                    } else {
                        false
                    }
                },
                finish = ::finish,
            )
    }

    /** Teardown and retained-account reactivation cannot acquire the profile form's ownership. */
    private fun ownerAvailable(): Boolean {
        val tearingDown = appState.signOutInProgress || appState.wipeInProgress
        return !tearingDown && appState.retainedAccountReactivationRef == null
    }

    /** A late creation is refreshed into the account list without stealing the selected identity. */
    private fun accept(
        summary: AccountSummaryFfi,
        owner: SignUpOwner,
    ): Boolean {
        val ownsSource = appState.runtimeGeneration == owner.runtime && appState.activeAccountRef == owner.accountRef
        return if (ownsSource && ownerAvailable()) {
            activateCreatedIdentity(summary)
            true
        } else {
            appState.launchMutation { appState.refreshAccounts() }
            false
        }
    }

    /** Only the accepted identity in the original runtime may complete the form and enter Ready. */
    private fun finish(
        summary: AccountSummaryFfi,
        owner: SignUpOwner,
    ): Boolean {
        val ownsAccepted = appState.runtimeGeneration == owner.runtime && appState.activeAccountRef == summary.label
        return if (ownsAccepted && ownerAvailable()) {
            markReady()
            appState.presentTransient(R.string.toast_identity_created)
            launchIdentityPostCreateWarmup(summary)
            true
        } else {
            false
        }
    }

    /** Discards unsubmitted or stale presentation without pretending native accepted work was undone. */
    fun dismiss(): Boolean {
        val controller = pending ?: return true
        val discarded = controller.stage == SignUpStage.OwnerChanged || controller.discardUnsubmitted()
        if (discarded) pending = null
        return discarded
    }

    /** Enriches an accepted identity through the same best-effort, account-scoped legacy warmup. */
    fun launchIdentityPostCreateWarmup(summary: AccountSummaryFfi) {
        appState.mutationsScope.launch {
            runBestEffortPostCommitSteps(
                steps =
                    listOf(
                        "refresh-accounts" to { appState.refreshAccounts() },
                        "configure-privacy-runtime" to {
                            if (appState.activeAccountRef == summary.label) configurePrivacyRuntime()
                        },
                        "refresh-notification-settings" to {
                            if (appState.activeAccountRef == summary.label) appState.refreshLocalNotificationSettings()
                        },
                        "warm-profile" to {
                            if (appState.activeAccountRef == summary.label) warmProfile(summary.accountIdHex)
                        },
                        "sync-push-registration" to {
                            if (appState.activeAccountRef == summary.label) {
                                appState.syncNativePushRegistrationIfEnabled()
                            }
                        },
                    ),
                onFailure = { step, error ->
                    appStateDebug(error) {
                        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                        "post-create $step failed: $detail"
                    }
                },
            )
        }
    }
}
