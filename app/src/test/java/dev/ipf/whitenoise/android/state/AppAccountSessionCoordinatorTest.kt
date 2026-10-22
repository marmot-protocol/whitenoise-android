package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

/** Exercises the extracted production boundary with suspended platform/native adapters, without booting MDK. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAccountSessionCoordinatorTest {
    /** Automatic succession cannot invalidate an explicit handoff that already won its atomic intent boundary. */
    @Test
    fun conditionalSuccessorReservationPreservesNewerExplicitIntent() {
        val handoff = AccountSwitchLocalSnapshotHandoff()
        val logout = handoff.beginRequest()
        val explicit = handoff.beginRequest("explicit")
        assertNull(handoff.beginRequestIfCurrent(logout, "automatic"))
        assertTrue(handoff.isCurrent(explicit))
        assertEquals(explicit, handoff.captureForAccount("explicit"))
        assertNull(handoff.captureForAccount("automatic"))
        val accepted = handoff.beginRequestIfCurrent(explicit, "accepted")
        assertEquals(explicit + 1, accepted)
        assertEquals(accepted, handoff.captureForAccount("accepted"))
    }

    /** A queued activation cannot enter native preparation while logout owns the actual coordinator lifetime. */
    @Test
    fun lifetimeOwnerFencesNativePreparationAndCancelledWaitHasNoEffects() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val logout =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.withLifetime {
                        acquired.complete(Unit)
                        release.await()
                    }
                }
            acquired.await()
            val queued =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("cancelled"))
                }
            assertTrue(host.events.isEmpty())
            queued.cancelAndJoin()
            release.complete(Unit)
            logout.await()
            assertTrue(host.events.isEmpty())
            assertEquals("original", host.activeAccount)
        }

    /** An explicit queued selection wins automatic succession before the automatic path can call native prepare. */
    @Test
    fun queuedExplicitSelectionSupersedesAutomaticSignOutSuccessor() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Long>()
            val logout =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.withLifetime {
                        entered.complete(owner.retireSelectionsForSignOut())
                        release.await()
                    }
                }
            val generation = entered.await()
            val explicit =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("explicit"))
                }
            release.complete(Unit)
            logout.await()
            assertNotNull(explicit.await())
            val before = host.events.toList()
            assertNull(
                owner.activateAfterSignOutIfCurrent(generation, AccountSessionActivationRequest("automatic")) { true },
            )
            assertEquals(before, host.events)
            assertEquals("explicit", host.activeAccount)
        }

    /** Routing can drain a setup child outside lifetime without giving an older user action a fresh generation. */
    @Test
    fun delayedRoutingCannotOvertakeNewerActivationOrNativePrepare() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val release = CompletableDeferred<Unit>()
            val earlier =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("earlier"), beforePrepare = {
                        release.await()
                        true
                    })
                }
            assertNotNull(owner.activate(AccountSessionActivationRequest("newer")))
            release.complete(Unit)
            assertNull(earlier.await())
            assertEquals("newer", host.activeAccount)
            assertFalse(host.events.contains("prepare:earlier"))
        }

    /** The actual setup controller joins a command whose cancellation finally needs the production lifetime. */
    @Test
    fun setupControllerCancellationCleanupFinishesBeforeActivationTakesLifetime() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val entered = CompletableDeferred<Unit>()
            val base =
                dev.ipf.whitenoise.android.ui.onboarding.setup
                    .FakeSetupClient()
            val client =
                object : dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupClient by base {
                    override suspend fun execute(
                        request: dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest,
                    ): dev.ipf.marmotkit.OnboardingSnapshotFfi? {
                        try {
                            entered.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                owner.withLifetime { host.events += "setup-cleaned" }
                            }
                        }
                    }
                }
            val controller =
                dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupController(
                    dev.ipf.whitenoise.android.ui.onboarding.setup.SETUP_TEST_ACCOUNT,
                    client,
                    backgroundScope,
                    { true },
                    {},
                    {},
                )
            controller.reconnect()
            runCurrent()
            controller.submit(
                dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest(
                    3uL,
                    dev.ipf.marmotkit.OnboardingStepFfi.PROFILE,
                    dev.ipf.marmotkit.OnboardingActionFfi.CONTINUE_WITHOUT,
                ),
            )
            runCurrent()
            entered.await()
            assertNotNull(
                owner.activate(AccountSessionActivationRequest("next"), beforePrepare = {
                    controller.close()
                    true
                }),
            )
            assertTrue(host.events.indexOf("setup-cleaned") < host.events.indexOf("prepare:next"))
        }

    /** A late pending route for the same account may not clear a newer accepted selection. */
    @Test
    fun oldSameAccountRoutingCannotClearNewerSameRefPublication() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val release = CompletableDeferred<Unit>()
            val earlier =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("original"), beforePrepare = { generation ->
                        release.await()
                        owner.withLifetime {
                            if (owner.handoff.isCurrent(generation)) host.activeAccount = null
                        }
                        false
                    })
                }
            assertNotNull(owner.activate(AccountSessionActivationRequest("original")))
            release.complete(Unit)
            assertNull(earlier.await())
            assertEquals("original", host.activeAccount)
        }

    /** Local-ready and frame acknowledgement precede the existing profile/privacy/notification/service sequence. */
    @Test
    fun publishesBeforeFrameWaitAndRefreshesOnlyAfterAcknowledgement() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val frame = CompletableDeferred<Unit>()
            val activation =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(
                        AccountSessionActivationRequest("next"),
                        awaitPostActivationWork = { frame.await() },
                        onActivated = { host.events += "ready" },
                    )
                }
            assertEquals(listOf("prepare:next", "hide", "publish:next", "ready"), host.events)
            assertEquals("next", host.activeAccount)
            frame.complete(Unit)
            assertNotNull(activation.await())
            assertEquals(
                listOf(
                    "prepare:next",
                    "hide",
                    "publish:next",
                    "ready",
                    "profile:next",
                    "privacy",
                    "notifications",
                    "delivery:true",
                ),
                host.events,
            )
        }

    /** A newer queued selection invalidates old preparation, then enters native work after lifetime release. */
    @Test
    fun supersededPreparationCannotClearOrPublishOverNewAccount() =
        runTest {
            val host = Activation()
            val release = CompletableDeferred<Unit>()
            host.beforePrepare = { if (it == "old-request") release.await() }
            val owner = coordinator(backgroundScope, host)
            val old =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("old-request"))
                }
            val newer =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("new-request"))
                }
            assertFalse(newer.isCompleted)
            release.complete(Unit)
            assertNull(old.await())
            assertNotNull(newer.await())
            assertEquals("new-request", host.activeAccount)
            assertEquals(listOf("publish:new-request"), host.events.filter { it.startsWith("publish:") })
        }

    /** The last suspension before publication is shortcut removal, which must retain its own supersession check. */
    @Test
    fun supersededShortcutCleanupCannotPublishOldAccount() =
        runTest {
            val host = Activation()
            val release = CompletableDeferred<Unit>()
            var hides = 0
            host.beforeHide = { if (hides++ == 0) release.await() }
            val owner = coordinator(backgroundScope, host)
            val old =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("old-request"))
                }
            val newer =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("new-request"))
                }
            assertFalse(newer.isCompleted)
            release.complete(Unit)
            assertNull(old.await())
            assertNotNull(newer.await())
            assertEquals("new-request", host.activeAccount)
            assertEquals(1, host.events.count { it.startsWith("publish:") })
        }

    /** Preparation failure preserves the usable account and releases intent so the same boundary can retry. */
    @Test
    fun failedPreparationRetainsCurrentAccountAndAllowsRetry() =
        runTest {
            val host = Activation()
            val failure = IllegalStateException("preload unavailable")
            host.beforePrepare = { throw failure }
            val owner = coordinator(backgroundScope, host)
            val result = runCatching { owner.activate(AccountSessionActivationRequest("next")) }
            assertSame(failure, result.exceptionOrNull())
            assertEquals("original", host.activeAccount)
            assertNotNull(owner.handoff.captureForAccount("original"))
            assertFalse(host.events.any { it.startsWith("publish:") })
            host.beforePrepare = {}
            assertNotNull(owner.activate(AccountSessionActivationRequest("next")))
        }

    /** Cancellation after local-ready keeps accepted state, releases intent and cannot start deferred services. */
    @Test
    fun cancelledFrameWaitRetainsPublishedAccountAndReleasesRequest() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val frame = CompletableDeferred<Unit>()
            val call =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("next"), awaitPostActivationWork = { frame.await() })
                }
            call.cancelAndJoin()
            assertEquals("next", host.activeAccount)
            assertNotNull(owner.handoff.captureForAccount("original"))
            assertFalse(host.events.contains("privacy"))
            assertNotNull(owner.activate(AccountSessionActivationRequest("next")))
            assertTrue(host.events.contains("delivery:false"))
        }

    /** Same-label replacement is still a new runtime; an in-flight privacy refresh cannot admit later services. */
    @Test
    fun runtimeReplacementDuringRefreshRejectsRemainingEffects() =
        runTest {
            val host = Activation()
            host.beforePrivacy = { host.currentRuntimeGeneration++ }
            assertNotNull(coordinator(backgroundScope, host).activate(AccountSessionActivationRequest("next")))
            assertTrue(host.events.contains("privacy"))
            assertFalse(host.events.contains("notifications"))
            assertFalse(host.events.any { it.startsWith("delivery:") })
        }

    /** Revoked account admission stops deferred service work even before the visible label changes. */
    @Test
    fun revokedAccountCannotStartPostFrameRefreshes() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val frame = CompletableDeferred<Unit>()
            val activation =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("next"), awaitPostActivationWork = { frame.await() })
                }
            host.publicationAllowed = false
            frame.complete(Unit)
            assertNotNull(activation.await())
            assertFalse(host.events.contains("privacy"))
            assertFalse(host.events.any { it.startsWith("delivery:") })
        }

    /** A newer same-label request must fence the earlier request's delayed post-frame work. */
    @Test
    fun sameAccountReactivationFencesOlderFrameCompletion() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val frame = CompletableDeferred<Unit>()
            val old =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("next"), awaitPostActivationWork = { frame.await() })
                }
            assertNotNull(owner.activate(AccountSessionActivationRequest("next")))
            val refreshes = host.events.count { it == "privacy" }
            frame.complete(Unit)
            assertNotNull(old.await())
            assertEquals(refreshes, host.events.count { it == "privacy" })
        }

    /** Background startup refreshes settings while retaining the existing foreground service-admission policy. */
    @Test
    fun startupDeliveryUsesForegroundStateAtAdmission() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val request =
                AccountSessionActivationRequest("next", preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION)
            host.foreground = false
            assertNotNull(owner.activate(request))
            assertTrue(host.events.contains("notifications"))
            assertFalse(host.events.any { it.startsWith("delivery:") })
            assertNotNull(owner.activate(request, awaitPostActivationWork = { host.foreground = true }))
            assertTrue(host.events.contains("delivery:false"))
        }

    /** A cancelled UI waiter cannot cancel the process-owned bootstrap; concurrent callers share one attempt. */
    @Test
    fun bootstrapWaitCancellationAndRetryShareProcessAttempt() =
        runTest {
            val owner = coordinator(backgroundScope, Activation())
            val release = CompletableDeferred<Unit>()
            var starts = 0
            val attempt =
                owner.bootstrapAttempt {
                    starts++
                    release.await()
                }
            val waiter = async(UnconfinedTestDispatcher(testScheduler)) { attempt.await() }
            waiter.cancelAndJoin()
            val second = owner.bootstrapAttempt { error("duplicate bootstrap") }
            assertSame(attempt, second)
            assertEquals(1, starts)
            release.complete(Unit)
            second.await()
        }

    /** Controller drainage completes before the shipped listener owner cancels recovery and joins native cleanup. */
    @Test
    fun teardownFencesBeforeSuspensionAndWaitsForControllersBeforeListenerOwner() =
        runTest {
            val events = mutableListOf<String>()
            val setupRelease = CompletableDeferred<Unit>()
            val controllerRelease = CompletableDeferred<Unit>()
            val oldReadRelease = CompletableDeferred<Unit>()
            val host = Activation().apply { beforePrepare = { oldReadRelease.await() } }
            val owner = coordinator(backgroundScope, host, events)
            val oldActivation =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("old-selection"))
                }
            val teardown =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.prepareForAccountTeardown {
                        events += "capture"
                        setupRelease.await()
                        AccountSessionTeardown(
                            restartNotifications = true,
                            invalidateActiveSession = { events += "invalidate" },
                            closeControllers = {
                                events += "controllers"
                                controllerRelease.await()
                            },
                        )
                    }
                }
            assertEquals(listOf("capture"), events)
            oldReadRelease.complete(Unit)
            assertNull(oldActivation.await())
            assertEquals("original", host.activeAccount)
            assertFalse(teardown.isCompleted)
            setupRelease.complete(Unit)
            assertEquals(listOf("capture", "invalidate", "controllers"), events)
            assertFalse(teardown.isCompleted)
            controllerRelease.complete(Unit)
            assertTrue(teardown.await())
            assertEquals(
                listOf("capture", "invalidate", "controllers", "network", "push", "listener", "unread"),
                events,
            )
        }

    /** A newly created identity fences an older selection even when that selection's native read completes later. */
    @Test
    fun createdIdentitySupersedesSuspendedSelection() =
        runTest {
            val host = Activation()
            val release = CompletableDeferred<Unit>()
            host.beforePrepare = { release.await() }
            val owner = coordinator(backgroundScope, host)
            val selection =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("old-request"))
                }
            val publication =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.publishCreatedIdentity("created") { host.activeAccount = "created" }
                }
            release.complete(Unit)
            assertNull(selection.await())
            publication.await()
            assertEquals("created", host.activeAccount)
            assertFalse(host.events.any { it.startsWith("publish:") })
        }

    /** Final local sign-out retires an older selection without closing the shared process runtime. */
    @Test
    fun signedOutIdentitySupersedesSuspendedSelection() =
        runTest {
            val host = Activation()
            val release = CompletableDeferred<Unit>()
            host.beforePrepare = { release.await() }
            val owner = coordinator(backgroundScope, host)
            val selection =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.activate(AccountSessionActivationRequest("old-request"))
                }
            val publication =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.publishSignedOutIdentity { host.activeAccount = null }
                }
            release.complete(Unit)
            assertNull(selection.await())
            publication.await()
            assertNull(host.activeAccount)
            assertEquals(listOf("prepare:old-request"), host.events)
        }

    /** Import enrichment cannot republish Ready or start warmup after a different account takes ownership. */
    @Test
    fun importedIdentityCompletionCannotRepublishAfterAccountSwitch() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val receipt = checkNotNull(owner.activate(AccountSessionActivationRequest("imported")))
            var ready = false
            var warmed = false
            owner.finishImportedIdentity(
                receipt = receipt,
                refreshSettings = { owner.activate(AccountSessionActivationRequest("other")) },
                publishReady = { ready = true },
                warmProfile = { warmed = true },
            )
            assertEquals("other", host.activeAccount)
            assertFalse(ready)
            assertFalse(warmed)
        }

    /** Completion of an earlier A activation cannot acquire the receipt of a later A-B-A selection. */
    @Test
    fun importedIdentityRetainsOriginalReceiptAcrossABA() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val receipt = checkNotNull(owner.activate(AccountSessionActivationRequest("imported")))
            assertNotNull(owner.activate(AccountSessionActivationRequest("other")))
            assertNotNull(owner.activate(AccountSessionActivationRequest("imported")))
            owner.finishImportedIdentity(
                receipt,
                refreshSettings = { error("old import cannot refresh a newer selection") },
                publishReady = { error("old import cannot publish Ready") },
                warmProfile = { error("old import cannot warm a newer selection") },
            )
        }

    /** A replacement runtime with the same account label cannot adopt an old import's completion. */
    @Test
    fun importedIdentityRetainsOriginalRuntimeReceipt() =
        runTest {
            val host = Activation()
            val owner = coordinator(backgroundScope, host)
            val receipt = checkNotNull(owner.activate(AccountSessionActivationRequest("imported")))
            host.currentRuntimeGeneration++
            owner.finishImportedIdentity(
                receipt,
                refreshSettings = { error("old import cannot refresh a replacement runtime") },
                publishReady = { error("old import cannot publish into a replacement runtime") },
                warmProfile = { error("old import cannot warm a replacement runtime") },
            )
        }

    /** Extraction must preserve the caller's continuation dispatcher across platform/native suspensions. */
    @Test
    fun activationImportAndTeardownRetainCallerDispatcher() =
        runTest {
            val expected = currentCoroutineContext()[ContinuationInterceptor]
            val host = Activation()
            host.beforePrepare = { assertSame(expected, currentCoroutineContext()[ContinuationInterceptor]) }
            val owner = coordinator(backgroundScope, host)
            val receipt = checkNotNull(owner.activate(AccountSessionActivationRequest("imported")))
            owner.finishImportedIdentity(
                receipt,
                refreshSettings = { assertSame(expected, currentCoroutineContext()[ContinuationInterceptor]) },
                publishReady = {},
                warmProfile = {},
            )
            owner.prepareForAccountTeardown {
                AccountSessionTeardown(
                    restartNotifications = false,
                    invalidateActiveSession = {},
                    closeControllers = { assertSame(expected, currentCoroutineContext()[ContinuationInterceptor]) },
                )
            }
        }

    /** Uses deterministic state dispatch and the real teardown owner rather than a fake lifetime manager. */
    private fun TestScope.coordinator(
        scope: CoroutineScope,
        host: Activation,
        teardownEvents: MutableList<String> = mutableListOf(),
    ) = AppAccountSessionCoordinator(
        mutationScope = CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        stateDispatcher = UnconfinedTestDispatcher(testScheduler),
        listenerTeardown = {
            AppRuntimeListenerTeardownOwner(
                cancelNetworkRecovery = { teardownEvents += "network" },
                cancelPushWakeDrain = { teardownEvents += "push" },
                cancelListener = { teardownEvents += "listener" },
                clearUnreadRefresh = { teardownEvents += "unread" },
                receiverActive = MutableStateFlow(false),
            )
        },
        activation = host,
    )

    /** Records adapter effects while production code owns ordering and lifetime admission. */
    private class Activation : AppAccountSessionActivation {
        override var activeAccount: String? = "original"
        var currentRuntimeGeneration = 1
        val events = mutableListOf<String>()
        var foreground = true
        var publicationAllowed = true
        var beforePrepare: suspend (String) -> Unit = {}
        var beforeHide: suspend () -> Unit = {}
        var beforePrivacy: suspend () -> Unit = {}

        /** Simulates a cancellable native projection read without duplicating lifetime checks. */
        override suspend fun prepare(
            request: AccountSessionActivationRequest,
            generation: Long,
            switchingAccounts: Boolean,
        ): PreparedAccountSessionActivation {
            events += "prepare:${request.label}"
            val runtime = currentRuntimeGeneration
            beforePrepare(request.label)
            return PreparedAccountSessionActivation(runtime, StartupSelfProfilePresentation(null, emptyList()), null)
        }

        /** Models the existing native/runtime admission result. */
        override fun canPublish(
            label: String,
            runtime: Int,
        ): Boolean = publicationAllowed && runtime == currentRuntimeGeneration

        /** Exposes the last suspending platform step before publication. */
        override suspend fun hidePreviousAccountShortcuts() {
            events += "hide"
            beforeHide()
        }

        /** Records the visible state transaction. */
        override fun publish(
            label: String,
            generation: Long,
            switchingAccounts: Boolean,
            prepared: PreparedAccountSessionActivation,
        ) {
            events += "publish:$label"
            activeAccount = label
        }

        /** Records self-profile enrichment. */
        override suspend fun warmAccountProfile(label: String) {
            events += "profile:$label"
        }

        /** Exposes a suspending runtime-configuration boundary. */
        override suspend fun configurePrivacy() {
            events += "privacy"
            beforePrivacy()
        }

        /** Records native-backed notification projection. */
        override suspend fun refreshNotificationSettings() {
            events += "notifications"
        }

        /** Records service admission and its background-account mode. */
        override suspend fun reconcileNotificationDelivery(includeBackgroundAccountModes: Boolean) {
            events += "delivery:$includeBackgroundAccountModes"
        }

        /** Supplies mutable foreground state to the production service gate. */
        override fun isInForeground(): Boolean = foreground
    }
}
