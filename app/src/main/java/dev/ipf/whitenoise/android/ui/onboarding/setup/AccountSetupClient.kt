package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingSubscription
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A single native subscription, released only after its reader has been cancelled and drained. */
internal interface AccountSetupSubscription {
    /** Returns the subscription’s initial checkpoint without advancing its update stream. */
    fun snapshot(): OnboardingSnapshotFfi

    /** Suspends for the next native checkpoint and returns null when the stream ends. */
    suspend fun next(): OnboardingSnapshotFfi?

    /** Releases native resources off the UI dispatcher, including during reader cancellation. */
    suspend fun close()
}

/** Account-scoped protocol boundary; Android never persists a second setup journal. */
internal interface AccountSetupClient {
    /** Reads the authoritative checkpoint, or null for an account without interactive setup. */
    suspend fun snapshot(): OnboardingSnapshotFfi?

    /** Acquires an owned update stream that the controller must eventually close. */
    suspend fun subscribe(): AccountSetupSubscription

    /** Advances automatic checks until MDK requires a user decision. */
    suspend fun run(): OnboardingSnapshotFfi

    /** Dispatches a user decision with its reviewed proposal data and revision. */
    suspend fun execute(request: SetupRequest): OnboardingSnapshotFfi?

    /** Creates an unapproved relay preview without advancing optional setup decisions. */
    suspend fun previewRelayRepair(step: OnboardingStepFfi): OnboardingSnapshotFfi

    /** Releases only this exact undisplayed preview; native clients keep its read and cancel in one owned gate. */
    suspend fun discardOwnedRelayPreview(preview: OnboardingSnapshotFfi) = discardCurrentPreview(preview)

    /** Loads existing metadata so profile edits can preserve untouched fields. */
    suspend fun profile(): UserProfileMetadataFfi?
}

/** The revision and recovery epoch identify the displayed decision, even when native state advances. */
internal data class SetupRequest(
    val revision: ULong,
    val step: OnboardingStepFfi,
    val action: OnboardingActionFfi,
    val readRelays: List<String> = emptyList(),
    val writeRelays: List<String> = emptyList(),
    val profile: UserProfileMetadataFfi? = null,
    val recoveryEpoch: String? = null,
    val resetRelays: Boolean = false,
)

/** Holds one runtime instance for its entire lifetime; replacement runtimes get a new client. */
internal class MarmotAccountSetupClient(
    private val marmot: MarmotInterface,
    private val account: String,
    private val withLifetime: suspend (suspend () -> OnboardingSnapshotFfi?) -> OnboardingSnapshotFfi? = { it() },
    private val withOwnedPreviewCleanup:
        suspend (suspend () -> OnboardingSnapshotFfi?) -> OnboardingSnapshotFfi? = withLifetime,
    private val reconnectSigner: suspend () -> Unit,
) : AccountSetupClient {
    private val defaults = AccountSetupDefaults(marmot, account)

    /** Reads native setup state away from the UI dispatcher. */
    override suspend fun snapshot(): OnboardingSnapshotFfi? =
        withContext(Dispatchers.IO) {
            marmot.onboardingSnapshot(account)
        }

    /** Acquires the native stream on IO and releases it if cancellation discards delivery. */
    override suspend fun subscribe(): AccountSetupSubscription {
        var acquired: OnboardingSubscription? = null
        var delivered = false
        try {
            val result =
                withContext(Dispatchers.IO) {
                    val subscription = marmot.subscribeOnboarding(account).also { acquired = it }
                    val initial = subscription.snapshot()
                    object : AccountSetupSubscription {
                        /** Returns the initial snapshot captured when the native subscription was acquired. */
                        override fun snapshot() = initial

                        /**
                         * Forwards the cancellable native update stream without storing a second checkpoint
                         * history.
                         */
                        override suspend fun next() = withContext(Dispatchers.IO) { subscription.next() }

                        /** Releases the acquired native stream after the controller has drained its reader. */
                        override suspend fun close() {
                            withContext(NonCancellable + Dispatchers.IO) { subscription.close() }
                        }
                    }
                }
            delivered = true
            return result
        } finally {
            // withContext can discard its result when the caller is cancelled during dispatch back.
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) { acquired?.close() }
        }
    }

    /** Runs native preflight away from the UI dispatcher. */
    override suspend fun run(): OnboardingSnapshotFfi =
        checkNotNull(
            withLifetime {
                withContext(Dispatchers.IO) { defaults.advance(marmot.runOnboarding(account)) }
            },
        )

    /** Loads metadata on IO before constructing a profile-edit proposal. */
    override suspend fun profile(): UserProfileMetadataFfi? =
        withContext(Dispatchers.IO) {
            marmot.userProfile(account)
        }

    /** Executes explicit decisions, then advances optional follows. */
    override suspend fun execute(request: SetupRequest): OnboardingSnapshotFfi? {
        // The signer UI waits outside the native lifetime; its registration adapter rechecks the runtime.
        if (request.action == OnboardingActionFfi.RECONNECT_SIGNER) {
            withLifetime { null }
            reconnectSigner()
        }
        return withLifetime {
            withContext(Dispatchers.IO) {
                val result = dispatch(request)
                val cancelledRelayDraft =
                    request.action == OnboardingActionFfi.CANCEL_REPAIR &&
                        request.step in setOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS)
                if (result == null || cancelledRelayDraft) result else defaults.advance(result)
            }
        }
    }

    /** Retains ownership until IO delivery succeeds, cleaning up previews discarded by cancellation. */
    override suspend fun previewRelayRepair(step: OnboardingStepFfi): OnboardingSnapshotFfi {
        var acquired: OnboardingSnapshotFfi? = null
        var delivered = false
        var failure: Exception? = null
        try {
            val preview =
                checkNotNull(
                    withLifetime {
                        withContext(Dispatchers.IO) {
                            marmot.proposeOnboardingRelayRepair(account, step).also { acquired = it }
                        }
                    },
                )
            delivered = true
            return preview
        } catch (expectedFailure: Exception) {
            failure = expectedFailure
            throw expectedFailure
        } finally {
            if (!delivered) acquired?.let { discardRelayPreview(it, failure) }
        }
    }

    /** A draining controller may discard its own preview, but never a newer native proposal. */
    override suspend fun discardOwnedRelayPreview(preview: OnboardingSnapshotFfi): OnboardingSnapshotFfi? =
        withOwnedPreviewCleanup {
            withContext(Dispatchers.IO) {
                val latest = marmot.onboardingSnapshot(account)
                if (preview.accountIdHex == account && latest != null && preview.isCurrentRelayPreview(latest)) {
                    marmot.cancelOnboardingRepair(account)
                } else {
                    null
                }
            }
        }

    /** Keeps full replacement an explicit action separate from the default native minimal preview. */
    private suspend fun proposeRelayRepair(request: SetupRequest): OnboardingSnapshotFfi =
        if (request.resetRelays) {
            marmot.proposeOnboardingRelays(
                account,
                request.step,
                if (request.step == OnboardingStepFfi.INBOX_RELAYS) {
                    MarmotClient.bootstrapRelays
                } else {
                    MarmotClient.accountRelays
                },
                if (request.step == OnboardingStepFfi.RELAYS) MarmotClient.accountRelays else emptyList(),
            )
        } else {
            marmot.proposeOnboardingRelayRepair(account, request.step)
        }

    /** Maps each explicit decision to the published native command. */
    private suspend fun dispatch(request: SetupRequest): OnboardingSnapshotFfi? =
        with(request) {
            when (action) {
                OnboardingActionFfi.RETRY -> marmot.retryOnboardingStep(account, step)
                OnboardingActionFfi.CONTINUE_WITHOUT -> marmot.continueOnboardingWithout(account, step)
                // A preview never signs; reset is a separately reviewed, explicit full replacement.
                OnboardingActionFfi.USE_RECOMMENDED_RELAYS ->
                    proposeRelayRepair(request)
                OnboardingActionFfi.EDIT_RELAYS ->
                    marmot.proposeOnboardingRelays(
                        account,
                        step,
                        readRelays,
                        writeRelays,
                    )
                OnboardingActionFfi.EDIT_DISCOVERY_RELAYS ->
                    marmot.setOnboardingDiscoveryRelays(
                        account,
                        readRelays,
                    )
                OnboardingActionFfi.EDIT_PROFILE ->
                    marmot.proposeOnboardingProfile(
                        account,
                        requireNotNull(profile),
                    )
                OnboardingActionFfi.APPROVE_REPAIR -> marmot.approveSetupRepair(account, revision, recoveryEpoch)
                OnboardingActionFfi.CANCEL_REPAIR -> marmot.cancelOnboardingRepair(account)
                OnboardingActionFfi.CONTINUE_ANYWAY ->
                    marmot.acknowledgeSetupSingleDevice(account, revision, recoveryEpoch)
                OnboardingActionFfi.RECONNECT_SIGNER -> {
                    marmot.retryOnboardingStep(account, step)
                }
                OnboardingActionFfi.CANCEL_ONBOARDING -> {
                    marmot.cancelOnboarding(account)
                    null
                }
                OnboardingActionFfi.EDIT_FOLLOWS ->
                    error("Follow-list publication is not part of imported-account setup")
            }
        }
}
