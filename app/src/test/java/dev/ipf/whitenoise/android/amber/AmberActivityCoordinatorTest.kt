package dev.ipf.whitenoise.android.amber

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.service.chooser.ChooserResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

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
        Nip55.clearSignerPackage(context)
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
                putExtra(AmberSignerRelay.EXTRA_HANDLED_SIGNER_PACKAGE, "com.spoofed.signer")
                putExtra(Nip55.EXTRA_RESULT, "npub1result")
            }

        val result =
            AmberSignerRelay.buildResultIntent(
                requestId,
                signerData,
                handledSignerPackage = "com.actual.signer",
            )
        val nullDataResult = AmberSignerRelay.buildResultIntent(requestId, signerData = null)

        assertEquals(requestId, result.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        assertFalse(result.getBooleanExtra(AmberSignerRelay.EXTRA_LAUNCH_FAILED, false))
        assertEquals("com.actual.signer", result.getStringExtra(AmberSignerRelay.EXTRA_HANDLED_SIGNER_PACKAGE))
        assertEquals("npub1result", result.getStringExtra(Nip55.EXTRA_RESULT))
        assertEquals(requestId, nullDataResult.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
    }

    @Test
    fun soleResolvedSignerIsMadeExplicitAndRecordedAsHandled() {
        val requestId = "single-signer"
        val signerIntent = Nip55.buildGetPublicKeyIntent("", requestId)
        registerSignerHandler(signerIntent, "com.example.signer", "com.example.signer.SignerActivity")

        val prepared = AmberSignerRelay.prepareSignerLaunch(context, requestId, signerIntent)

        assertEquals(
            ComponentName("com.example.signer", "com.example.signer.SignerActivity"),
            prepared?.component,
        )
        assertEquals("com.example.signer", AmberSignerRelay.consumeHandledSignerPackage(requestId))
    }

    @Test
    fun chooserCallbackRecordsThePackageSelectedByAndroid() {
        val requestId = "chosen-signer"
        AmberSignerRelay.registerHandledSignerRequest(requestId)
        AmberSignerChoiceReceiver().onReceive(
            context,
            Intent().apply {
                putExtra(AmberSignerRelay.EXTRA_CHOOSER_REQUEST_ID, requestId)
                putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("com.chosen.signer", "SignerActivity"))
            },
        )

        assertEquals("com.chosen.signer", AmberSignerRelay.consumeHandledSignerPackage(requestId))
    }

    @Test
    fun chooserResultRecordsThePackageSelectedByAndroidOnApi35AndLater() {
        val requestId = "chosen-signer-result"
        AmberSignerRelay.registerHandledSignerRequest(requestId)
        val component = ComponentName("com.modern.signer", "SignerActivity")
        val chooserResult =
            ChooserResult::class.java
                .getDeclaredConstructor(Int::class.javaPrimitiveType, ComponentName::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .newInstance(ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT, component, false)
        AmberSignerChoiceReceiver().onReceive(
            context,
            Intent().apply {
                putExtra(AmberSignerRelay.EXTRA_CHOOSER_REQUEST_ID, requestId)
                putExtra(Intent.EXTRA_CHOOSER_RESULT, chooserResult)
            },
        )

        assertEquals("com.modern.signer", AmberSignerRelay.consumeHandledSignerPackage(requestId))
    }

    @Test
    fun successfulSignerResultWaitsForTheChooserCallbackPackage() =
        runBlocking {
            val requestId = "fast-signer-before-chooser-callback"
            AmberSignerRelay.registerHandledSignerRequest(requestId)
            val handledPackage =
                async(start = CoroutineStart.UNDISPATCHED) {
                    AmberSignerRelay.awaitHandledSignerPackage(requestId, timeoutMs = 2_000)
                }

            assertFalse(handledPackage.isCompleted)
            AmberSignerChoiceReceiver().onReceive(
                context,
                Intent().apply {
                    putExtra(AmberSignerRelay.EXTRA_CHOOSER_REQUEST_ID, requestId)
                    putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("com.fast.signer", "SignerActivity"))
                },
            )

            assertEquals("com.fast.signer", handledPackage.await())
            assertEquals("com.fast.signer", AmberSignerRelay.consumeHandledSignerPackage(requestId))
            assertNull(AmberSignerRelay.consumeHandledSignerPackage(requestId))
        }

    @Test
    fun publicKeyLoginPersistsTheResolvedPackageNotOnlyTheSignerEcho() {
        val signerPackage = "com.example.signer"
        val signerIntent = Nip55.buildGetPublicKeyIntent("", "probe")
        registerSignerHandler(signerIntent, signerPackage, "$signerPackage.SignerActivity")
        val result = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                result.set(AmberSignerController(context, approvalTimeoutMs = 5_000).requestPublicKey())
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }.start()

        val relayLaunch = awaitRelayLaunch()
        val requestId = checkNotNull(relayLaunch.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        val launchedSignerIntent =
            checkNotNull(relayLaunch.getParcelableExtra(AmberSignerRelay.EXTRA_SIGNER_INTENT, Intent::class.java))
        assertNotNull(AmberSignerRelay.prepareSignerLaunch(context, requestId, launchedSignerIntent))
        val handledPackage = AmberSignerRelay.consumeHandledSignerPackage(requestId)
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                AmberSignerRelay.buildResultIntent(
                    requestId,
                    Intent().apply {
                        putExtra(Nip55.EXTRA_RESULT, "npub1resolved")
                        putExtra(Nip55.EXTRA_PACKAGE, signerPackage)
                    },
                    handledSignerPackage = handledPackage,
                ),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals("npub1resolved", result.get())
        assertEquals(signerPackage, Nip55.savedSignerPackage(context))
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

    private fun registerSignerHandler(
        intent: Intent,
        packageName: String,
        className: String,
    ) {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        this.packageName = packageName
                        name = className
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")),
            resolveInfo,
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }
}
