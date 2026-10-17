package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class AppStateScopeExceptionHandlerTest {
    @Test
    fun launchedFailureIsReportedWithoutCancellingSiblingWork() {
        val failure = IllegalStateException("boom")
        var reported: Throwable? = null
        var siblingCompleted = false
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Unconfined +
                    appStateScopeExceptionHandler { reported = it },
            )

        try {
            scope.launch { throw failure }
            scope.launch { siblingCompleted = true }

            assertSame(failure, reported)
            assertTrue("a failed mutation must not cancel later sibling work", siblingCompleted)
        } finally {
            scope.cancel()
        }
    }

    /** A failed process-owned bootstrap can be observed and retried without cancelling sibling mutations. */
    @Test
    fun bootstrapFailureAllowsRetryOnTheSupervisedMutationScope() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + appStateScopeExceptionHandler {})
            var stateDispatches = 0
            val stateDispatcher =
                object : CoroutineDispatcher() {
                    /** Records entry through the production state-dispatch boundary. */
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        stateDispatches += 1
                        block.run()
                    }
                }
            val session =
                AppAccountSessionCoordinator(
                    mutationScope = scope,
                    stateDispatcher = stateDispatcher,
                    listenerTeardown = { error("bootstrap must not tear down listeners") },
                    activation = BootstrapOnlyActivation,
                )
            val failure = IllegalStateException("bootstrap failed")
            try {
                val failed = session.bootstrapAttempt { throw failure }
                val observed = runCatching { failed.await() }.exceptionOrNull()
                assertTrue(observed is IllegalStateException)
                assertEquals(failure.message, observed?.message)
                var siblingCompleted = false
                scope.launch { siblingCompleted = true }
                val retry = session.bootstrapAttempt { }
                retry.await()
                assertTrue(siblingCompleted)
                assertTrue("both attempts must enter the coordinator's state dispatcher", stateDispatches >= 2)
            } finally {
                scope.cancel()
            }
        }

    /** Fails if a bootstrap-only attempt accidentally enters account activation or platform delivery. */
    private object BootstrapOnlyActivation : AppAccountSessionActivation {
        override val activeAccount: String? get() = error("bootstrap must not inspect an activation")

        /** Detects native selection work escaping the bootstrap callback. */
        override suspend fun prepare(
            request: AccountSessionActivationRequest,
            generation: Long,
            switchingAccounts: Boolean,
        ): PreparedAccountSessionActivation? = error("bootstrap must not prepare an activation")

        /** Detects account admission escaping the bootstrap callback. */
        override fun canPublish(
            label: String,
            runtime: Int,
        ): Boolean = error("bootstrap must not admit an activation")

        /** Detects unexpected shortcut teardown. */
        override suspend fun hidePreviousAccountShortcuts(): Unit = error("bootstrap must not hide shortcuts")

        /** Detects unexpected active-account publication. */
        override fun publish(
            label: String,
            generation: Long,
            switchingAccounts: Boolean,
            prepared: PreparedAccountSessionActivation,
        ): Unit = error("bootstrap must not publish an activation")

        /** Detects unexpected account-profile enrichment. */
        override suspend fun warmAccountProfile(label: String): Unit = error("bootstrap must not warm a profile")

        /** Detects privacy effects outside the supplied bootstrap callback. */
        override suspend fun configurePrivacy(): Unit = error("bootstrap must not configure privacy")

        /** Detects notification reads outside the supplied bootstrap callback. */
        override suspend fun refreshNotificationSettings(): Unit = error("bootstrap must not read notifications")

        /** Detects unexpected service reconciliation. */
        override suspend fun reconcileNotificationDelivery(includeBackgroundAccountModes: Boolean): Unit = error("bootstrap must not reconcile delivery")

        /** Detects unexpected service-admission inspection. */
        override fun isInForeground(): Boolean = error("bootstrap must not inspect foreground admission")
    }
}
