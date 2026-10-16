package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the extracted production boundary with suspended platform/native adapters, without booting MDK. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAccountSessionCoordinatorTest {
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

    /** A newer selection can finish while an older native preload is suspended; the old result cannot publish. */
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
            assertNotNull(owner.activate(AccountSessionActivationRequest("new-request")))
            release.complete(Unit)
            assertNull(old.await())
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
            assertNotNull(owner.activate(AccountSessionActivationRequest("new-request")))
            release.complete(Unit)
            assertNull(old.await())
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
            val controllerRelease = CompletableDeferred<Unit>()
            val owner = coordinator(backgroundScope, Activation(), events)
            val teardown =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    owner.prepareForAccountTeardown {
                        events += "capture"
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
            owner.publishCreatedIdentity("created") { host.activeAccount = "created" }
            release.complete(Unit)
            assertNull(selection.await())
            assertEquals("created", host.activeAccount)
            assertFalse(host.events.any { it.startsWith("publish:") })
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

    /** Uses deterministic state dispatch and the real teardown owner rather than a fake lifetime manager. */
    private fun TestScope.coordinator(
        scope: CoroutineScope,
        host: Activation,
        teardownEvents: MutableList<String> = mutableListOf(),
    ) = AppAccountSessionCoordinator(
        mutationScope = CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        recoveryScope = scope,
        stateDispatcher = UnconfinedTestDispatcher(testScheduler),
        catchUpWindow = null,
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
        override var currentRuntimeGeneration = 1
        val events = mutableListOf<String>()
        var foreground = true
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
        override fun canPublish(label: String, runtime: Int): Boolean = runtime == currentRuntimeGeneration

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
