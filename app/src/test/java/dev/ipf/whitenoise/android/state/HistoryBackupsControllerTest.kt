package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BackgroundBackupOutcomeFfi
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.marmotkit.BackupRestoreStatusFfi
import dev.ipf.marmotkit.BackupRestoreSubscription
import dev.ipf.marmotkit.BackupSettingsFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.RecoveryWordsFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class HistoryBackupsControllerTest {
    @Test fun statusFollowsMdkSettingsAndSchedulesOnlyForTheWriter() =
        runTest {
            val fake = FakeBackups()
            var scheduled = 0
            val controller = controller(fake, onWriting = { scheduled++ })
            fake.settings = BackupSettingsFfi(enabled = false, thisDeviceWrites = false)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.Off, controller.state.value.status)
            assertEquals(0, scheduled)
            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = false)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.On(thisDeviceWrites = false), controller.state.value.status)
            assertEquals(0, scheduled)
            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = true)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.On(thisDeviceWrites = true), controller.state.value.status)
            assertEquals(1, scheduled)
        }

    @Test fun aBuildWithoutDeviceGroupsShowsUnavailableRatherThanAFailure() =
        runTest {
            val fake = FakeBackups().apply { settingsError = refused("roster_unsupported") }
            val controller = controller(fake)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.Unavailable, controller.state.value.status)
            fake.settingsError = MarmotKitException.Runtime("io")
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.LoadFailed, controller.state.value.status)
        }

    @Test fun wrongWordsKeepTheSetupAndARightAnswerTurnsBackupsOn() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            val setup = controller.state.value.setup!!
            assertEquals(WORDS, setup.words)
            assertEquals(listOf(3, 9), setup.confirmPositions)
            controller.continueToConfirm()
            assertTrue(
                controller.state.value.setup!!
                    .confirming,
            )
            // Shown once: confirmation keeps only the positions.
            assertTrue(
                controller.state.value.setup!!
                    .words
                    .isEmpty(),
            )
            assertEquals(
                listOf(3, 9),
                controller.state.value.setup!!
                    .confirmPositions,
            )

            fake.confirmError = refused("recovery_words_mismatch")
            controller.confirm(listOf("nope", "nope"))
            runCurrent()
            assertEquals(HistoryBackupProblem.WrongWords, controller.state.value.problem)
            assertTrue(
                controller.state.value.setup!!
                    .confirming,
            )
            assertNull(controller.state.value.running)

            fake.confirmError = null
            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = true)
            controller.confirm(listOf(" Gamma ", "india"))
            runCurrent()
            assertEquals(listOf("Gamma", "india"), fake.confirmed.last())
            assertNull(controller.state.value.setup)
            assertNull(controller.state.value.problem)
            assertEquals(HistoryBackupStatus.On(thisDeviceWrites = true), controller.state.value.status)
            assertEquals(0, fake.cancelledWords)
        }

    @Test fun cancellingOrLeavingSetupForgetsTheWordsHereAndInMdk() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            controller.cancelSetup()
            runCurrent()
            assertNull(controller.state.value.setup)
            assertEquals(1, fake.cancelledWords)
            assertEquals(0, fake.turnedOff)

            controller.beginSetup()
            runCurrent()
            controller.close()
            runCurrent()
            assertNull(controller.state.value.setup)
            assertEquals(2, fake.cancelledWords)
            // A closed controller sends nothing more.
            controller.beginSetup()
            runCurrent()
            assertEquals(2, fake.begun)
        }

    @Test fun leavingWhileWordsAreBeingMadeStillForgetsThem() =
        runTest {
            val fake = FakeBackups()
            val gate = CompletableDeferred<Unit>()
            fake.beginGate = gate
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            controller.close()
            runCurrent()
            assertEquals(0, fake.cancelledWords)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fake.cancelledWords)
            assertNull(controller.state.value.setup)
        }

    @Test fun setupCannotBeCancelledWhileAConfirmationIsSent() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            controller.continueToConfirm()
            val gate = CompletableDeferred<Unit>()
            fake.confirmGate = gate
            controller.confirm(listOf("gamma", "india"))
            runCurrent()
            controller.cancelSetup()
            runCurrent()
            assertEquals(0, fake.cancelledWords)
            assertNotNull(controller.state.value.setup)
            gate.complete(Unit)
            runCurrent()
            assertNull(controller.state.value.setup)
            assertEquals(0, fake.cancelledWords)
        }

    @Test fun anExpiredSetupIsDroppedAndBusyKeepsIt() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            fake.confirmError = refused("device_group_busy")
            controller.confirm(listOf("gamma", "india"))
            runCurrent()
            assertEquals(HistoryBackupProblem.Busy, controller.state.value.problem)
            assertNotNull(controller.state.value.setup)
            fake.confirmError = refused("recovery_words_not_started")
            controller.confirm(listOf("gamma", "india"))
            runCurrent()
            assertEquals(HistoryBackupProblem.SetupExpired, controller.state.value.problem)
            assertNull(controller.state.value.setup)
        }

    @Test fun oneActionRunsAtATime() =
        runTest {
            val fake = FakeBackups()
            val gate = CompletableDeferred<Unit>()
            fake.beginGate = gate
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            assertEquals(HistoryBackupAction.BeginSetup, controller.state.value.running)
            controller.turnOff()
            controller.beginSetup()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fake.begun)
            assertEquals(0, fake.turnedOff)
            assertNull(controller.state.value.running)
        }

    @Test fun aRetiredOwnerNeverAppliesLateAnswers() =
        runTest {
            val fake = FakeBackups()
            var current = true
            val gate = CompletableDeferred<Unit>()
            fake.beginGate = gate
            val controller = controller(fake, isCurrent = { current })
            controller.beginSetup()
            runCurrent()
            current = false
            gate.complete(Unit)
            runCurrent()
            assertNull(controller.state.value.setup)
            assertNull(controller.state.value.problem)
        }

    @Test fun restoreReportsProgressRefusalsAndCancellation() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            fake.restoreError = refused("backup_recovery_words")
            controller.beginRestore("not words")
            runCurrent()
            assertEquals(HistoryBackupProblem.InvalidRestoreWords, controller.state.value.problem)
            assertNull(controller.state.value.restore)

            fake.restoreError = null
            fake.restoreStatus = status(BackupRestorePhaseFfi.DISCOVERING, operation = "ab", revision = 2u)
            controller.beginRestore(WORDS.joinToString(" "))
            runCurrent()
            assertEquals(WORDS.joinToString(" "), fake.restoredWith.last())
            val progress = controller.state.value.restore!!
            assertTrue(progress.running)
            assertEquals("ab", progress.operation)

            fake.restoreStatus = status(BackupRestorePhaseFfi.CANCELLED, operation = null, revision = 3u, events = 4u)
            controller.cancelRestore()
            runCurrent()
            assertEquals(listOf("ab"), fake.cancelledRestores)
            assertEquals(
                BackupRestorePhaseFfi.CANCELLED,
                controller.state.value.restore!!
                    .phase,
            )
            assertEquals(
                4L,
                controller.state.value.restore!!
                    .importedMessages,
            )
            assertFalse(
                controller.state.value.restore!!
                    .running,
            )
        }

    @Test fun recoveryWordsNeverAppearInStateText() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            val text = controller.state.value.toString()
            WORDS.forEach { word -> assertFalse("state text leaked $word", text.contains(word)) }
            assertTrue(text.contains("12 redacted"))
        }

    @Test fun refusalCodesMapToTheirAdvice() {
        assertEquals(HistoryBackupProblem.Unavailable, historyBackupProblem(refused("roster_unsupported")))
        assertEquals(HistoryBackupProblem.RosterUnavailable, historyBackupProblem(refused("roster_unavailable")))
        assertEquals(HistoryBackupProblem.RestoreBusy, historyBackupProblem(refused("backup_restore_busy")))
        assertEquals(HistoryBackupProblem.InvalidRestoreWords, historyBackupProblem(refused("backup_recovery_key")))
        assertEquals(HistoryBackupProblem.Failed, historyBackupProblem(refused("something_new")))
        assertEquals(
            HistoryBackupProblem.Failed,
            historyBackupProblem(MarmotKitException.Runtime("roster_unsupported")),
        )
        assertEquals(
            HistoryBackupProblem.Failed,
            historyBackupProblem(IllegalStateException("device group refused: roster_unsupported")),
        )
    }

    @Test fun backgroundVisitRunsOnlyWritersAndRetriesFailures() =
        runTest {
            val fake = FakeBackups()
            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = false)
            assertEquals(HistoryBackupVisit.NothingToBackUp, runDueHistoryBackups(fake, listOf("a", "b"), BUDGET))
            assertTrue(fake.passes.isEmpty())

            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = true)
            assertEquals(HistoryBackupVisit.Ran, runDueHistoryBackups(fake, listOf("a", "b"), BUDGET))
            assertEquals(listOf("a", "b"), fake.passes)

            fake.passError = MarmotKitException.Runtime("transport closed")
            assertEquals(HistoryBackupVisit.Retry, runDueHistoryBackups(fake, listOf("a"), BUDGET))

            fake.passError = null
            fake.settingsError = MarmotKitException.Runtime("io")
            assertEquals(HistoryBackupVisit.Retry, runDueHistoryBackups(fake, listOf("a"), BUDGET))
        }

    private fun TestScope.controller(
        fake: FakeBackups,
        isCurrent: () -> Boolean = { true },
        onWriting: () -> Unit = {},
    ): HistoryBackupsController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return HistoryBackupsController(
            marmot = fake,
            accountRef = ACCOUNT,
            scope = this,
            isCurrent = isCurrent,
            dispatcher = dispatcher,
            onWriting = onWriting,
        )
    }

    private class FakeBackups(
        delegate: MarmotInterface = unsupported(),
    ) : MarmotInterface by delegate {
        var settings = BackupSettingsFfi(enabled = false, thisDeviceWrites = false)
        var settingsError: Exception? = null
        var confirmError: Exception? = null
        var restoreError: Exception? = null
        var passError: Exception? = null
        var beginGate: CompletableDeferred<Unit>? = null
        var confirmGate: CompletableDeferred<Unit>? = null
        var restoreStatus = status(BackupRestorePhaseFfi.IDLE, operation = null, revision = 0u)
        var begun = 0
        var cancelledWords = 0
        var turnedOff = 0
        val confirmed = mutableListOf<List<String>>()
        val restoredWith = mutableListOf<String>()
        val cancelledRestores = mutableListOf<String>()
        val passes = mutableListOf<String>()

        override suspend fun backupSettings(accountRef: String): BackupSettingsFfi {
            settingsError?.let { throw it }
            return settings
        }

        override suspend fun beginRecoveryWords(accountRef: String): RecoveryWordsFfi {
            begun++
            beginGate?.await()
            return RecoveryWordsFfi(WORDS, listOf(3u, 9u))
        }

        override suspend fun confirmRecoveryWords(
            accountRef: String,
            answers: List<String>,
        ) {
            confirmed += answers
            confirmGate?.await()
            confirmError?.let { throw it }
        }

        override suspend fun cancelRecoveryWords(accountRef: String) {
            cancelledWords++
        }

        override suspend fun turnOffBackups(accountRef: String) {
            turnedOff++
        }

        override suspend fun beginBackupRestore(
            accountRef: String,
            words: String,
        ): BackupRestoreStatusFfi {
            restoredWith += words
            restoreError?.let { throw it }
            return restoreStatus
        }

        override suspend fun cancelBackupRestore(
            accountRef: String,
            operation: String,
        ): Boolean {
            cancelledRestores += operation
            return true
        }

        override suspend fun backupRestoreStatus(accountRef: String): BackupRestoreStatusFfi = restoreStatus

        override suspend fun watchBackupRestore(accountRef: String): BackupRestoreSubscription {
            throw MarmotKitException.Runtime("no watch in tests")
        }

        override suspend fun runDueBackupPass(accountRef: String): BackgroundBackupOutcomeFfi {
            passError?.let { throw it }
            passes += accountRef
            return BackgroundBackupOutcomeFfi.FINISHED_ATTEMPT
        }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val BUDGET = 60_000L
        val WORDS =
            listOf(
                "alpha",
                "beta",
                "Gamma",
                "delta",
                "echo",
                "foxtrot",
                "golf",
                "hotel",
                "india",
                "juliet",
                "kilo",
                "lima",
            )

        fun refused(code: String) = MarmotKitException.Runtime("device group refused: $code")

        fun status(
            phase: BackupRestorePhaseFfi,
            operation: String?,
            revision: ULong,
            events: ULong = 0u,
        ) = BackupRestoreStatusFfi(
            operation = operation,
            phase = phase,
            importedBundles = 0u,
            importedEvents = events,
            failedArchives = 0u,
            failedBundles = 0u,
            skippedBundles = 0u,
            revision = revision,
        )

        fun unsupported(): MarmotInterface {
            val type = MarmotInterface::class.java
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "toString" -> "HistoryBackupsTestRuntime"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as MarmotInterface
        }
    }
}
