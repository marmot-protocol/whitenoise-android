package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingSubscription
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single native subscription, released only after its reader has been cancelled and drained. */
internal interface AccountSetupSubscription : AutoCloseable {
    fun snapshot(): OnboardingSnapshotFfi

    suspend fun next(): OnboardingSnapshotFfi?
}

/** Account-scoped protocol boundary; Android never persists a second setup journal. */
internal interface AccountSetupClient {
    suspend fun snapshot(): OnboardingSnapshotFfi?

    suspend fun subscribe(): AccountSetupSubscription

    suspend fun run(): OnboardingSnapshotFfi

    suspend fun execute(request: SetupRequest): OnboardingSnapshotFfi?

    suspend fun profile(): UserProfileMetadataFfi?
}

/** The revision belongs to the displayed decision, not the latest asynchronously received state. */
internal data class SetupRequest(
    val revision: ULong,
    val step: OnboardingStepFfi,
    val action: OnboardingActionFfi,
    val readRelays: List<String> = emptyList(),
    val writeRelays: List<String> = emptyList(),
    val profile: UserProfileMetadataFfi? = null,
)

/** Holds one runtime instance for its entire lifetime; replacement runtimes get a new client. */
internal class MarmotAccountSetupClient(
    private val marmot: MarmotInterface,
    private val account: String,
    private val reconnectSigner: suspend () -> Unit,
) : AccountSetupClient {
    override suspend fun snapshot(): OnboardingSnapshotFfi? =
        withContext(Dispatchers.IO) {
            marmot.onboardingSnapshot(account)
        }

    override suspend fun subscribe(): AccountSetupSubscription {
        var acquired: OnboardingSubscription? = null
        var delivered = false
        try {
            val result =
                withContext(Dispatchers.IO) {
                    val subscription = marmot.subscribeOnboarding(account).also { acquired = it }
                    val initial = subscription.snapshot()
                    object : AccountSetupSubscription {
                        override fun snapshot() = initial

                        override suspend fun next() = subscription.next()

                        override fun close() = subscription.close()
                    }
                }
            delivered = true
            return result
        } finally {
            // withContext can discard its result when the caller is cancelled during dispatch back.
            if (!delivered) acquired?.close()
        }
    }

    override suspend fun run(): OnboardingSnapshotFfi = withContext(Dispatchers.IO) { marmot.runOnboarding(account) }

    override suspend fun profile(): UserProfileMetadataFfi? =
        withContext(Dispatchers.IO) {
            marmot.userProfile(account)
        }

    override suspend fun execute(request: SetupRequest): OnboardingSnapshotFfi? =
        withContext(Dispatchers.IO) {
            with(request) {
                when (action) {
                    OnboardingActionFfi.RETRY -> marmot.retryOnboardingStep(account, step)
                    OnboardingActionFfi.CONTINUE_WITHOUT -> marmot.continueOnboardingWithout(account, step)
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS ->
                        marmot.proposeOnboardingRecommendedRelays(
                            account,
                            step,
                        )
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
                    OnboardingActionFfi.APPROVE_REPAIR -> marmot.approveOnboardingRepair(account, revision)
                    OnboardingActionFfi.CANCEL_REPAIR -> marmot.cancelOnboardingRepair(account)
                    OnboardingActionFfi.CONTINUE_ANYWAY -> marmot.acknowledgeOnboardingSingleDevice(account, revision)
                    OnboardingActionFfi.RECONNECT_SIGNER -> {
                        reconnectSigner()
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
}
