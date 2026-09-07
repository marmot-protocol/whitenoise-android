package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingSubscription
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Verifies the concrete adapter uses the native retry/approval APIs, rather than only testing a fake client. */
class MarmotAccountSetupClientTest {
    /** A cancellation during the IO dispatch must release the native result that withContext cannot deliver. */
    @Test
    fun cancellationDuringSubscriptionAcquisitionClosesTheUndeliveredNativeHandle() =
        runTest {
            val closed = AtomicBoolean()
            val subscription =
                object : OnboardingSubscription(NoPointer) {
                    override fun snapshot() = setupSnapshot()

                    override fun close() {
                        closed.set(true)
                    }
                }
            lateinit var owner: Job
            val marmot =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, _ ->
                    check(method.name == "subscribeOnboarding")
                    owner.cancel()
                    subscription
                } as MarmotInterface
            owner =
                launch(start = CoroutineStart.LAZY) {
                    MarmotAccountSetupClient(marmot, SETUP_TEST_ACCOUNT) {}.subscribe()
                }
            owner.start()
            owner.join()
            assertTrue(closed.get())
        }

    @Test
    fun signerReconnectRetriesTheWaitingStepInsteadOfRunningAnUnchangedCheckpoint() =
        runTest {
            val calls = mutableListOf<String>()
            val marmot =
                nativeBoundary { name, arguments ->
                    calls += name
                    assertEquals(SETUP_TEST_ACCOUNT, arguments.first())
                    if (name == "retryOnboardingStep") assertEquals(OnboardingStepFfi.KEY_PACKAGE, arguments[1])
                }
            val client = MarmotAccountSetupClient(marmot, SETUP_TEST_ACCOUNT) { calls += "reattach-signer" }
            client.execute(SetupRequest(3uL, OnboardingStepFfi.KEY_PACKAGE, OnboardingActionFfi.RECONNECT_SIGNER))
            assertEquals(listOf("reattach-signer", "retryOnboardingStep"), calls)
        }

    @Test
    fun approvalForwardsTheRenderedProposalRevision() =
        runTest {
            val calls = mutableListOf<String>()
            val marmot =
                nativeBoundary { name, arguments ->
                    calls += name
                    assertEquals(SETUP_TEST_ACCOUNT, arguments.first())
                    assertEquals(7uL, (arguments[1] as Long).toULong())
                }
            MarmotAccountSetupClient(marmot, SETUP_TEST_ACCOUNT) {}.execute(
                SetupRequest(7uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.APPROVE_REPAIR),
            )
            assertEquals(listOf("approveOnboardingRepair"), calls)
        }

    private fun nativeBoundary(onCall: (String, Array<out Any?>) -> Unit): MarmotInterface {
        val nativeType = MarmotInterface::class.java
        return Proxy.newProxyInstance(nativeType.classLoader, arrayOf(nativeType)) { _, method, arguments ->
            onCall(method.name.substringBefore('-'), arguments.orEmpty())
            setupSnapshot()
        } as MarmotInterface
    }
}
