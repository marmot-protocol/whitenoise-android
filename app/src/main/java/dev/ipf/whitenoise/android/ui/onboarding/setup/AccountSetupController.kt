package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen-only drafts survive rotation and signer handoff within this process; MDK owns all durable progress. */
internal data class SetupEditor(
    val revision: ULong,
    val step: OnboardingStepFfi,
    val action: OnboardingActionFfi,
    val reads: String = "",
    val writes: String = "",
    val displayName: String = "",
    val about: String = "",
    val originalProfile: UserProfileMetadataFfi? = null,
    val displayNameEdited: Boolean = false,
    val aboutEdited: Boolean = false,
)

/** Immutable presentation envelope around the authoritative native snapshot. */
internal data class AccountSetupState(
    val snapshot: OnboardingSnapshotFfi? = null,
    val busy: Boolean = false,
    val disconnected: Boolean = false,
    val error: Boolean = false,
    val staleDecision: Boolean = false,
    val editor: SetupEditor? = null,
    val detailsExpanded: Boolean = false,
) {
    val accountLabel: String
        get() = snapshot?.accountIdHex?.let { it.take(12) + "…" + it.takeLast(8) }.orEmpty()

    val completedSteps: Int
        get() =
            snapshot?.steps?.count {
                it.status == OnboardingStatusFfi.PASSED || it.status == OnboardingStatusFfi.SKIPPED
            } ?: 0

    val currentStep: OnboardingStepStateFfi?
        get() {
            val current = snapshot ?: return null
            val cancellation =
                current.steps.firstOrNull {
                    current.cancellationPending && OnboardingActionFfi.CANCEL_ONBOARDING in it.actions
                }
            return cancellation ?: current.steps.firstOrNull {
                it.status != OnboardingStatusFfi.PASSED && it.status != OnboardingStatusFfi.SKIPPED
            }
        }
}

/** Serializes user decisions and fences callbacks to one account and runtime generation. */
internal class AccountSetupController(
    val account: String,
    private val client: AccountSetupClient,
    parentScope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val onReady: suspend () -> Unit,
    private val onCancelled: () -> Unit,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(AccountSetupState())
    val state = mutableState.asStateFlow()
    private var reader: Job? = null
    private val automation = AccountSetupAutomation()
    private var readinessDelivered = false

    init {
        scope.launch {
            state.collect { current ->
                if (isCurrent()) automation.advance(current, ::openChats)
            }
        }
    }

    /** Keeps diagnostics and secondary controls out of the default decision screen. */
    fun toggleDetails() {
        mutableState.value = mutableState.value.copy(detailsExpanded = !mutableState.value.detailsExpanded)
    }

    /** Cancels and drains commands/readers before the owning runtime can be released. */
    suspend fun close() {
        lifetime.cancelAndJoin()
    }

    /** Reloads a terminated stream, preserving any unapproved proposal exactly as MDK saved it. */
    fun reconnect() =
        operate {
            attach()
            accept(client.run())
        }

    /** Rechecks readiness even when the ready stream already terminated. */
    fun openChats() =
        operate {
            val latest = client.snapshot()
            if (latest != null) accept(latest)
            val readyForThisAccount =
                latest?.let {
                    it.ready &&
                        it.accountIdHex == account &&
                        !it.cancellationPending &&
                        it.revision == mutableState.value.snapshot?.revision
                } == true
            if (readyForThisAccount && isCurrent() && !readinessDelivered) {
                onReady()
                readinessDelivered = true
            }
        }

    /** Rejects repeated taps and stale rendered actions before issuing any protocol mutation. */
    fun submit(request: SetupRequest) {
        if (!mutableState.canAct(request, account, isCurrent())) return
        operate {
            val latest = client.snapshot()
            if (latest == null || !latest.matchesDecision(account, request)) {
                latest?.let(::accept)
                mutableState.value = mutableState.value.copy(staleDecision = true)
            } else {
                val result = client.execute(request)
                if (result == null) {
                    if (isCurrent()) onCancelled()
                } else {
                    accept(result)
                    mutableState.value = mutableState.value.copy(editor = null, detailsExpanded = false)
                }
            }
        }
    }

    /** Loads the existing profile before editing so untouched metadata is retained. */
    fun edit(
        step: OnboardingStepFfi,
        action: OnboardingActionFfi,
        revision: ULong,
    ) {
        val request = SetupRequest(revision, step, action)
        if (!mutableState.canAct(request, account, isCurrent())) return
        operate {
            val existing = mutableState.value.editor
            val profile = if (action == OnboardingActionFfi.EDIT_PROFILE) client.profile() else null
            mutableState.value =
                mutableState.value.copy(
                    editor =
                        existing?.takeIf { it.step == step && it.action == action }?.copy(revision = revision)
                            ?: SetupEditor(
                                revision,
                                step,
                                action,
                                displayName = profile?.displayName ?: profile?.name.orEmpty(),
                                about = profile?.about.orEmpty(),
                                originalProfile = profile,
                            ),
                )
        }
    }

    /** Updates only the draft associated with the currently displayed editor. */
    fun updateEditor(editor: SetupEditor) {
        val previous = mutableState.value.editor ?: return
        if (!mutableState.value.busy && previous.revision == editor.revision) {
            mutableState.value =
                mutableState.value.copy(
                    editor =
                        editor.copy(
                            displayNameEdited =
                                previous.displayNameEdited || editor.displayName != previous.displayName,
                            aboutEdited = previous.aboutEdited || editor.about != previous.about,
                        ),
                )
        }
    }

    /** Closes the editor without changing or publishing any account metadata. */
    fun dismissEditor() {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(editor = null)
    }

    /** Owns one foreground operation at a time without logging keys or raw engine errors. */
    private fun operate(block: suspend () -> Unit) {
        if (!isCurrent() || !lifetime.isActive || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = false, staleDecision = false)
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isCurrent()) mutableState.value = mutableState.value.copy(error = true)
            } finally {
                if (isCurrent()) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    /** Replaces a reader only after its native next call has finished cancellation. */
    private suspend fun attach() {
        reader?.cancelAndJoin()
        val subscription = client.subscribe()
        reader =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    accept(subscription.snapshot())
                    mutableState.value = mutableState.value.copy(disconnected = false)
                    while (isCurrent()) {
                        val next = subscription.next() ?: break
                        accept(next)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (isCurrent()) mutableState.value = mutableState.value.copy(error = true)
                } finally {
                    subscription.close()
                    if (isCurrent()) mutableState.value = mutableState.value.copy(disconnected = true)
                }
            }
    }

    /** Ignores callbacks from another account or an earlier persisted revision. */
    private fun accept(snapshot: OnboardingSnapshotFfi) {
        if (!isCurrent() || snapshot.accountIdHex != account) return
        val current = mutableState.value
        if (snapshot.revision >= (current.snapshot?.revision ?: 0uL)) {
            mutableState.value = current.copy(snapshot = snapshot)
        }
    }
}

/** Grants must match the reviewed recovery epoch as well as the proposal or checkpoint revision. */
private fun OnboardingSnapshotFfi.matchesDecision(
    account: String,
    request: SetupRequest,
): Boolean {
    val actions =
        steps
            .firstOrNull { it.step == request.step }
            ?.actions
            .orEmpty()
    val expectedRevision =
        if (request.action == OnboardingActionFfi.APPROVE_REPAIR) {
            proposal?.takeIf { it.step == request.step }?.revision
        } else {
            revision
        }
    val grantsConsent =
        request.action == OnboardingActionFfi.APPROVE_REPAIR ||
            request.action == OnboardingActionFfi.CONTINUE_ANYWAY
    val epochMatches = !grantsConsent || request.recoveryEpoch == recoveryEpoch
    return accountIdHex == account && request.action in actions && request.revision == expectedRevision && epochMatches
}

/** Converts an editor draft into a proposal request without approving publication. */
internal fun SetupEditor.request(): SetupRequest {
    val editor = this
    val profile = editor.originalProfile ?: UserProfileMetadataFfi(null, null, null, null, null, null, null)
    return SetupRequest(
        editor.revision,
        editor.step,
        editor.action,
        readRelays =
            editor.reads
                .lines()
                .map(String::trim)
                .filter(String::isNotEmpty),
        writeRelays =
            editor.writes
                .lines()
                .map(String::trim)
                .filter(String::isNotEmpty),
        profile =
            profile.copy(
                displayName = if (editor.displayNameEdited) editor.displayName.trim() else profile.displayName,
                about = if (editor.aboutEdited) editor.about.trim() else profile.about,
            ),
    )
}

/** Checks the rendered native decision before entering the controller's single-flight operation. */
private fun MutableStateFlow<AccountSetupState>.canAct(
    request: SetupRequest,
    account: String,
    isCurrent: Boolean,
): Boolean {
    val current = value
    val snapshot = current.snapshot
    if (!isCurrent || current.busy || snapshot == null) return false
    val valid = snapshot.matchesDecision(account, request)
    if (!valid) value = current.copy(staleDecision = true)
    return valid
}
