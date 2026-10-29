package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
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
    private val activateCreatedIdentity: suspend (AccountSummaryFfi, () -> Boolean) -> Unit,
    private val configurePrivacyRuntime: suspend () -> Unit,
    private val warmProfile: (String) -> Unit,
    private val markReady: () -> Unit,
) {
    private val directIdentityQualification = IdentityPolicyQualification()
    private val profileIdentityCoordinator = SignUpIdentityCoordinator()

    private data class CreatingRuntime(
        val native: dev.ipf.marmotkit.MarmotInterface,
        val epoch: Int,
    )

    private val creatingRuntimes = java.util.IdentityHashMap<AccountSummaryFfi, CreatingRuntime>()

    private fun ownsCreatingRuntime(summary: AccountSummaryFfi): Boolean {
        val owner = creatingRuntimes[summary] ?: return false
        return appState.runtimeGeneration == owner.epoch && appState.marmot() === owner.native
    }

    /** Creates under the captured runtime and selection; a form [initialProfile] is the first published profile. */
    private suspend fun createOwnedIdentity(
        expectedOwner: SignUpOwner,
        initialProfile: UserProfileMetadataFfi? = null,
    ): AccountSummaryFfi {
        val owner = CreatingRuntime(appState.marmot(), appState.runtimeGeneration)
        return appState.withCurrentAccountLifetime {
            val runtimeCurrent = appState.runtimeGeneration == owner.epoch && appState.marmot() === owner.native
            if (!runtimeCurrent) throw kotlinx.coroutines.CancellationException("Creating runtime was replaced")
            if (expectedOwner.runtime != owner.epoch ||
                expectedOwner.accountRef != appState.activeAccountRef ||
                !appState.accountSession.handoff.isCurrent(expectedOwner.selection)
            ) {
                throw kotlinx.coroutines.CancellationException("Creating runtime was replaced")
            }
            appState
                .marmotIo {
                    if (initialProfile == null) {
                        createIdentityWithBootstrapRelays()
                    } else {
                        createNamedIdentity(initialProfile)
                    }
                }.also { creatingRuntimes[it] = owner }
        }
    }

    /**
     * Contains the retained receipt and, for a local-ready profile receipt, waits for its relay publication.
     * Both repeat for the same receipt on Retry, so a failed wait never discards it or creates another identity.
     */
    private suspend fun qualifyOwnedIdentity(
        summary: AccountSummaryFfi,
        awaitPublication: Boolean = false,
    ) = appState.withAccountLifetime {
        if (!ownsCreatingRuntime(summary)) {
            throw kotlinx.coroutines.CancellationException("Creating runtime was replaced")
        }
        appState.marmotIo {
            enforceAppOwnedAttachmentAcquisitionPolicy(listOf(summary.label))
            if (awaitPublication) awaitAccountNetworkReady(summary.label) { accountSetupReadiness(it) }
        }
    }

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
                currentOwner = {
                    SignUpOwner(
                        appState.runtimeGeneration,
                        appState.activeAccountRef,
                        appState.accountSession.handoff.capture(),
                    )
                },
                ownerAvailable = ::ownerAvailable,
                create = { owner, profile ->
                    profileIdentityCoordinator.createOrReuse(owner) {
                        runCatchingCancellable {
                            createOwnedIdentity(owner, profile)
                        }.onFailure {
                            appState.presentFailure(R.string.toast_couldnt_create_identity, "IDENTITY_CREATE", it)
                        }.getOrThrow()
                    }
                },
                hasPendingIdentityReceipt = profileIdentityCoordinator::hasPendingReceipt,
                qualify = { account ->
                    runCatchingCancellable {
                        qualifyOwnedIdentity(account, awaitPublication = true)
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

    /** Creates a policy-qualified identity for the legacy no-profile action without duplicating a retained receipt. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun createIdentityWithoutProfile() {
        val requestedSelection = appState.accountSession.handoff.capture()
        val requestedOwner = SignUpOwner(appState.runtimeGeneration, appState.activeAccountRef, requestedSelection)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val summary =
                directIdentityQualification.createQualifyAndAccept(
                    create = { createOwnedIdentity(requestedOwner) },
                    qualify = { account ->
                        qualifyOwnedIdentity(account)
                    },
                    accept = { summary ->
                        if (!appState.accountSession.handoff.isCurrent(requestedSelection)) {
                            throw kotlinx.coroutines.CancellationException("Identity creation superseded")
                        }
                        activateCreatedIdentity(summary) { ownsCreatingRuntime(summary) }
                    },
                )
            markReady()
            appState.presentTransient(R.string.toast_identity_created)
            appStateDebug { "identity engine setup returned in ${SystemClock.elapsedRealtime() - startedAt}ms" }
            launchIdentityPostCreateWarmup(summary)
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            appState.presentFailure(R.string.toast_couldnt_create_identity, "IDENTITY_CREATE", error)
        }
    }

    /** Teardown and retained-account reactivation cannot acquire the profile form's ownership. */
    private fun ownerAvailable(): Boolean {
        val tearingDown = appState.signOutInProgress || appState.wipeInProgress
        return !tearingDown && appState.retainedAccountReactivationRef == null
    }

    /** A late creation is refreshed into the account list without stealing the selected identity. */
    private suspend fun accept(
        summary: AccountSummaryFfi,
        @Suppress("UNUSED_PARAMETER") controllerOwner: SignUpOwner,
    ): Boolean =
        profileIdentityCoordinator.reconcile(summary) { creationOwner ->
            val ownsSource =
                appState.runtimeGeneration == creationOwner.runtime &&
                    appState.activeAccountRef == creationOwner.accountRef &&
                    appState.accountSession.handoff.isCurrent(creationOwner.selection)
            if (ownsSource && ownsCreatingRuntime(summary) && ownerAvailable()) {
                activateCreatedIdentity(summary) { ownsCreatingRuntime(summary) }
                SignUpIdentityReconciliation(receiptHandled = true, activated = true)
            } else {
                appState.refreshAccounts()
                SignUpIdentityReconciliation(receiptHandled = true, activated = false)
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
