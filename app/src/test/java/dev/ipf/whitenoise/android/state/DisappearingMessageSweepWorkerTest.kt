package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private typealias SweepOverride = PerformDisappearingMessageSweep
private typealias AccountOverride = HasRetentionSweepAccount

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WhiteNoiseApplication::class)
class DisappearingMessageSweepWorkerTest {
    private lateinit var application: WhiteNoiseApplication
    private lateinit var appContext: Context

    /** Installs a test WorkManager without constructing the app's native runtime. */
    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        appContext = application.applicationContext
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
    }

    /** A completed eligible sweep settles the WorkManager attempt successfully. */
    @Test
    fun doWorkSucceedsWhenSweepCompletes() =
        runTest {
            val worker = buildWorkerWithSweepOverride(sweepOverride = { })

            assertEquals(Result.success(), worker.doWork())
        }

    /** An empty cold inventory never creates AppState or calls the sweep. */
    @Test
    fun emptyColdProcessSkipsRuntimeAndSweep() =
        runTest {
            var sweepCalls = 0
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = { sweepCalls++ },
                    accountOverride = { needsRetentionSweep(emptyList()) },
                )

            assertNull(application.initializedAppState())
            assertEquals(Result.success(), worker.doWork())
            assertEquals(0, sweepCalls)
            assertNull(application.initializedAppState())
        }

    /** Signed-out and non-signing identities cannot admit background retention work. */
    @Test
    fun signedOutAndNonSigningAccountsSkipSweep() =
        runTest {
            var sweepCalls = 0
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = { sweepCalls++ },
                    accountOverride = {
                        needsRetentionSweep(
                            listOf(
                                account(signedOut = true),
                                account(label = "read-only", localSigning = false),
                            ),
                        )
                    },
                )

            assertEquals(Result.success(), worker.doWork())
            assertEquals(0, sweepCalls)
        }

    /** An eligible inventory in a cold process admits the sweep only after the account read. */
    @Test
    fun eligibleColdProcessRunsSweepAfterAuthoritativePreflight() =
        runTest {
            val calls = mutableListOf<String>()
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = {
                        assertEquals(listOf("inventory"), calls)
                        calls += "sweep"
                    },
                    accountOverride = {
                        assertNull(application.initializedAppState())
                        calls += "inventory"
                        needsRetentionSweep(listOf(account(localSigning = false, externalSigning = true)))
                    },
                )

            assertNull(application.initializedAppState())
            assertEquals(Result.success(), worker.doWork())
            assertEquals(listOf("inventory", "sweep"), calls)
        }

    /** An unknown inventory retries rather than treating account eligibility as false. */
    @Test
    fun uncertainEligibilityRetriesWithoutStartingSweep() =
        runTest {
            var sweepCalls = 0
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = { sweepCalls++ },
                    accountOverride = { throw IOException("account store unavailable") },
                )

            assertEquals(Result.retry(), worker.doWork())
            assertEquals(0, sweepCalls)
        }

    /** A sweep failure asks WorkManager for its ordinary backoff retry. */
    @Test
    fun doWorkRetriesTransientSweepFailures() =
        runTest {
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = {
                        throw IOException("offline")
                    },
                )

            assertEquals(Result.retry(), worker.doWork())
        }

    /** Cancellation remains visible to WorkManager instead of becoming a retry result. */
    @Test
    fun doWorkRethrowsCancellation() =
        runTest {
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = {
                        throw CancellationException("cancelled")
                    },
                )

            try {
                worker.doWork()
                error("expected cancellation")
            } catch (_: CancellationException) {
                // expected
            }
        }

    /** A context outside the app's runtime domain has no retention work to run. */
    @Test
    fun doWorkSucceedsWhenApplicationIsNotWhiteNoiseApplication() =
        runTest {
            val wrapped = NonWhiteNoiseApplicationContext(appContext)
            val worker =
                TestListenableWorkerBuilder
                    .from<DisappearingMessageSweepWorker>(wrapped, DisappearingMessageSweepWorker::class.java)
                    .build()

            assertEquals(Result.success(), worker.doWork())
        }

    /** Builds native-shaped inventory rows with independently controlled signing and sign-out flags. */
    private fun account(
        label: String = "alice",
        localSigning: Boolean = true,
        externalSigning: Boolean = false,
        signedOut: Boolean = false,
    ): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = "hex-$label",
            localSigning = localSigning,
            externalSigning = externalSigning,
            signedOut = signedOut,
            running = !signedOut,
        )

    /** Injects account and sweep seams while preserving the worker's real WorkManager entry point. */
    private fun buildWorkerWithSweepOverride(
        sweepOverride: SweepOverride,
        accountOverride: AccountOverride = { true },
    ): DisappearingMessageSweepWorker =
        TestListenableWorkerBuilder
            .from<DisappearingMessageSweepWorker>(appContext, DisappearingMessageSweepWorker::class.java)
            .setWorkerFactory(sweepWorkerFactory(sweepOverride, accountOverride))
            .build()

    /** Restricts test overrides to this worker so other WorkManager workers keep their factory. */
    private fun sweepWorkerFactory(
        sweepOverride: SweepOverride,
        accountOverride: AccountOverride,
    ): WorkerFactory =
        object : WorkerFactory() {
            /** Builds only the requested sweep worker with its injected preflight and sweep seams. */
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                if (workerClassName != DisappearingMessageSweepWorker::class.java.name) return null
                return DisappearingMessageSweepWorker(appContext, workerParameters, sweepOverride, accountOverride)
            }
        }

    private class NonWhiteNoiseApplicationContext(
        base: Context,
    ) : ContextWrapper(base) {
        /** Exposes a foreign application context to exercise the worker's guarded cast. */
        override fun getApplicationContext(): Context = this
    }
}
