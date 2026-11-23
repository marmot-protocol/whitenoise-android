package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.marmotkit.BackupRestoreStatusFfi
import dev.ipf.marmotkit.BackupSettingsFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.RecoveryWordsFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryBackupsControllerTest {
    /** Tracks the enablement and writer role returned by MarmotKit. */
    @Test fun statusFollowsMdkSettings() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            fake.settings = BackupSettingsFfi(enabled = false, thisDeviceWrites = false)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.Off, controller.state.value.status)
            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = false)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.On(thisDeviceWrites = false), controller.state.value.status)
            fake.settings = BackupSettingsFfi(enabled = true, thisDeviceWrites = true)
            controller.refresh()
            runCurrent()
            assertEquals(HistoryBackupStatus.On(thisDeviceWrites = true), controller.state.value.status)
        }

    /** Leaves the screen in loading state when there is no eligible owner. */
    @Test fun withoutAnEligibleAccountTheScreenStaysLoadingAndSendsNothing() =
        runTest {
            val controller = HistoryBackupsController(access = null, scope = this)
            controller.refresh()
            controller.beginSetup()
            runCurrent()
            assertEquals(HistoryBackupsState(), controller.state.value)
        }

    /** Explains why backups are unavailable when device groups are disabled. */
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

    /** Allows a confirmation retry without generating new words. */
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

    /** Clears unfinished words when setup is dismissed or the screen leaves. */
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

    /** Discards words created by an in-flight request after the screen leaves. */
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
            // Both the retired begin and close() ask MDK to forget; forgetting twice is harmless.
            assertTrue(fake.cancelledWords >= 1)
            assertNull(controller.state.value.setup)
        }

    /** Keeps setup alive while confirmation is in progress. */
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

    /** Ends setup once confirmation succeeds even if status cannot reload. */
    @Test fun anAcceptedConfirmationEndsSetupEvenIfTheFollowUpReadFails() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            fake.settingsError = MarmotKitException.Runtime("io")
            controller.confirm(listOf("gamma", "india"))
            runCurrent()
            assertNull(controller.state.value.setup)
            assertEquals(HistoryBackupStatus.LoadFailed, controller.state.value.status)
        }

    /** Lets an admitted confirmation settle before cleaning up the screen. */
    @Test fun leavingDuringAConfirmationLetsItSettleBeforeForgetting() =
        runTest {
            val fake = FakeBackups()
            val controller = controller(fake)
            controller.beginSetup()
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            fake.confirmGate = gate
            controller.confirm(listOf("gamma", "india"))
            runCurrent()
            controller.close()
            runCurrent()
            // The forget waits for the admitted confirmation, which holds the setup lock.
            assertEquals(0, fake.cancelledWords)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fake.confirmed.size)
            assertEquals(1, fake.cancelledWords)
        }

    /** Cleans up words received after the account or runtime changes. */
    @Test fun wordsMadeForARetiredOwnerAreForgottenAtOnce() =
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
            assertEquals(1, fake.cancelledWords)
        }

    /** Distinguishes an expired setup from a refusal that allows a retry. */
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

    /** Ignores another action until the current action settles. */
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

    /** Drops native results that arrive after ownership changes. */
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

    /** Follows restore state and maps refusals and cancellation into screen updates. */
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

    /** Redacts recovery words in the state’s string representation. */
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

    /** Maps native refusal codes to the matching user-facing guidance. */
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

    /** Visits multiple accounts within a shared time budget and reports failures. */
    @Test fun aVisitRunsEveryAccountWithinOneBudgetAndReportsFailures() =
        runTest {
            val passes = mutableListOf<String>()
            assertTrue(runDueHistoryBackupPasses(listOf("a", "b"), BUDGET) { passes += it })
            assertEquals(listOf("a", "b"), passes)
            // One failing account does not stop the others, but the visit is retried.
            val failing: suspend (String) -> Unit = { if (it == "a") error("closed") else passes += it }
            assertFalse(runDueHistoryBackupPasses(listOf("a", "b"), BUDGET, failing))
            assertEquals(listOf("a", "b", "b"), passes)
            // A pass that outlives the budget is detached and retried on the next visit.
            assertFalse(runDueHistoryBackupPasses(listOf("a"), BUDGET) { CompletableDeferred<Unit>().await() })
        }

    private fun TestScope.controller(
        fake: FakeBackups,
        isCurrent: () -> Boolean = { true },
    ): HistoryBackupsController = HistoryBackupsController(fake.apply { current = isCurrent }, scope = this)

    private class FakeBackups : HistoryBackupsAccess {
        var current: () -> Boolean = { true }
        var settings = BackupSettingsFfi(enabled = false, thisDeviceWrites = false)
        var settingsError: Exception? = null
        var confirmError: Exception? = null
        var restoreError: Exception? = null
        var beginGate: CompletableDeferred<Unit>? = null
        var confirmGate: CompletableDeferred<Unit>? = null
        var restoreStatus = status(BackupRestorePhaseFfi.IDLE, operation = null, revision = 0u)
        var begun = 0
        var cancelledWords = 0
        var turnedOff = 0
        val confirmed = mutableListOf<List<String>>()
        val restoredWith = mutableListOf<String>()
        val cancelledRestores = mutableListOf<String>()

        override fun isCurrent(): Boolean = current()

        override suspend fun settings(): BackupSettingsFfi {
            settingsError?.let { throw it }
            return settings
        }

        override suspend fun beginWords(): RecoveryWordsFfi {
            begun++
            beginGate?.await()
            return RecoveryWordsFfi(WORDS, listOf(3u, 9u))
        }

        override suspend fun confirmWords(answers: List<String>) {
            confirmed += answers
            confirmGate?.await()
            confirmError?.let { throw it }
        }

        override suspend fun forgetWords() {
            cancelledWords++
        }

        override suspend fun turnOff() {
            turnedOff++
        }

        override suspend fun beginRestore(words: String): BackupRestoreStatusFfi {
            restoredWith += words
            restoreError?.let { throw it }
            return restoreStatus
        }

        override suspend fun cancelRestore(operation: String) {
            cancelledRestores += operation
        }

        override suspend fun restoreStatus(): BackupRestoreStatusFfi = restoreStatus

        override suspend fun followRestore(onStatus: (BackupRestoreStatusFfi) -> Unit) = Unit
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
    }
}
