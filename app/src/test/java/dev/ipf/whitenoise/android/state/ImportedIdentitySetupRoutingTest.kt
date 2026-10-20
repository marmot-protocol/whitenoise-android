package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingSubscription
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/** A bounded receiver barrier, so setup-ready activation proceeds well inside each step's three-second timeout. */
private const val RECEIVER_TIMEOUT_MILLIS = 100L

/** Legacy import completion must preserve MDK's pending setup/recovery admission for both signer types. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportedIdentitySetupRoutingTest {
    /** A delayed real onReady continuation cannot supersede a newer same-runtime selection. */
    @Test
    fun delayedSetupReadyCannotStealNewerSelection() = assertSetupReadySelection(replaceSelection = true)

    /** Conditional allocation must not reject its own newly accepted setup activation. */
    @Test
    fun currentSetupReadyActivatesWithoutSelfRejection() = assertSetupReadySelection(replaceSelection = false)

    /** Holds setup-ready's account refresh, optionally selects another account, then checks who became active. */
    private fun assertSetupReadySelection(replaceSelection: Boolean) =
        runBlocking {
            val fixture = Fixture(amber = false, recoveryRequired = false)
            val read = HeldNativeCall<List<AccountSummaryFfi>>()
            try {
                runWithMainLooperPumping { fixture.state.importIdentity("nsec1" + "q".repeat(58)) }
                val controller = checkNotNull(fixture.state.accountSetup.controller)
                runWithMainLooperPumping {
                    while (controller.state.value.busy || controller.state.value.snapshot == null) delay(1)
                }
                fixture.pending = fixture.pending.copy(ready = true)
                fixture.accountsReply = read::hold
                val epoch = fixture.state.runtimeGeneration
                val lifetime =
                    controller.javaClass
                        .getDeclaredField("lifetime")
                        .apply { isAccessible = true }
                        .get(controller) as kotlinx.coroutines.Job
                val existingJobs = lifetime.children.toSet()
                controller.openChats()
                runWithMainLooperPumping { read.awaitEntered() }
                val readyCommand = lifetime.children.filterNot { it in existingJobs }.single()
                if (replaceSelection) {
                    assertTrue(runWithMainLooperPumping { fixture.state.setActiveAccount("existing") })
                }
                read.release(fixture.accountList)
                runWithMainLooperPumping { readyCommand.join() }
                assertEquals(epoch, fixture.state.runtimeGeneration)
                val expectedAccount = if (replaceSelection) "existing" else fixture.imported.label
                assertEquals(expectedAccount, fixture.state.activeAccountRef)
                assertEquals(AppPhase.Ready, fixture.state.phase)
            } finally {
                fixture.accountsReply = null
                read.release(fixture.accountList)
                runWithMainLooperPumping { fixture.state.accountSetup.close() }
            }
        }

    /** Closing a real mount retires its native command and signer admission while preserving the runtime. */
    @Test
    fun closedProductionSetupClientRejectsNormalActionsBeforeNativeOrSignerUi() =
        runBlocking {
            val fixture = Fixture(amber = false, recoveryRequired = false)
            try {
                runWithMainLooperPumping { fixture.state.importIdentity("nsec1" + "q".repeat(58)) }
                val controller = checkNotNull(fixture.state.accountSetup.controller)
                val client =
                    controller.javaClass
                        .getDeclaredField("client")
                        .apply { isAccessible = true }
                        .get(controller) as dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupClient
                runWithMainLooperPumping { fixture.state.accountSetup.close() }
                val before = fixture.calls.toList()
                for (action in listOf(
                    dev.ipf.marmotkit.OnboardingActionFfi.RETRY,
                    dev.ipf.marmotkit.OnboardingActionFfi.CANCEL_REPAIR,
                    dev.ipf.marmotkit.OnboardingActionFfi.RECONNECT_SIGNER,
                )) {
                    val result =
                        runCatching {
                            client.execute(
                                dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest(
                                    3uL,
                                    dev.ipf.marmotkit.OnboardingStepFfi.RELAYS,
                                    action,
                                ),
                            )
                        }
                    assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                    assertEquals(before, fixture.calls.toList())
                }
            } finally {
                runWithMainLooperPumping { fixture.state.accountSetup.close() }
            }
        }

    /** A public wipe queued at the native-login return retires that original import receipt. */
    @Test
    fun nsecReceiptCannotActivateAfterQueuedPublicWipeRestoresRuntime() = assertOriginalImportReceipt(recovery = false)

    /** Explicit legacy recovery carries the same original producer receipt into its acceptance gate. */
    @Test
    fun recoveryReceiptCannotActivateAfterQueuedPublicWipeRestoresRuntime() {
        assertOriginalImportReceipt(recovery = true)
    }

    private fun assertOriginalImportReceipt(recovery: Boolean) =
        runBlocking {
            lateinit var fixture: Fixture
            var wipe: kotlinx.coroutines.Deferred<dev.ipf.marmotkit.WipeOutcomeFfi?>? = null
            fixture =
                Fixture(amber = false, recoveryRequired = false, onLegacyLogin = {
                    wipe =
                        async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            fixture.state.signOutAndWipeActiveAccount()
                        }
                })
            try {
                val result =
                    runCatching {
                        runWithMainLooperPumping {
                            val identity = "nsec1" + "q".repeat(58)
                            if (recovery) {
                                fixture.state.recoverIncompleteIdentitySetup(identity)
                            } else {
                                fixture.state.importIdentity(identity)
                            }
                        }
                    }
                checkNotNull(wipe).await()
                assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertFalse(fixture.state.activeAccountRef == fixture.imported.label)
                assertTrue(fixture.calls.contains("signOutAndWipe"))
            } finally {
                wipe?.cancel()
                wipe?.join()
                runWithMainLooperPumping { fixture.state.accountSetup.close() }
            }
        }

    /** An nsec receipt cannot replace unfinished setup with an imported-success announcement. */
    @Test
    fun nsecImportKeepsPendingSetup() = assertPendingImport(amber = false, recoveryRequired = false)

    /** Amber's reconciled signer still requires the same native setup admission. */
    @Test
    fun amberImportKeepsPendingSetup() = assertPendingImport(amber = true, recoveryRequired = false)

    /** A native recovery marker remains authoritative even when no setup controller can be mounted. */
    @Test
    fun nsecImportKeepsRequiredRecovery() = assertPendingImport(amber = false, recoveryRequired = true)

    /** External signer import cannot announce readiness before explicit native recovery either. */
    @Test
    fun amberImportKeepsRequiredRecovery() = assertPendingImport(amber = true, recoveryRequired = true)

    /** Exercises the real import/Amber facade and the setup gate before any activation receipt exists. */
    private fun assertPendingImport(
        amber: Boolean,
        recoveryRequired: Boolean,
    ) = runBlocking {
        val fixture = Fixture(amber, recoveryRequired)
        val state = fixture.state
        try {
            if (amber) {
                runWithMainLooperPumping { state.loginWithAmber() }
                assertTrue(fixture.calls.contains("registerExternalSigner"))
                assertNull(state.amberSignInStage)
            } else {
                assertEquals(
                    IdentityImportOutcome.Success,
                    runWithMainLooperPumping { state.importIdentity("nsec1" + "q".repeat(58)) },
                )
                assertTrue(fixture.calls.contains("login"))
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("existing", state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertNull("pending imports must not announce completed activation", state.transientNotice)
            assertNull("the fake import must reach setup admission without an import failure", state.toast)
            assertFalse(fixture.profileRequests.contains(fixture.imported.accountIdHex))
            assertFalse(fixture.calls.contains("notificationSettings"))
            if (recoveryRequired) {
                assertTrue(state.onboardingRecoveryRequired(fixture.imported.label))
                assertNull(state.accountSetup.controller)
            } else {
                assertEquals(fixture.imported.label, state.accountSetup.controller?.account)
                assertFalse(state.accountSetup.eligible(fixture.imported))
                val controller = checkNotNull(state.accountSetup.controller)
                withTimeout(3_000) {
                    while (
                        !controller.state.value.error &&
                        (controller.state.value.snapshot == null || controller.state.value.busy)
                    ) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertFalse("setup reconnect errors must fail the fixture", controller.state.value.error)
                val checkpoint = controller.state.value.snapshot
                assertEquals(fixture.imported.label, checkpoint?.accountIdHex)
                assertTrue("the mounted setup must run native preflight", fixture.calls.contains("runOnboarding"))
            }
        } finally {
            runWithMainLooperPumping { state.accountSetup.close() }
        }
        assertEquals(fixture.openedSubscriptions.get(), fixture.closedSubscriptions.get())
        if (!recoveryRequired) assertEquals(1, fixture.openedSubscriptions.get())
    }

    /** Joins Main-owned setup jobs while advancing Robolectric's paused looper, including IO returns. */
    private suspend fun <T> runWithMainLooperPumping(block: suspend () -> T): T =
        coroutineScope {
            val call = async(Dispatchers.Main.immediate) { withTimeout(3_000) { block() } }
            try {
                while (!call.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
                call.await()
            } finally {
                call.cancel()
            }
        }

    /** Supplies native setup eligibility while retaining a previously usable account and Ready shell. */
    private class Fixture(
        amber: Boolean,
        private val recoveryRequired: Boolean,
        private val onLegacyLogin: (() -> Unit)? = null,
    ) {
        val calls = ConcurrentLinkedQueue<String>()
        val profileRequests = ConcurrentLinkedQueue<String>()
        val openedSubscriptions = AtomicInteger()
        val closedSubscriptions = AtomicInteger()
        private val existing = AccountSummaryFfi("existing", "aa".repeat(32), true, false, false, true)
        val imported = AccountSummaryFfi("bb".repeat(32), "bb".repeat(32), !amber, amber, false, true)
        var pending = setupSnapshot(actions = emptyList()).copy(accountIdHex = imported.label)
        val accountList get() = listOf(existing, imported)

        // The account list is a synchronous binding, so a scenario holds it on its IO thread.
        var accountsReply: (() -> List<AccountSummaryFfi>)? = null
        private val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                val name = method.name.substringBefore('-')
                calls += name
                when (name) {
                    "accountIdHex" -> imported.accountIdHex
                    "beginExternalSignerOnboarding" -> failSuspendedCall(arguments)
                    "loginExternalSigner" -> imported
                    "registerExternalSigner" -> Unit
                    "signOutAndWipe" ->
                        dev.ipf.marmotkit.WipeOutcomeFfi(
                            groupsLeft = 0u,
                            groupLeaveFailures = emptyList(),
                            keyPackagesDeleted = 0u,
                            keyPackageFailures = emptyList(),
                            localCleanup = dev.ipf.marmotkit.LocalCleanupReportFfi(false, "scripted retained runtime"),
                        )
                    "listAccounts" -> {
                        val reply = accountsReply
                        accountsReply = null
                        reply?.invoke() ?: accountList
                    }
                    "attachmentDownloadPolicy" -> AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                    "beginAttachmentPermissionUpdate" -> 1uL
                    "setAttachmentAutomaticPermission" -> true
                    "accountUnreadSummary", "chatList" -> emptyList<Any>()
                    "onboardingRecoveryRequired" -> arguments?.firstOrNull() == imported.label && recoveryRequired
                    "onboardingSnapshot" -> pending.takeIf { arguments?.firstOrNull() == imported.label }
                    "subscribeOnboarding" -> subscription()
                    "runOnboarding" -> pending
                    "toString" -> "ImportedIdentitySetupMarmotFake"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected Marmot call: $name")
                }
            } as MarmotInterface

        val state: WhiteNoiseAppState

        init {
            val context = ApplicationProvider.getApplicationContext<Context>()
            state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { null },
                    accounts = listOf(existing),
                    activeAccountRef = existing.label,
                    initialMarmotRuntime = AppMarmotRuntime("test", marmot),
                    profileReader = { null },
                    profileRefreshRequest = { profileRequests += it },
                    identityLoginCalls = loginCalls(),
                    // Setup-ready activation waits for the receiver barrier, which this fake never satisfies.
                    notificationReceiverTimeoutMillis = { RECEIVER_TIMEOUT_MILLIS },
                )
            state.amberPublicKeyRequest = { imported.accountIdHex }
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setPhase", AppPhase::class.java)
                .apply { isAccessible = true }
                .invoke(state, AppPhase.Ready)
        }

        /** Returns an import receipt only after the legacy identity-only path declines setup ownership. */
        private fun loginCalls() =
            object : IdentityLoginCalls {
                /** Records the actual legacy-login boundary used by nsec import. */
                override suspend fun login(
                    nsec: String,
                    relays: List<String>,
                    keyPackageRelays: List<String>,
                ): AccountSummaryFfi {
                    calls += "login"
                    onLegacyLogin?.invoke()
                    return imported
                }

                /** Recovery commands are outside these read-only eligibility scenarios. */
                override suspend fun loginRecoveringIncompleteSetup(
                    nsec: String,
                    relays: List<String>,
                    keyPackageRelays: List<String>,
                    acknowledgePossibleKeyPackageOrphan: Boolean,
                ): AccountSummaryFfi {
                    calls += "loginRecoveringIncompleteSetup"
                    checkNotNull(onLegacyLogin) { "unexpected destructive recovery" }.invoke()
                    return imported
                }
            }

        /** Bypasses native Android cleaner setup for this inert Robolectric subscription, as other fixtures do. */
        private fun subscription(): OnboardingSubscription {
            openedSubscriptions.incrementAndGet()
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            val subscription =
                unsafeClass
                    .getMethod("allocateInstance", Class::class.java)
                    .invoke(unsafe, PendingOnboardingSubscription::class.java) as PendingOnboardingSubscription
            return subscription.apply {
                checkpoint = pending
                onClose = { closedSubscriptions.incrementAndGet() }
            }
        }

        /** Delivers the typed legacy-only refusal through the same suspended Amber boundary as production. */
        private fun failSuspendedCall(arguments: Array<out Any?>?): Any {
            @Suppress("UNCHECKED_CAST")
            val continuation = arguments?.last() as Continuation<Any?>
            continuation.resumeWithException(MarmotKitException.OnboardingActionUnavailable())
            return COROUTINE_SUSPENDED
        }
    }

    /** Holds only explicit fixture state and never allocates or releases a native handle. */
    private class PendingOnboardingSubscription : OnboardingSubscription(NoPointer) {
        lateinit var checkpoint: OnboardingSnapshotFfi
        lateinit var onClose: () -> Unit

        /** Provides the same pending native revision mounted by the account setup route. */
        override fun snapshot() = checkpoint

        /** A terminated stream retains its last known pending checkpoint. */
        override suspend fun next(): OnboardingSnapshotFfi? = null

        /** Records release of the fake subscription without entering native cleanup. */
        override fun close() = onClose()
    }
}
