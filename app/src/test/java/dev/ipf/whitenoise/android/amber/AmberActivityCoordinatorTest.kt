package dev.ipf.whitenoise.android.amber

import android.content.Intent
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins request-id correlation for Amber signer prompts: late results from a
 * prior, timed-out prompt must not satisfy the next caller, while the current
 * prompt's cancellation (signer data null) must complete promptly via the relay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmberActivityCoordinatorTest {
    private lateinit var coordinatorLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private val launched = AtomicReference<Intent>()

    @Before
    fun attachCoordinatorLauncher() {
        launched.set(null)
        coordinatorLauncher = CapturingLauncher()
        AmberActivityCoordinator.attach(coordinatorLauncher)
    }

    @After
    fun detachCoordinatorLauncher() {
        AmberActivityCoordinator.detach(coordinatorLauncher)
    }

    private inner class CapturingLauncher : androidx.activity.result.ActivityResultLauncher<Intent>() {
        override fun launch(
            input: Intent,
            options: androidx.core.app.ActivityOptionsCompat?,
        ) {
            launched.set(input)
        }

        override fun unregister() = Unit

        override val contract: androidx.activity.result.contract.ActivityResultContract<Intent, *> =
            ActivityResultContracts.StartActivityForResult()
    }

    private fun awaitRelayLaunch(timeoutMs: Long = 2_000): Intent {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            launched.get()?.let { return it }
            Thread.sleep(5)
        }
        val relayLaunch = launched.get()
        assertNotNull(relayLaunch)
        return relayLaunch!!
    }

    @Test
    fun staleResultCannotSatisfyGetPublicKey() {
        assertFalse(
            AmberActivityCoordinator.shouldAcceptResult(
                expectedId = "login-req-7f3a",
                resultId = "stale-sign-event-id",
            ),
        )
    }

    @Test
    fun matchingRequestIdsAreAccepted() {
        assertTrue(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = "req-A"))
    }

    @Test
    fun resultWithoutRelayRequestIdIsDropped() {
        assertFalse(AmberActivityCoordinator.shouldAcceptResult(expectedId = "req-A", resultId = null))
    }

    @Test
    fun getPublicKeyIntentCarriesClientRequestId() {
        val requestId = "login-req-7f3a"
        val intent = Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), requestId)
        assertEquals(requestId, intent.getStringExtra(Nip55.EXTRA_ID))
    }

    @Test
    fun relayCancellationCarriesTrustedRequestId() {
        val requestId = "current-get-public-key"
        val signerData =
            Intent().apply {
                putExtra(AmberSignerRelay.EXTRA_REQUEST_ID, "signer-controlled-id")
                putExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, true)
                putExtra(Nip55.EXTRA_RESULT, "npub1result")
            }

        val result = AmberSignerRelay.buildResultIntent(requestId, signerData)
        val nullDataResult = AmberSignerRelay.buildResultIntent(requestId, signerData = null)

        assertEquals(requestId, result.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        assertFalse(result.getBooleanExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, false))
        assertEquals("npub1result", result.getStringExtra(Nip55.EXTRA_RESULT))
        assertEquals(requestId, nullDataResult.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
    }

    @Test
    fun getPublicKeyCancellationCompletesPromptly() {
        val requestId = "current-get-public-key"

        val outcomeRef = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(1)
        val worker =
            Thread {
                outcomeRef.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), requestId),
                        timeoutMs = 5_000,
                        requestId = requestId,
                    ),
                )
                done.countDown()
            }
        worker.start()

        val relayLaunch = awaitRelayLaunch()
        assertEquals(AmberSignerRelayActivity::class.java.name, relayLaunch.component?.className)
        assertEquals(requestId, relayLaunch.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        val signerIntent = relayLaunch.getParcelableExtra(AmberSignerRelay.EXTRA_SIGNER_INTENT, Intent::class.java)
        assertEquals(requestId, signerIntent?.getStringExtra(Nip55.EXTRA_ID))

        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data =
                Intent().apply {
                    putExtra(AmberSignerRelay.EXTRA_REQUEST_ID, requestId)
                },
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        val outcome = outcomeRef.get()
        assertTrue(outcome is AmberActivityCoordinator.Outcome.Completed)
        outcome as AmberActivityCoordinator.Outcome.Completed
        assertFalse(outcome.resultOk)
        assertEquals(requestId, outcome.data?.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
    }

    @Test
    fun relayLaunchFailureReturnsUnavailable() {
        val requestId = "signer-vanished"
        val outcomeRef = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(1)
        Thread {
            outcomeRef.set(
                AmberActivityCoordinator.awaitApproval(
                    Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), requestId),
                    timeoutMs = 5_000,
                    requestId = requestId,
                ),
            )
            done.countDown()
        }.start()

        awaitRelayLaunch()
        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data =
                Intent().apply {
                    putExtra(AmberSignerRelay.EXTRA_REQUEST_ID, requestId)
                    putExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, true)
                },
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.NoForegroundActivity, outcomeRef.get())
    }

    @Test
    fun staleCancellationAfterTimeoutDoesNotSatisfyNextRequest() {
        val staleRequestId = "stale-get-public-key"
        val currentRequestId = "current-get-public-key"

        val firstOutcome = AtomicReference<AmberActivityCoordinator.Outcome>()
        val firstDone = CountDownLatch(1)
        val firstWorker =
            Thread {
                firstOutcome.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), staleRequestId),
                        timeoutMs = 100,
                        requestId = staleRequestId,
                    ),
                )
                firstDone.countDown()
            }
        firstWorker.start()
        awaitRelayLaunch()

        assertTrue(firstDone.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, firstOutcome.get())

        launched.set(null)
        val secondOutcome = AtomicReference<AmberActivityCoordinator.Outcome>()
        val secondDone = CountDownLatch(1)
        val secondWorker =
            Thread {
                secondOutcome.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildGetPublicKeyIntent(Nip55.defaultPermissionsJson(), currentRequestId),
                        timeoutMs = 5_000,
                        requestId = currentRequestId,
                    ),
                )
                secondDone.countDown()
            }
        secondWorker.start()
        awaitRelayLaunch()

        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data =
                Intent().apply {
                    putExtra(AmberSignerRelay.EXTRA_REQUEST_ID, staleRequestId)
                },
        )

        assertFalse(secondDone.await(200, TimeUnit.MILLISECONDS))

        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data =
                Intent().apply {
                    putExtra(AmberSignerRelay.EXTRA_REQUEST_ID, currentRequestId)
                },
        )

        assertTrue(secondDone.await(2, TimeUnit.SECONDS))

        val outcome = secondOutcome.get()
        assertTrue(outcome is AmberActivityCoordinator.Outcome.Completed)
        outcome as AmberActivityCoordinator.Outcome.Completed
        assertFalse(outcome.resultOk)
        assertEquals(currentRequestId, outcome.data?.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
    }

    @Test
    fun nip55RejectionCompletesPromptly() {
        val requestId = "current-sign-event"
        val packageName = "com.example.signer"
        val eventJson = """{"kind":1,"content":"hello"}"""

        val outcomeRef = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(1)
        val worker =
            Thread {
                outcomeRef.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildSignEventIntent(packageName, eventJson, requestId, currentUser = "abc"),
                        timeoutMs = 5_000,
                        requestId = requestId,
                    ),
                )
                done.countDown()
            }
        worker.start()

        awaitRelayLaunch()
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                AmberSignerRelay.buildResultIntent(
                    requestId,
                    signerData = Intent().apply { putExtra(Nip55.EXTRA_REJECTED, true) },
                ),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        val outcome = outcomeRef.get()
        assertTrue(outcome is AmberActivityCoordinator.Outcome.Completed)
        outcome as AmberActivityCoordinator.Outcome.Completed
        assertTrue(outcome.resultOk)
        assertTrue(readRejectedIntentExtra(outcome.data))
        assertEquals(requestId, outcome.data?.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
    }

    @Test
    fun staleNip55RejectionAfterTimeoutDoesNotSatisfyNextRequest() {
        val staleRequestId = "stale-sign-event"
        val currentRequestId = "current-sign-event"
        val packageName = "com.example.signer"
        val eventJson = """{"kind":1,"content":"hello"}"""

        val firstOutcome = AtomicReference<AmberActivityCoordinator.Outcome>()
        val firstDone = CountDownLatch(1)
        val firstWorker =
            Thread {
                firstOutcome.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildSignEventIntent(packageName, eventJson, staleRequestId, currentUser = "abc"),
                        timeoutMs = 100,
                        requestId = staleRequestId,
                    ),
                )
                firstDone.countDown()
            }
        firstWorker.start()
        awaitRelayLaunch()

        assertTrue(firstDone.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, firstOutcome.get())

        launched.set(null)
        val secondOutcome = AtomicReference<AmberActivityCoordinator.Outcome>()
        val secondDone = CountDownLatch(1)
        val secondWorker =
            Thread {
                secondOutcome.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildSignEventIntent(packageName, eventJson, currentRequestId, currentUser = "abc"),
                        timeoutMs = 5_000,
                        requestId = currentRequestId,
                    ),
                )
                secondDone.countDown()
            }
        secondWorker.start()
        awaitRelayLaunch()

        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                AmberSignerRelay.buildResultIntent(
                    staleRequestId,
                    signerData = Intent().apply { putExtra(Nip55.EXTRA_REJECTED, true) },
                ),
        )

        assertFalse(secondDone.await(200, TimeUnit.MILLISECONDS))

        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                AmberSignerRelay.buildResultIntent(
                    currentRequestId,
                    signerData = Intent().apply { putExtra(Nip55.EXTRA_REJECTED, true) },
                ),
        )

        assertTrue(secondDone.await(2, TimeUnit.SECONDS))

        val outcome = secondOutcome.get()
        assertTrue(outcome is AmberActivityCoordinator.Outcome.Completed)
        outcome as AmberActivityCoordinator.Outcome.Completed
        assertTrue(outcome.resultOk)
        assertEquals(currentRequestId, outcome.data?.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
    }
}
