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

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        appContext = application.applicationContext
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
    }

    @Test
    fun doWorkSucceedsWhenSweepCompletes() =
        runTest {
            val worker = buildWorkerWithSweepOverride(sweepOverride = { })

            assertEquals(Result.success(), worker.doWork())
        }

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

    @Test
    fun eligibleAccountRunsSweepAfterPreflight() =
        runTest {
            val calls = mutableListOf<String>()
            val worker =
                buildWorkerWithSweepOverride(
                    sweepOverride = { calls += "sweep" },
                    accountOverride = {
                        calls += "read"
                        needsRetentionSweep(listOf(account(localSigning = false, externalSigning = true)))
                    },
                )

            assertEquals(Result.success(), worker.doWork())
            assertEquals(listOf("read", "sweep"), calls)
        }

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

    private fun buildWorkerWithSweepOverride(
        sweepOverride: SweepOverride,
        accountOverride: AccountOverride = { true },
    ): DisappearingMessageSweepWorker =
        TestListenableWorkerBuilder
            .from<DisappearingMessageSweepWorker>(appContext, DisappearingMessageSweepWorker::class.java)
            .setWorkerFactory(sweepWorkerFactory(sweepOverride, accountOverride))
            .build()

    private fun sweepWorkerFactory(
        sweepOverride: SweepOverride,
        accountOverride: AccountOverride,
    ): WorkerFactory =
        object : WorkerFactory() {
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
        override fun getApplicationContext(): Context = this
    }
}
