package dev.ipf.whitenoise.android.amber

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.os.Parcel
import android.service.chooser.ChooserResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import java.util.concurrent.ConcurrentLinkedQueue
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
@Suppress("LargeClass") // Relay and grouped prompt lifecycle scenarios share one process-wide coordinator fixture.
class AmberActivityCoordinatorTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private lateinit var coordinatorLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private val launched = AtomicReference<Intent>()
    private val launches = ConcurrentLinkedQueue<Intent>()

    @Before
    fun attachCoordinatorLauncher() {
        launched.set(null)
        launches.clear()
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
            launches.add(input)
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

    private fun awaitLaunchCount(
        expected: Int,
        timeoutMs: Long = 2_000,
    ): List<Intent> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (launches.size >= expected) return launches.toList()
            Thread.sleep(5)
        }
        assertEquals(expected, launches.size)
        return launches.toList()
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
    fun getPublicKeyIntentCarriesCompleteTypedGrantSetAndLaunchContract() {
        val requestId = "login-req-7f3a"
        val intent = Nip55.buildGetPublicKeyIntent(requestId)

        assertSignerFacingLoginIntent(intent, requestId)
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
        val signerIntent = Nip55.buildGetPublicKeyIntent(requestId)
        registerSignerHandler(signerIntent, "com.example.signer", "com.example.signer.SignerActivity")

        val prepared = AmberSignerRelay.prepareSignerLaunch(context, requestId, signerIntent)

        assertEquals(
            ComponentName("com.example.signer", "com.example.signer.SignerActivity"),
            prepared?.component,
        )
        assertSignerFacingLoginIntent(checkNotNull(prepared), requestId)
        assertEquals("com.example.signer", AmberSignerRelay.consumeHandledSignerPackage(requestId))
    }

    @Test
    fun chooserTargetPreservesCompleteLoginPermissions() {
        val requestId = "multiple-signers"
        val signerIntent = Nip55.buildGetPublicKeyIntent(requestId)
        registerSignerHandler(signerIntent, "com.example.first", "com.example.first.SignerActivity")
        registerSignerHandler(signerIntent, "com.example.second", "com.example.second.SignerActivity")

        val chooser = checkNotNull(AmberSignerRelay.prepareSignerLaunch(context, requestId, signerIntent))
        val target = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertSignerFacingLoginIntent(target, requestId)
        AmberSignerRelay.consumeHandledSignerPackage(requestId)
    }

    @Test
    fun relayParcelBoundaryPreservesCompleteLoginPermissions() {
        val requestId = "parcelled-login"
        val relayIntent = AmberSignerRelay.buildLaunchIntent(requestId, Nip55.buildGetPublicKeyIntent(requestId))
        val parcel = Parcel.obtain()
        val restored =
            try {
                relayIntent.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                Intent.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        val restoredSignerIntent =
            checkNotNull(restored.getParcelableExtra(AmberSignerRelay.EXTRA_SIGNER_INTENT, Intent::class.java))

        assertEquals(requestId, restored.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        assertSignerFacingLoginIntent(restoredSignerIntent, requestId)
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
        val signerIntent = Nip55.buildGetPublicKeyIntent("probe")
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
    fun publicKeyLoginUsesDirectGroupedPathForSoleAmber64() {
        installAmber("6.4.0")
        val probe = Nip55.buildGetPublicKeyIntent("probe")
        registerSignerHandler(probe, Nip55.AMBER_PACKAGE, "${Nip55.AMBER_PACKAGE}.SignerActivity")
        val pubkey = "ab".repeat(32)
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

        val signerLaunch = awaitRelayLaunch()
        assertEquals(Nip55.AMBER_PACKAGE, signerLaunch.`package`)
        assertFalse(signerLaunch.component?.className == AmberSignerRelayActivity::class.java.name)
        val requestId = checkNotNull(signerLaunch.getStringExtra(Nip55.EXTRA_ID))
        assertSignerFacingLoginIntent(signerLaunch, requestId)
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                Intent()
                    .putExtra(Nip55.EXTRA_ID, requestId)
                    .putExtra(Nip55.EXTRA_RESULT, pubkey)
                    .putExtra(Nip55.EXTRA_PACKAGE, Nip55.AMBER_PACKAGE),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals(pubkey, result.get())
        assertEquals(Nip55.AMBER_PACKAGE, Nip55.savedSignerPackage(context))
    }

    @Test
    fun directGroupedLoginRejectsASpoofedSignerPackageEcho() {
        installAmber("6.4.0")
        val probe = Nip55.buildGetPublicKeyIntent("probe")
        registerSignerHandler(probe, Nip55.AMBER_PACKAGE, "${Nip55.AMBER_PACKAGE}.SignerActivity")
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                AmberSignerController(context, approvalTimeoutMs = 5_000).requestPublicKey()
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }.start()

        val signerLaunch = awaitRelayLaunch()
        val requestId = checkNotNull(signerLaunch.getStringExtra(Nip55.EXTRA_ID))
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                Intent()
                    .putExtra(Nip55.EXTRA_ID, requestId)
                    .putExtra(Nip55.EXTRA_RESULT, "ab".repeat(32))
                    .putExtra(Nip55.EXTRA_PACKAGE, "com.example.spoofed"),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get() is MarmotKitException.Runtime)
        assertNull(Nip55.savedSignerPackage(context))
    }

    @Test
    fun publicKeyLoginKeepsSerializedChooserWhenSignerChoiceIsAmbiguous() {
        installAmber("6.4.0")
        val probe = Nip55.buildGetPublicKeyIntent("probe")
        registerSignerHandler(probe, Nip55.AMBER_PACKAGE, "${Nip55.AMBER_PACKAGE}.SignerActivity")
        registerSignerHandler(probe, "com.example.other", "com.example.other.SignerActivity")
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                AmberSignerController(context, approvalTimeoutMs = 5_000).requestPublicKey()
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }.start()

        val relayLaunch = awaitRelayLaunch()
        assertEquals(AmberSignerRelayActivity::class.java.name, relayLaunch.component?.className)
        val requestId = checkNotNull(relayLaunch.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        val signerIntent =
            checkNotNull(relayLaunch.getParcelableExtra(AmberSignerRelay.EXTRA_SIGNER_INTENT, Intent::class.java))
        assertEquals(
            Intent.ACTION_CHOOSER,
            AmberSignerRelay.prepareSignerLaunch(context, requestId, signerIntent)?.action,
        )
        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data = AmberSignerRelay.buildResultIntent(requestId, signerData = null),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get() is MarmotKitException.ExternalSignerRejected)
        assertNull(Nip55.savedSignerPackage(context))
    }

    @Test
    fun publicKeyLoginKeepsSerializedRelayForOlderAmber() {
        installAmber("6.2.9")
        val probe = Nip55.buildGetPublicKeyIntent("probe")
        registerSignerHandler(probe, Nip55.AMBER_PACKAGE, "${Nip55.AMBER_PACKAGE}.SignerActivity")
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        Thread {
            try {
                AmberSignerController(context, approvalTimeoutMs = 5_000).requestPublicKey()
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }.start()

        val relayLaunch = awaitRelayLaunch()
        assertEquals(AmberSignerRelayActivity::class.java.name, relayLaunch.component?.className)
        val requestId = checkNotNull(relayLaunch.getStringExtra(AmberSignerRelay.EXTRA_REQUEST_ID))
        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data = AmberSignerRelay.buildResultIntent(requestId, signerData = null),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get() is MarmotKitException.ExternalSignerRejected)
        assertNull(Nip55.savedSignerPackage(context))
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
                        Nip55.buildGetPublicKeyIntent(requestId),
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
                    Nip55.buildGetPublicKeyIntent(requestId),
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
                        Nip55.buildGetPublicKeyIntent(staleRequestId),
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
                        Nip55.buildGetPublicKeyIntent(currentRequestId),
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
    fun groupedResultsAreCorrelatedByIdAcrossReorderingAndMixedDecisions() {
        val approvedId = "grouped-approved"
        val rejectedId = "grouped-rejected"
        val packageName = Nip55.AMBER_PACKAGE
        val approved = AtomicReference<AmberActivityCoordinator.Outcome>()
        val rejected = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(2)

        fun launchRequest(
            id: String,
            destination: AtomicReference<AmberActivityCoordinator.Outcome>,
        ) = Thread {
            destination.set(
                AmberActivityCoordinator.awaitApproval(
                    Nip55.buildCryptoIntent(
                        SignerOp.Nip44Encrypt,
                        packageName,
                        content = "content-$id",
                        counterparty = "counterparty",
                        currentUser = "account-a",
                        id = id,
                    ),
                    timeoutMs = 5_000,
                    requestId = id,
                    allowGrouping = true,
                ),
            )
            done.countDown()
        }.apply(Thread::start)

        launchRequest(approvedId, approved)
        launchRequest(rejectedId, rejected)
        val directLaunches = awaitLaunchCount(2)
        assertTrue(directLaunches.all { it.`package` == packageName })
        assertTrue(directLaunches.none { it.component?.className == AmberSignerRelayActivity::class.java.name })

        val aggregate =
            JSONArray()
                .put(JSONObject().put("id", rejectedId).put("rejected", true))
                .put(JSONObject().put("id", approvedId).put("result", "ciphertext"))
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_RESULTS, aggregate.toString()),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        val approvedOutcome = approved.get() as AmberActivityCoordinator.Outcome.Completed
        val rejectedOutcome = rejected.get() as AmberActivityCoordinator.Outcome.Completed
        assertEquals("ciphertext", approvedOutcome.data?.getStringExtra(Nip55.EXTRA_RESULT))
        assertFalse(readRejectedIntentExtra(approvedOutcome.data))
        assertTrue(readRejectedIntentExtra(rejectedOutcome.data))
        assertEquals(
            packageName,
            approvedOutcome.data?.getStringExtra(AmberSignerRelay.EXTRA_HANDLED_SIGNER_PACKAGE),
        )
    }

    @Test
    @Suppress("LongMethod") // Duplicate, unknown, and missing IDs must remain one exact-once correlation scenario.
    fun duplicateUnknownAndMissingAggregateIdsCannotCompletePendingRequests() {
        val firstId = "grouped-first"
        val secondId = "grouped-second"
        val first = AtomicReference<AmberActivityCoordinator.Outcome>()
        val second = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(2)

        listOf(firstId to first, secondId to second).forEach { (id, destination) ->
            Thread {
                destination.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildCryptoIntent(
                            SignerOp.Nip44Decrypt,
                            Nip55.AMBER_PACKAGE,
                            content = "payload-$id",
                            counterparty = "counterparty",
                            currentUser = "account-a",
                            id = id,
                        ),
                        timeoutMs = 5_000,
                        requestId = id,
                        allowGrouping = true,
                    ),
                )
                done.countDown()
            }.start()
        }
        awaitLaunchCount(2)

        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                Intent().putExtra(
                    Nip55.EXTRA_RESULTS,
                    """
                    [
                        {"id":"$firstId","result":"first-value"},
                        {"id":"unknown","result":"unknown-value"},
                        {"id":"$firstId","result":"duplicate-value"}
                    ]
                    """.trimIndent(),
                ),
        )
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        assertNull(first.get())
        assertNull(second.get())

        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_ID, firstId).putExtra(Nip55.EXTRA_RESULT, "first-safe"),
        )
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_ID, secondId).putExtra(Nip55.EXTRA_RESULT, "second-safe"),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(
            "first-safe",
            (first.get() as AmberActivityCoordinator.Outcome.Completed).data?.getStringExtra(Nip55.EXTRA_RESULT),
        )
        assertEquals(
            "second-safe",
            (second.get() as AmberActivityCoordinator.Outcome.Completed).data?.getStringExtra(Nip55.EXTRA_RESULT),
        )
    }

    @Test
    fun groupedApprovalConcurrencyIsBoundedAndCancellationCompletesTheVisibleSession() {
        val outcomes = List(Nip55.MAX_GROUPED_APPROVALS + 1) { AtomicReference<AmberActivityCoordinator.Outcome>() }
        val done = CountDownLatch(outcomes.size)

        fun startRequest(
            index: Int,
            timeoutMs: Long,
        ) {
            val destination = outcomes[index]
            Thread {
                destination.set(
                    AmberActivityCoordinator.awaitApproval(
                        Nip55.buildCryptoIntent(
                            SignerOp.Nip44Encrypt,
                            Nip55.AMBER_PACKAGE,
                            content = "content-$index",
                            counterparty = "counterparty",
                            currentUser = "account-a",
                            id = "bounded-$index",
                        ),
                        timeoutMs = timeoutMs,
                        requestId = "bounded-$index",
                        allowGrouping = true,
                    ),
                )
                done.countDown()
            }.start()
        }

        repeat(Nip55.MAX_GROUPED_APPROVALS) { index -> startRequest(index, timeoutMs = 5_000) }

        awaitLaunchCount(Nip55.MAX_GROUPED_APPROVALS)
        startRequest(Nip55.MAX_GROUPED_APPROVALS, timeoutMs = 250)
        Thread.sleep(350)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Nip55.MAX_GROUPED_APPROVALS, launches.size)
        assertEquals(
            AmberActivityCoordinator.Outcome.TimedOut,
            outcomes.last().get(),
        )

        AmberActivityCoordinator.deliverResult(resultOk = false, data = null)

        assertTrue(done.await(2, TimeUnit.SECONDS))
        outcomes.dropLast(1).forEach { outcome ->
            val completed = outcome.get() as AmberActivityCoordinator.Outcome.Completed
            assertFalse(completed.resultOk)
        }
    }

    @Test
    fun cancellationAfterOneTimeoutRejectsTheRemainingVisibleGroupedSession() {
        val staleId = "stale-grouped"
        val currentId = "current-grouped"
        val stale = AtomicReference<AmberActivityCoordinator.Outcome>()
        val current = AtomicReference<AmberActivityCoordinator.Outcome>()
        val staleDone = CountDownLatch(1)
        val currentDone = CountDownLatch(1)

        Thread {
            stale.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent(staleId, currentUser = "account-a"),
                    timeoutMs = 100,
                    requestId = staleId,
                    allowGrouping = true,
                ),
            )
            staleDone.countDown()
        }.start()
        awaitLaunchCount(1)
        assertTrue(staleDone.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, stale.get())

        Thread {
            current.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent(currentId, currentUser = "account-a"),
                    timeoutMs = 5_000,
                    requestId = currentId,
                    allowGrouping = true,
                ),
            )
            currentDone.countDown()
        }.start()
        awaitLaunchCount(2)

        AmberActivityCoordinator.deliverResult(resultOk = false, data = null)
        assertTrue(currentDone.await(2, TimeUnit.SECONDS))
        assertFalse((current.get() as AmberActivityCoordinator.Outcome.Completed).resultOk)
    }

    @Test
    fun staleRelayCancellationAfterTimeoutDoesNotCancelAGroupedRequest() {
        val staleId = "stale-serialized"
        val currentId = "current-grouped"
        val stale = AtomicReference<AmberActivityCoordinator.Outcome>()
        val current = AtomicReference<AmberActivityCoordinator.Outcome>()
        val staleDone = CountDownLatch(1)
        val currentDone = CountDownLatch(1)

        Thread {
            stale.set(
                AmberActivityCoordinator.awaitApproval(
                    Nip55.buildGetPublicKeyIntent(staleId),
                    timeoutMs = 100,
                    requestId = staleId,
                ),
            )
            staleDone.countDown()
        }.start()
        awaitRelayLaunch()
        assertTrue(staleDone.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, stale.get())

        Thread {
            current.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent(currentId, currentUser = "account-a"),
                    timeoutMs = 5_000,
                    requestId = currentId,
                    allowGrouping = true,
                ),
            )
            currentDone.countDown()
        }.start()
        awaitLaunchCount(2)

        AmberActivityCoordinator.deliverResult(
            resultOk = false,
            data = AmberSignerRelay.buildResultIntent(staleId, signerData = null),
        )
        assertFalse(currentDone.await(200, TimeUnit.MILLISECONDS))

        AmberActivityCoordinator.deliverResult(resultOk = false, data = null)
        assertTrue(currentDone.await(2, TimeUnit.SECONDS))
        assertFalse((current.get() as AmberActivityCoordinator.Outcome.Completed).resultOk)
    }

    @Test
    fun expiredRequestsAreRemovedBeforeTheirQueuedLaunchRuns() {
        val serialized = AtomicReference<AmberActivityCoordinator.Outcome>()
        val serializedDone = CountDownLatch(1)
        Thread {
            serialized.set(
                AmberActivityCoordinator.awaitApproval(
                    Nip55.buildGetPublicKeyIntent("expired-serialized"),
                    timeoutMs = 50,
                    requestId = "expired-serialized",
                ),
            )
            serializedDone.countDown()
        }.start()

        assertTrue(serializedDone.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, serialized.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(launches.isEmpty())

        val grouped = AtomicReference<AmberActivityCoordinator.Outcome>()
        val groupedDone = CountDownLatch(1)
        Thread {
            grouped.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent("expired-grouped", currentUser = "account-a"),
                    timeoutMs = 50,
                    requestId = "expired-grouped",
                    allowGrouping = true,
                ),
            )
            groupedDone.countDown()
        }.start()

        assertTrue(groupedDone.await(2, TimeUnit.SECONDS))
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, grouped.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(launches.isEmpty())
    }

    @Test
    fun groupedRequestsFromDifferentAccountsCannotShareAnApprovalSession() {
        val first = AtomicReference<AmberActivityCoordinator.Outcome>()
        val second = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(2)

        Thread {
            first.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent("account-a-request", currentUser = "account-a"),
                    timeoutMs = 5_000,
                    requestId = "account-a-request",
                    allowGrouping = true,
                ),
            )
            done.countDown()
        }.start()
        awaitLaunchCount(1)

        Thread {
            second.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent("account-b-request", currentUser = "account-b"),
                    timeoutMs = 250,
                    requestId = "account-b-request",
                    allowGrouping = true,
                ),
            )
            done.countDown()
        }.start()
        Thread.sleep(350)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, launches.size)
        assertEquals(AmberActivityCoordinator.Outcome.TimedOut, second.get())
        AmberActivityCoordinator.deliverResult(resultOk = false, data = null)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse((first.get() as AmberActivityCoordinator.Outcome.Completed).resultOk)
    }

    @Test
    fun concurrentGroupedPublicKeyRequestsCannotShareAnApprovalSession() {
        val firstId = "grouped-login-first"
        val secondId = "grouped-login-second"
        val first = AtomicReference<AmberActivityCoordinator.Outcome>()
        val second = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(2)

        fun launchLogin(
            id: String,
            destination: AtomicReference<AmberActivityCoordinator.Outcome>,
        ) = Thread {
            destination.set(
                AmberActivityCoordinator.awaitApproval(
                    Nip55.buildGetPublicKeyIntent(id).setPackage(Nip55.AMBER_PACKAGE),
                    timeoutMs = 5_000,
                    requestId = id,
                    allowGrouping = true,
                ),
            )
            done.countDown()
        }.apply(Thread::start)

        launchLogin(firstId, first)
        awaitLaunchCount(1)
        launchLogin(secondId, second)
        val admissionDeadline = System.currentTimeMillis() + 250
        while (System.currentTimeMillis() < admissionDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals("a second login must wait for the first session", 1, launches.size)

        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_ID, firstId).putExtra(Nip55.EXTRA_RESULT, "first-pubkey"),
        )
        awaitLaunchCount(2)
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_ID, secondId).putExtra(Nip55.EXTRA_RESULT, "second-pubkey"),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(
            "first-pubkey",
            (first.get() as AmberActivityCoordinator.Outcome.Completed).data?.getStringExtra(Nip55.EXTRA_RESULT),
        )
        assertEquals(
            "second-pubkey",
            (second.get() as AmberActivityCoordinator.Outcome.Completed).data?.getStringExtra(Nip55.EXTRA_RESULT),
        )
    }

    @Test
    fun groupedResultSurvivesForegroundLauncherRecreation() {
        val requestId = "grouped-recreated-launcher"
        val outcome = AtomicReference<AmberActivityCoordinator.Outcome>()
        val done = CountDownLatch(1)
        Thread {
            outcome.set(
                AmberActivityCoordinator.awaitApproval(
                    groupedCryptoIntent(requestId, currentUser = "account-a"),
                    timeoutMs = 5_000,
                    requestId = requestId,
                    allowGrouping = true,
                ),
            )
            done.countDown()
        }.start()
        awaitLaunchCount(1)

        val replaced = coordinatorLauncher
        coordinatorLauncher = CapturingLauncher()
        AmberActivityCoordinator.attach(coordinatorLauncher)
        AmberActivityCoordinator.detach(replaced)
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_ID, requestId).putExtra(Nip55.EXTRA_RESULT, "recreated-value"),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(
            "recreated-value",
            (outcome.get() as AmberActivityCoordinator.Outcome.Completed).data?.getStringExtra(Nip55.EXTRA_RESULT),
        )
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

    private fun installAmber(versionName: String) {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = Nip55.AMBER_PACKAGE
                this.versionName = versionName
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = Nip55.AMBER_PACKAGE
                    }
            },
        )
    }

    private fun groupedCryptoIntent(
        requestId: String,
        currentUser: String,
    ): Intent =
        Nip55.buildCryptoIntent(
            SignerOp.Nip44Encrypt,
            Nip55.AMBER_PACKAGE,
            content = "content-$requestId",
            counterparty = "counterparty",
            currentUser = currentUser,
            id = requestId,
        )

    private fun assertSignerFacingLoginIntent(
        intent: Intent,
        requestId: String,
    ) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("${Nip55.SCHEME}:"), intent.data)
        assertEquals(SignerOp.GetPublicKey.intentType, intent.getStringExtra(Nip55.EXTRA_TYPE))
        assertEquals(requestId, intent.getStringExtra(Nip55.EXTRA_ID))
        assertTrue(requestId.isNotBlank())
        val requiredFlags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(requiredFlags, intent.flags and requiredFlags)

        val permissions = JSONArray(checkNotNull(intent.getStringExtra(Nip55.EXTRA_PERMISSIONS)))
        val actual =
            buildList {
                repeat(permissions.length()) { index ->
                    val entry = permissions.getJSONObject(index)
                    add(entry.getString("type") to entry.optInt("kind").takeIf { entry.has("kind") })
                }
            }
        val expected = Nip55.LOGIN_PERMISSIONS.map { it.operation.intentType to it.kind }
        assertEquals(expected, actual)
        assertEquals(actual.size, actual.toSet().size)
    }
}
