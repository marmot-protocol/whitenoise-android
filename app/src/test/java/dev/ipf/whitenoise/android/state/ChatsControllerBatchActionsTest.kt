package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.ui.chats.reconcileChatListSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Calls the production controller and native command adapter, not a stand-alone batch lambda runner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@Suppress("LargeClass") // Keep causal batch cases beside their shared production-controller/native adapter fixture.
class ChatsControllerBatchActionsTest {
    /** Native batches keep captured targets after filtering and retry confirmed failures only. */
    @Test fun mixedBatchSurvivesFilterRemovalCountsSatisfiedAndRetriesOnlyFailures() =
        runTest {
            val fixture = Fixture(this)
            val owner = fixture.controller.captureChatListActionOwner()!!
            val selection = mutableSetOf(A, B, C)
            var filteredAfterFirst = emptySet<String>()
            val result =
                fixture.controller.runChatListBatch(selection, ChatListBatchCommand.READ, owner) { done, _ ->
                    if (done == 1) {
                        val unreadVisibleIds =
                            fixture.controller.chatRows
                                .map { fixture.controller.projectChatRow(it) }
                                .filter { it.effectiveHasUnread(ACCOUNT_ID) }
                                .map { it.id }
                                .toSet()
                        selection.retainAll(reconcileChatListSelection(selection, unreadVisibleIds))
                        filteredAfterFirst = selection.toSet()
                    }
                }!!
            assertEquals(setOf(B), filteredAfterFirst)
            assertEquals(listOf(A to false, B to false), fixture.commands)
            assertEquals(1, result.changed)
            assertEquals(1, result.satisfied)
            assertEquals(1, result.failed)
            assertEquals(0, result.skipped)
            assertEquals(listOf(B), result.retryIds)
            assertFalse(
                fixture.controller
                    .projectChatRow(fixture.controller.chatRows.first { it.groupIdHex == A })
                    .effectiveHasUnread(ACCOUNT_ID),
            )
            fixture.rejectB = false
            val retry = fixture.controller.runChatListBatch(result.retryIds, ChatListBatchCommand.READ, owner)!!
            assertEquals(1, retry.changed)
            val cumulative = result.withAttempt(retry)
            assertEquals(2, cumulative.changed)
            assertEquals(1, cumulative.satisfied)
            assertEquals(0, cumulative.failed)
            assertEquals(listOf(A to false, B to false, B to false), fixture.commands)
            fixture.controller.onCleared()
        }

    /** A suspended native lookup retains one batch owner and rejects another dispatch. */
    @Test fun duplicateTapIsRejectedWhileNativeLookupIsSuspended() =
        runTest {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeRead = {
                entered.complete(Unit)
                release.await()
            }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val running =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.controller.runChatListBatch(listOf(A, B, C), ChatListBatchCommand.READ, owner)
                }
            entered.await()
            assertNull(fixture.controller.runChatListBatch(listOf(A), ChatListBatchCommand.UNREAD, owner))
            release.complete(Unit)
            assertEquals(1, running.await()!!.changed)
            assertEquals(2, fixture.commands.size)
            fixture.controller.onCleared()
        }

    /** Replacing the account during lookup prevents every native mutation. */
    @Test fun accountReplacementDuringLookupRejectsEveryMutation() =
        runTest {
            val fixture = Fixture(this)
            fixture.beforeRead = {
                WhiteNoiseAppState::class.java
                    .getDeclaredMethod("setActiveAccountRef", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(fixture.state, "replacement")
            }
            val result =
                fixture.controller.runChatListBatch(
                    listOf(A, B, C),
                    ChatListBatchCommand.READ,
                    fixture.controller.captureChatListActionOwner()!!,
                )!!
            assertEquals(3, result.notStarted)
            assertEquals(emptyList<Pair<String, Boolean>>(), fixture.commands)
            fixture.controller.onCleared()
        }

    /** Runtime retirement after a commit stops later targets and prevents stale projection. */
    @Test fun runtimeReplacementAfterFirstCommitStopsRemainingTargetsAndLateProjection() =
        runTest {
            val fixture = Fixture(this)
            fixture.afterCommit = {
                val field =
                    WhiteNoiseAppState::class.java
                        .getDeclaredField("runtimeGeneration\$delegate")
                        .apply { isAccessible = true }
                (field.get(fixture.state) as MutableIntState).intValue++
            }
            val result =
                fixture.controller.runChatListBatch(
                    listOf(A, B, C),
                    ChatListBatchCommand.READ,
                    fixture.controller.captureChatListActionOwner()!!,
                )!!
            assertEquals(listOf(A to false), fixture.commands)
            assertEquals(2, result.notStarted)
            assertEquals(1, result.unconfirmed)
            assertEquals(
                true,
                fixture.controller
                    .projectChatRow(fixture.controller.chatRows.first { it.groupIdHex == A })
                    .effectiveHasUnread(ACCOUNT_ID),
            )
            fixture.controller.onCleared()
        }

    /** Explicit mute, pin and archive commands count satisfied native rows without duplicate writes. */
    @Test fun mutePinAndArchiveUseNativeExplicitStateAndReportSatisfiedTargets() =
        runTest {
            for (command in listOf(
                ChatListBatchCommand.MUTE,
                ChatListBatchCommand.UNMUTE,
                ChatListBatchCommand.PIN,
                ChatListBatchCommand.UNPIN,
                ChatListBatchCommand.ARCHIVE,
                ChatListBatchCommand.RESTORE,
            )) {
                val fixture = Fixture(this)
                fixture.rejectB = false
                val desired =
                    command in
                        setOf(
                            ChatListBatchCommand.MUTE,
                            ChatListBatchCommand.PIN,
                            ChatListBatchCommand.ARCHIVE,
                        )
                fixture.rows.keys.forEach { id ->
                    val targetState = if (id == C) desired else !desired
                    fixture.rows[id] =
                        fixture.rows.getValue(id).copy(
                            muted =
                                if (command in setOf(ChatListBatchCommand.MUTE, ChatListBatchCommand.UNMUTE)) {
                                    targetState
                                } else {
                                    false
                                },
                            pinned =
                                if (command in setOf(ChatListBatchCommand.PIN, ChatListBatchCommand.UNPIN)) {
                                    targetState
                                } else {
                                    false
                                },
                            archived =
                                if (command in setOf(ChatListBatchCommand.ARCHIVE, ChatListBatchCommand.RESTORE)) {
                                    targetState
                                } else {
                                    false
                                },
                        )
                }
                val result =
                    fixture.controller.runChatListBatch(
                        listOf(A, B, C),
                        command,
                        fixture.controller.captureChatListActionOwner()!!,
                    )!!
                assertEquals(command.toString(), 2, result.changed)
                assertEquals(1, result.satisfied)
                assertEquals(0, result.failed)
                assertEquals(listOf(A to desired, B to desired), fixture.commands)
                fixture.controller.onCleared()
            }
        }

    /** Manual pin ordering includes authoritative native rows beyond the retained window. */
    @Test fun orderingUsesCompleteNativePinnedSetOutsideRetainedWindow() =
        runTest {
            val fixture = Fixture(this, setOf(C))
            listOf(A, B, C).forEachIndexed { index, id ->
                fixture.rows[id] = fixture.rows.getValue(id).copy(pinned = true, pinnedPosition = index.toUInt())
            }
            val owner = fixture.controller.captureChatListActionOwner()!!
            assertEquals(ChatListPinMoveResult.MOVED, fixture.controller.movePinnedSelection(setOf(B, C), -1, owner))
            assertEquals(listOf(B, C, A), fixture.lastPinOrder)
            assertEquals(
                setOf(C),
                fixture.controller.chatRows
                    .map { it.groupIdHex }
                    .toSet(),
            )
            assertEquals(
                ChatListPinMoveResult.UNCHANGED,
                fixture.controller.movePinnedSelection(setOf(B, C), -1, owner),
            )
            fixture.controller.onCleared()
        }

    /** Native unread counters already satisfy Mark unread without another setter. */
    @Test fun numericUnreadTargetsStayUnreadWithoutAnotherSetterOrToggle() =
        runTest {
            val fixture = Fixture(this)
            fixture.rows[A] = chatRow(A)
            fixture.rows[B] = chatRow(B)
            fixture.rows[C] = chatRow(C).copy(hasUnread = true, unreadCount = 7uL)
            fixture.rows.values.forEach(fixture.controller::applyChatListRow)
            val selected = mutableSetOf(A, B, C)
            var visibleAfterFirst = emptySet<String>()
            val result =
                fixture.controller.runChatListBatch(
                    selected,
                    ChatListBatchCommand.UNREAD,
                    fixture.controller.captureChatListActionOwner()!!,
                ) { completed, _ ->
                    if (completed == 1) {
                        val visibleIds =
                            fixture.controller.chatRows
                                .filter { it.hasUnread || it.manuallyMarkedUnread }
                                .map { it.groupIdHex }
                                .toSet()
                        selected.retainAll(reconcileChatListSelection(selected, visibleIds))
                        visibleAfterFirst = selected.toSet()
                    }
                }!!
            assertEquals(setOf(A, C), visibleAfterFirst)
            assertEquals(listOf(A to true, B to true), fixture.commands)
            assertEquals(1, result.changed)
            assertEquals(1, result.satisfied)
            assertEquals(1, result.failed)
            assertEquals(3, result.outcomes.size)
            assertEquals(7uL, fixture.rows.getValue(C).unreadCount)
            assertFalse(fixture.rows.getValue(C).manuallyMarkedUnread)
            fixture.controller.onCleared()
        }

    /** Authoritative readback can confirm read and archive commits that returned errors. */
    @Test fun commitThenErrorIsReconciledForReadAndArchive() =
        runTest {
            for (command in listOf(ChatListBatchCommand.READ, ChatListBatchCommand.ARCHIVE)) {
                val fixture = Fixture(this)
                fixture.throwAfterCommit = true
                val result =
                    fixture.controller.runChatListBatch(
                        listOf(A),
                        command,
                        fixture.controller.captureChatListActionOwner()!!,
                    )!!
                assertEquals(command.toString(), 1, result.changed)
                assertEquals(0, result.failed)
                assertEquals(0, result.unconfirmed)
                assertEquals(1, fixture.commands.size)
                fixture.controller.onCleared()
            }
        }

    /** An unreadable completion remains uncertain until explicit read-only recovery. */
    @Test fun unreadableCommitIsNotRetryableUntilReadOnlyReconciliation() =
        runTest {
            val fixture = Fixture(this)
            fixture.throwAfterCommit = true
            fixture.afterCommit = { fixture.readbackUnavailable = true }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result = fixture.controller.runChatListBatch(listOf(A), ChatListBatchCommand.READ, owner)!!
            assertEquals(1, result.unconfirmed)
            assertEquals(emptyList<String>(), result.retryIds)
            val unavailable = fixture.controller.reconcileChatListBatch(result, ChatListBatchCommand.READ, owner)!!
            assertEquals(1, unavailable.unconfirmed)
            assertEquals(1, fixture.commands.size)
            fixture.readbackUnavailable = false
            val confirmed = fixture.controller.reconcileChatListBatch(result, ChatListBatchCommand.READ, owner)!!
            assertEquals(1, confirmed.changed)
            assertEquals(0, confirmed.failed)
            assertEquals(1, fixture.commands.size)
            fixture.controller.onCleared()
        }

    /** A mismatched postcondition ID never replaces the captured row or invites retry. */
    @Test fun wrongPostconditionIdentityRemainsUnconfirmedWithoutApplyingAnotherRow() =
        runTest {
            val fixture = Fixture(this)
            fixture.afterCommit = { fixture.wrongReadback = true }
            val result =
                fixture.controller.runChatListBatch(
                    listOf(A),
                    ChatListBatchCommand.READ,
                    fixture.controller.captureChatListActionOwner()!!,
                )!!
            assertEquals(1, result.unconfirmed)
            assertEquals(emptyList<String>(), result.retryIds)
            assertEquals(
                setOf(A, B, C),
                fixture.controller.chatRows
                    .map { it.groupIdHex }
                    .toSet(),
            )
            fixture.controller.onCleared()
        }

    /** Retrying a confirmed failure preserves another target's unconfirmed result. */
    @Test fun failedTargetCanRetryWhileAnotherAttemptRemainsUnconfirmed() =
        runTest {
            val fixture = Fixture(this)
            fixture.afterCommit = { fixture.readbackUnavailableIds = setOf(A) }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val initial = fixture.controller.runChatListBatch(listOf(A, B), ChatListBatchCommand.READ, owner)!!
            assertEquals(1, initial.unconfirmed)
            assertEquals(1, initial.failed)
            assertEquals(listOf(B), initial.retryIds)
            fixture.rejectB = false
            val retried = fixture.controller.runChatListBatch(initial.retryIds, ChatListBatchCommand.READ, owner)!!
            val combined = initial.withAttempt(retried)
            assertEquals(1, combined.changed)
            assertEquals(1, combined.unconfirmed)
            assertEquals(0, combined.failed)
            assertEquals(listOf(A to false, B to false, B to false), fixture.commands)
            assertEquals(setOf(A), fixture.readbackUnavailableIds)
            fixture.controller.onCleared()
        }

    /** A busy group exhausts only its own deadline; successful targets are never retried with it. */
    @Test fun archiveCommitLockTimeoutContinuesOtherTargetsAndRequiresReadOnlyRecovery() =
        runTest {
            val fixture = Fixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.state.withGroupCommitLock(ACCOUNT, A) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val owner = fixture.controller.captureChatListActionOwner()!!
            val batch =
                async { fixture.controller.runChatListBatch(listOf(A, B, C), ChatListBatchCommand.ARCHIVE, owner) }
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            val result = batch.await()!!
            assertEquals(ChatListBatchOutcome.UNCONFIRMED, result.outcomes[A])
            assertEquals(ChatListBatchOutcome.CHANGED, result.outcomes[B])
            assertEquals(ChatListBatchOutcome.CHANGED, result.outcomes[C])
            assertEquals(setOf(A, B, C), result.attemptedIds)
            assertEquals(emptyList<String>(), result.retryIds)
            assertFalse(fixture.controller.chatListBatchInProgress)
            assertEquals(listOf(B to true, C to true), fixture.commands)
            val retained = fixture.controller.chatRows.first { it.groupIdHex == A }
            assertFalse(
                fixture.controller
                    .projectChatRow(retained)
                    .group.archived,
            )
            release.complete(Unit)
            holder.await()
            runCurrent()
            assertEquals(listOf(B to true, C to true), fixture.commands)

            val checked =
                result.withAttempt(
                    fixture.controller.reconcileChatListBatch(result, ChatListBatchCommand.ARCHIVE, owner),
                )
            assertEquals(ChatListBatchOutcome.FAILED, checked.outcomes[A])
            assertEquals(listOf(A), checked.retryIds)
            val completed =
                checked.withAttempt(
                    fixture.controller.runChatListBatch(checked.retryIds, ChatListBatchCommand.ARCHIVE, owner),
                )
            assertEquals(3, completed.changed)
            assertEquals(0, completed.unconfirmed)
            assertEquals(listOf(B to true, C to true, A to true), fixture.commands)
            fixture.controller.onCleared()
        }

    /** A cancellation-insensitive native return cannot publish through its expired per-target context. */
    @Test fun archiveTimedOutCompletionKeepsUnknownUntilCheckedAndContinuesOtherTargets() =
        runTest {
            val fixture = Fixture(this)
            fixture.archiveUpdater = { account, id, archived ->
                if (id == A) {
                    fixture.commands += id to archived
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        fixture.rows[id] = fixture.rows.getValue(id).copy(archived = archived)
                        emptyGroupRecord(fixture.rows.getValue(id))
                    }
                } else {
                    fixture.state.marmotIo { setGroupArchived(account, id, archived) }
                }
            }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result = fixture.controller.runChatListBatch(listOf(A, B, C), ChatListBatchCommand.ARCHIVE, owner)!!
            assertEquals(ChatListBatchOutcome.UNCONFIRMED, result.outcomes[A])
            assertEquals(2, result.changed)
            assertEquals(emptyList<String>(), result.retryIds)
            assertEquals(true, fixture.rows.getValue(A).archived)
            assertFalse(
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .archived,
            )
            val retained = fixture.controller.chatRows.first { it.groupIdHex == A }
            assertFalse(
                fixture.controller
                    .projectChatRow(retained)
                    .group.archived,
            )
            assertFalse(fixture.controller.chatListBatchInProgress)
            assertEquals(listOf(A to true, B to true, C to true), fixture.commands)

            val checked =
                result.withAttempt(
                    fixture.controller.reconcileChatListBatch(result, ChatListBatchCommand.ARCHIVE, owner),
                )
            assertEquals(3, checked.changed)
            assertEquals(0, checked.unconfirmed)
            assertEquals(
                true,
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .archived,
            )
            assertEquals(listOf(A to true, B to true, C to true), fixture.commands)
            fixture.controller.onCleared()
        }

    /** Batch-owned feedback suppresses the legacy error notice without changing other archive callers. */
    @Test fun batchArchiveDefersFailureNoticeWhileLegacyArchiveStillReportsIt() =
        runTest {
            val fixture = Fixture(this)
            var failures = 0
            val actions =
                ChatListArchiveActions(
                    rowForGroup = { fixture.rows[it] },
                    isCurrent = { true },
                    onChanged = {},
                    updateGroup = { _, _, _, _ -> error("rejected") },
                    onFailure = { failures++ },
                    onSuccess = {},
                )
            val rows = ResolvedArchiveRows(listOf(fixture.rows.getValue(A)), isCurrent = { true })
            actions.setArchivedRows(ACCOUNT, 0L, rows, true, false, notifyFailures = false)
            assertEquals(0, failures)
            actions.setArchivedRows(ACCOUNT, 0L, rows, true, false)
            assertEquals(1, failures)
            fixture.controller.onCleared()
        }

    /** A wrong archive lookup ID fails only its captured target while later targets continue. */
    @Test fun wrongArchiveLookupIdentityFailsOnlyThatTargetAndContinuesBatch() =
        runTest {
            val fixture = Fixture(this)
            fixture.rejectB = false
            fixture.wrongReadbackIds = setOf(A)
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result = fixture.controller.runChatListBatch(listOf(A, B), ChatListBatchCommand.ARCHIVE, owner)!!
            assertEquals(1, result.failed)
            assertEquals(1, result.changed)
            assertEquals(listOf(A), result.retryIds)
            assertEquals(setOf(B), result.attemptedIds)
            assertEquals(listOf(B to true), fixture.commands)
            assertFalse(fixture.rows.getValue(A).archived)
            assertEquals(true, fixture.rows.getValue(B).archived)
            assertFalse(fixture.controller.chatListBatchInProgress)
            assertEquals(
                setOf(A, B, C),
                fixture.controller.chatRows
                    .map { it.groupIdHex }
                    .toSet(),
            )
            fixture.controller.onCleared()
        }

    /** Failed admission never claims that an already archived target was changed. */
    @Test fun failedArchiveLookupNeverClaimsItChangedAnAlreadyArchivedTarget() =
        runTest {
            val fixture = Fixture(this)
            fixture.rows[A] = fixture.rows.getValue(A).copy(archived = true)
            var reads = 0
            fixture.beforeRead = { if (++reads == 1) error("lookup unavailable") }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result = fixture.controller.runChatListBatch(listOf(A), ChatListBatchCommand.ARCHIVE, owner)!!
            assertEquals(0, result.changed)
            assertEquals(1, result.failed)
            assertEquals(emptySet<String>(), result.attemptedIds)
            assertEquals(1, reads)
            val retry = fixture.controller.runChatListBatch(result.retryIds, ChatListBatchCommand.ARCHIVE, owner)!!
            assertEquals(0, retry.changed)
            assertEquals(1, retry.satisfied)
            assertEquals(emptyList<Pair<String, Boolean>>(), fixture.commands)
            fixture.controller.onCleared()
        }

    /** Native pin readback recovers every affected neighbour after a hydration error. */
    @Test fun pinCommitThenHydrationErrorRecoversEveryNeighbourPosition() =
        runTest {
            for ((command, target) in listOf(ChatListBatchCommand.PIN to C, ChatListBatchCommand.UNPIN to A)) {
                val fixture = Fixture(this)
                fixture.rows[A] = fixture.rows.getValue(A).copy(pinned = true, pinnedPosition = 0u)
                fixture.rows[B] = fixture.rows.getValue(B).copy(pinned = true, pinnedPosition = 1u)
                fixture.rows.values.forEach(fixture.controller::applyChatListRow)
                fixture.throwPinAfterCommit = true
                val result =
                    fixture.controller.runChatListBatch(
                        listOf(target),
                        command,
                        fixture.controller.captureChatListActionOwner()!!,
                    )!!
                assertEquals(1, result.changed)
                assertEquals(0, result.unconfirmed)
                for (nativeRow in fixture.rows.values) {
                    val retained = fixture.controller.chatRows.first { it.groupIdHex == nativeRow.groupIdHex }
                    assertEquals(nativeRow.pinned, retained.pinned)
                    assertEquals(nativeRow.pinnedPosition, retained.pinnedPosition)
                }
                assertEquals(1, fixture.commands.size)
                fixture.controller.onCleared()
            }
        }

    /** Read-only pin recovery preserves cumulative counts and restores the complete order. */
    @Test fun pinReadOnlyRecoveryRetainsChangedCountAndRestoresFullOrder() =
        runTest {
            val fixture = Fixture(this)
            fixture.rows[A] = fixture.rows.getValue(A).copy(pinned = true, pinnedPosition = 0u)
            fixture.rows[B] = fixture.rows.getValue(B).copy(pinned = true, pinnedPosition = 1u)
            fixture.rows.values.forEach(fixture.controller::applyChatListRow)
            fixture.throwPinAfterCommit = true
            fixture.afterCommit = { fixture.pinReadbackUnavailable = true }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result = fixture.controller.runChatListBatch(listOf(C), ChatListBatchCommand.PIN, owner)!!
            assertEquals(1, result.unconfirmed)
            assertEquals(emptyList<String>(), result.retryIds)
            fixture.pinReadbackUnavailable = false
            val checked = fixture.controller.reconcileChatListBatch(result, ChatListBatchCommand.PIN, owner)!!
            val cumulative = result.withAttempt(checked)
            assertEquals(1, cumulative.changed)
            assertEquals(0, cumulative.unconfirmed)
            assertEquals(1, fixture.commands.size)
            assertEquals(
                1u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .pinnedPosition,
            )
            assertEquals(
                2u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == B }
                    .pinnedPosition,
            )
            assertEquals(
                0u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == C }
                    .pinnedPosition,
            )
            fixture.controller.onCleared()
        }

    /** Owner retirement keeps a possibly committed archive result uncertain. */
    @Test fun archiveCommitErrorThenOwnerLossCannotBecomeAConfirmedFailure() =
        runTest {
            val fixture = Fixture(this)
            fixture.throwAfterCommit = true
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result =
                fixture.controller.runChatListBatch(listOf(A, B), ChatListBatchCommand.ARCHIVE, owner) { done, _ ->
                    if (done == 1) fixture.replaceAccount()
                }!!
            assertEquals(listOf(A to true), fixture.commands)
            assertEquals(1, result.unconfirmed)
            assertEquals(1, result.notStarted)
            assertEquals(0, result.failed)
            fixture.controller.onCleared()
        }

    /** A missing post-attempt row remains uncertain without an automatic retry. */
    @Test fun missingPostattemptRowRemainsUnknownAndCannotBeRetried() =
        runTest {
            val fixture = Fixture(this)
            fixture.afterCommit = { fixture.rows.remove(A) }
            val owner = fixture.controller.captureChatListActionOwner()!!
            val result = fixture.controller.runChatListBatch(listOf(A), ChatListBatchCommand.READ, owner)!!
            assertEquals(1, result.unconfirmed)
            assertEquals(0, result.skipped)
            assertEquals(emptyList<String>(), result.retryIds)
            val checked = fixture.controller.reconcileChatListBatch(result, ChatListBatchCommand.READ, owner)!!
            assertEquals(1, checked.unconfirmed)
            assertEquals(1, fixture.commands.size)
            fixture.controller.onCleared()
        }

    /** A pre-commit move failure restores the unchanged authoritative pin order. */
    @Test fun movePrecommitFailureReadbackRestoresTheUnchangedNativeOrder() =
        runTest {
            val fixture = Fixture(this)
            fixture.seedPinnedOrder(A, B, C)
            fixture.throwOrderBeforeCommit = true
            val result =
                fixture.controller.movePinnedSelection(
                    setOf(B),
                    -1,
                    fixture.controller.captureChatListActionOwner()!!,
                )
            assertEquals(ChatListPinMoveResult.UNAVAILABLE, result)
            assertEquals(1, fixture.orderCalls)
            assertEquals(
                0u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .pinnedPosition,
            )
            assertEquals(
                1u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == B }
                    .pinnedPosition,
            )
            fixture.controller.onCleared()
        }

    /** A move committed before an error settles its complete native order without a new event. */
    @Test fun moveCommitThenErrorSettlesTheCompleteOrderWithoutAnEvent() =
        runTest {
            val fixture = Fixture(this)
            fixture.seedPinnedOrder(A, B, C)
            fixture.throwOrderAfterCommit = true
            val result =
                fixture.controller.movePinnedSelection(
                    setOf(B),
                    -1,
                    fixture.controller.captureChatListActionOwner()!!,
                )
            assertEquals(ChatListPinMoveResult.MOVED, result)
            assertEquals(listOf(B, A, C), fixture.lastPinOrder)
            assertEquals(1, fixture.orderCalls)
            assertEquals(
                1u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .pinnedPosition,
            )
            assertEquals(
                0u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == B }
                    .pinnedPosition,
            )
            fixture.controller.onCleared()
        }

    /** Unreadable move completion neither claims a failure nor repeats the native mutation. */
    @Test fun moveWithUnreadableCommitDoesNotClaimFailureOrReplay() =
        runTest {
            val fixture = Fixture(this)
            fixture.seedPinnedOrder(A, B, C)
            fixture.throwOrderAfterCommit = true
            fixture.afterOrderCommit = { fixture.pinReadbackUnavailable = true }
            val result =
                fixture.controller.movePinnedSelection(
                    setOf(B),
                    -1,
                    fixture.controller.captureChatListActionOwner()!!,
                )
            assertEquals(ChatListPinMoveResult.UNCONFIRMED, result)
            assertEquals(1, fixture.orderCalls)
            assertEquals(listOf(B, A, C), fixture.lastPinOrder)
            assertEquals(
                0u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .pinnedPosition,
            )
            fixture.controller.onCleared()
        }

    /** A retired owner cannot publish an order after its move may have committed. */
    @Test fun moveOwnerLossAfterCommitCannotApplyTheRetiredAccountsOrder() =
        runTest {
            val fixture = Fixture(this)
            fixture.seedPinnedOrder(A, B, C)
            fixture.throwOrderAfterCommit = true
            fixture.afterOrderCommit = { fixture.replaceAccount() }
            val result =
                fixture.controller.movePinnedSelection(
                    setOf(B),
                    -1,
                    fixture.controller.captureChatListActionOwner()!!,
                )
            assertEquals(ChatListPinMoveResult.UNCONFIRMED, result)
            assertEquals(1, fixture.orderCalls)
            assertEquals(1, fixture.wholeReads)
            assertEquals(
                0u,
                fixture.controller.chatRows
                    .first { it.groupIdHex == A }
                    .pinnedPosition,
            )
            fixture.controller.onCleared()
        }

    private class Fixture(
        scope: TestScope,
        retainedIds: Set<String> = setOf(A, B, C),
    ) {
        val rows =
            java.util.concurrent.ConcurrentHashMap(
                mapOf(
                    A to chatRow(A).copy(manuallyMarkedUnread = true),
                    B to chatRow(B).copy(manuallyMarkedUnread = true),
                    C to chatRow(C),
                ),
            )
        val commands = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        var lastPinOrder = emptyList<String>()
        var throwOrderBeforeCommit = false
        var throwOrderAfterCommit = false
        var afterOrderCommit: (() -> Unit)? = null
        var orderCalls = 0
        var wholeReads = 0
        var throwAfterCommit = false
        var throwPinAfterCommit = false
        var pinReadbackUnavailable = false
        var readbackUnavailable = false
        var readbackUnavailableIds = emptySet<String>()
        var wrongReadback = false
        var wrongReadbackIds = emptySet<String>()
        var rejectB = true
        var archiveUpdater: (suspend (String, String, Boolean) -> AppGroupRecordFfi)? = null
        var beforeRead: (suspend () -> Unit)? = null
        var afterCommit: (() -> Unit)? = null
        val state: WhiteNoiseAppState
        val controller: ChatsController

        fun replaceAccount() {
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setActiveAccountRef", String::class.java)
                .apply { isAccessible = true }
                .invoke(state, "replacement")
        }

        fun seedPinnedOrder(vararg ids: String) {
            ids.forEachIndexed { index, id ->
                rows[id] = rows.getValue(id).copy(pinned = true, pinnedPosition = index.toUInt())
            }
            rows.values.forEach(controller::applyChatListRow)
        }

        init {
            val native =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, args ->
                    when (method.name.substringBefore('-')) {
                        "setChatManuallyUnread" -> {
                            check(args!![0] == ACCOUNT)
                            val id = args[1] as String
                            val unread = args[2] as Boolean
                            commands += id to unread
                            check(id != B || !rejectB) { "rejected" }
                            rows.getValue(id).copy(manuallyMarkedUnread = unread).also {
                                rows[id] = it
                                afterCommit?.invoke()
                                check(!throwAfterCommit) { "commit returned an error" }
                            }
                        }
                        "setPinnedChatOrder" -> {
                            orderCalls++
                            check(!throwOrderBeforeCommit) { "order failed before commit" }
                            @Suppress("UNCHECKED_CAST")
                            val order = args!![1] as List<String>
                            check(
                                order.toSet() ==
                                    rows.values
                                        .filter { it.pinned }
                                        .map { it.groupIdHex }
                                        .toSet(),
                            )
                            lastPinOrder = order
                            order.forEachIndexed { index, id ->
                                rows[id] = rows.getValue(id).copy(pinnedPosition = index.toUInt())
                            }
                            afterOrderCommit?.invoke()
                            check(!throwOrderAfterCommit) { "order committed but return hydration failed" }
                            ChatPinStateFfi(order)
                        }
                        "setChatPinned" -> {
                            val id = args!![1] as String
                            val pinned = args[2] as Boolean
                            commands += id to pinned
                            val old =
                                rows.values
                                    .filter { it.pinned }
                                    .sortedBy { it.pinnedPosition }
                                    .map { it.groupIdHex }
                            val order = if (pinned) listOf(id) + old.filter { it != id } else old.filter { it != id }
                            rows.keys.forEach { key ->
                                val position = order.indexOf(key).takeIf { it >= 0 }?.toUInt()
                                rows[key] =
                                    rows.getValue(key).copy(pinned = position != null, pinnedPosition = position)
                            }
                            afterCommit?.invoke()
                            check(!throwPinAfterCommit) { "pin committed but row hydration failed" }
                            ChatPinStateFfi(order)
                        }
                        "setChatMuted", "clearChatMuted", "chatNotificationSettings" -> {
                            val id = args!![1] as String
                            if (method.name.substringBefore('-') != "chatNotificationSettings") {
                                val muted = method.name.substringBefore('-') == "setChatMuted"
                                commands += id to muted
                                rows[id] = rows.getValue(id).copy(muted = muted)
                            }
                            ChatNotificationSettingsFfi(ACCOUNT, ACCOUNT_ID, id, rows.getValue(id).muted, null, 1L)
                        }
                        "setGroupArchived" -> {
                            val id = args!![1] as String
                            val archived = args[2] as Boolean
                            commands += id to archived
                            rows[id] = rows.getValue(id).copy(archived = archived)
                            afterCommit?.invoke()
                            check(!throwAfterCommit) { "commit returned an error" }
                            emptyGroupRecord(rows.getValue(id))
                        }
                        "listMedia" -> emptyList<Any>()
                        "catchUpAccounts" -> Unit
                        "chatListRow" -> rows[args!![1] as String]
                        "toString" -> "BatchNative"
                        else -> error("Unexpected native call ${method.name}")
                    }
                } as MarmotInterface
            state =
                WhiteNoiseAppState(
                    context = ApplicationProvider.getApplicationContext<Context>(),
                    draftStore = DraftStore.forContext(ApplicationProvider.getApplicationContext()),
                    accountIdHexResolver = { ACCOUNT_ID },
                    accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_ID, true, false, false, true)),
                    activeAccountRef = ACCOUNT,
                    initialMarmotRuntime = AppMarmotRuntime("batch-test", native),
                    marmotIoDispatcher = StandardTestDispatcher(scope.testScheduler),
                    notificationCardCancellationDispatcher = StandardTestDispatcher(scope.testScheduler),
                )
            state.liveSubscriptionOverrides.chatList =
                ChatListLiveSubscriptions(
                    openChatListWindow = { _, _ -> error("unused") },
                    openChats = { _, _ -> error("unused") },
                    presentedChatList = { account, _ ->
                        check(account == ACCOUNT)
                        wholeReads++
                        check(!pinReadbackUnavailable) { "complete pin read unavailable" }
                        rows.values.map { row -> presentedRow(row.groupIdHex).copy(row = row) }
                    },
                    presentedRowByGroup = { account, id ->
                        check(account == ACCOUNT)
                        beforeRead?.invoke()
                        check(!readbackUnavailable && id !in readbackUnavailableIds) { "readback unavailable" }
                        rows[id]?.let { original ->
                            val row =
                                if (
                                    wrongReadback ||
                                    id in wrongReadbackIds
                                ) {
                                    original.copy(groupIdHex = "ff".repeat(16))
                                } else {
                                    original
                                }
                            presentedRow(id).copy(
                                row = row,
                                actions =
                                    noChatRowActions().copy(
                                        canMarkRead = true,
                                        canMarkUnread = true,
                                        canPin = true,
                                        canUnpin = true,
                                        canMute = true,
                                        canUnmute = true,
                                        canArchive = true,
                                        canRestore = true,
                                    ),
                            )
                        }
                    },
                )
            controller =
                ChatsController(
                    state,
                    ACCOUNT,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                    groupArchivedUpdater = { account, id, archived ->
                        archiveUpdater?.invoke(account, id, archived)
                            ?: state.marmotIo { setGroupArchived(account, id, archived) }
                    },
                )
            controller.setChatListVisible(false)
            rows.values.filter { it.groupIdHex in retainedIds }.forEach {
                controller.applyChatListRow(it)
                controller.applyLocalGroupUpdate(emptyGroupRecord(it))
            }
            controller.setChatListVisible(true)
        }
    }

    private companion object {
        const val ACCOUNT = "batch-account"
        val ACCOUNT_ID = "ab".repeat(32)
        val A = "11".repeat(16)
        val B = "22".repeat(16)
        val C = "33".repeat(16)
    }
}
