package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercises preview acquisition and cancellation at the concrete native adapter boundary. */
class MarmotRelayPreviewClientTest {
    /** Even an unexpected checkpoint without a proposal must not trigger automatic follow-list advancement. */
    @Test fun editorPreviewNeverAdvancesDefaults() =
        runTest {
            val calls = mutableListOf<String>()
            val snapshot = setupSnapshot(OnboardingStepFfi.FOLLOWS, listOf(OnboardingActionFfi.CONTINUE_WITHOUT))
            val native =
                nativeBoundary { method ->
                    calls += method
                    snapshot
                }
            val result =
                MarmotAccountSetupClient(native, SETUP_TEST_ACCOUNT) {}.previewRelayRepair(OnboardingStepFfi.RELAYS)
            assertEquals(snapshot, result)
            assertEquals(listOf("proposeOnboardingRelayRepair"), calls)
        }

    /** Cancellation between native completion and caller delivery releases the otherwise orphaned proposal. */
    @Test fun cancelledDeliveryCleansUpWithoutMaskingCancellation() =
        runTest {
            for (failCleanup in listOf(false, true)) {
                val calls = mutableListOf<String>()
                val preview = relayPreviewSnapshot()
                lateinit var owner: Job
                var cancellation: CancellationException? = null
                val native =
                    nativeBoundary { method ->
                        calls += method
                        when (method) {
                            "proposeOnboardingRelayRepair" -> {
                                owner.cancel()
                                preview
                            }
                            "onboardingSnapshot" -> preview
                            "cancelOnboardingRepair" -> {
                                check(!failCleanup) { "cleanup failed" }
                                relayDecision().copy(revision = 5uL)
                            }
                            else -> error("Unexpected native call: $method")
                        }
                    }
                owner =
                    launch(start = CoroutineStart.LAZY) {
                        try {
                            MarmotAccountSetupClient(native, SETUP_TEST_ACCOUNT) {}
                                .previewRelayRepair(OnboardingStepFfi.RELAYS)
                        } catch (cancel: CancellationException) {
                            cancellation = cancel
                            throw cancel
                        }
                    }
                owner.start()
                owner.join()
                assertNotNull(cancellation)
                assertEquals(if (failCleanup) 1 else 0, cancellation!!.suppressed.size)
                assertEquals(
                    listOf("proposeOnboardingRelayRepair", "onboardingSnapshot", "cancelOnboardingRepair"),
                    calls,
                )
            }
        }

    /** Returns scripted native snapshots while rejecting accidental automatic setup commands. */
    private fun nativeBoundary(onCall: (String) -> OnboardingSnapshotFfi): MarmotInterface {
        val nativeType = MarmotInterface::class.java
        return Proxy.newProxyInstance(nativeType.classLoader, arrayOf(nativeType)) { _, method, _ ->
            onCall(method.name.substringBefore('-'))
        } as MarmotInterface
    }
}
