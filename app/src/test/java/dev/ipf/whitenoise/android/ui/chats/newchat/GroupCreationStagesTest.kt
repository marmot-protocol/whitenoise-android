package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.GroupCreationResultFfi
import dev.ipf.marmotkit.MemberResolutionOutcomeFfi
import dev.ipf.marmotkit.MemberResolutionStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.createdGroupSkippedMembers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes actual setup stages with controlled native suspension, without timers or fake readiness. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupCreationStagesTest {
    /** Work queued on the process scope must recheck admission before calling the native creation boundary. */
    @Test fun queuedCreateAfterAccountChangeNeverEntersNativeWork() =
        runTest {
            var currentAccount = "first"
            val owner = GroupCreationSession { currentAccount == "first" }
            var creates = 0
            var opens = 0
            val attempt =
                async {
                    runGroupCreationStages(owner, {
                        creates++
                        "canonical"
                    }, { opens++ })
                }
            try {
                currentAccount = "second"
                runCurrent()
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, creates)
                assertEquals(0, opens)
            } finally {
                attempt.cancel()
                attempt.join()
            }
        }

    /** Atomic native founding may finish for its captured account but cannot open replacement-account UI. */
    @Test fun heldNativeCreateThenAccountSwitchDoesNotOpenReplacementUi() =
        runTest {
            val capturedAccount = "first"
            var currentAccount = capturedAccount
            val owner = GroupCreationSession { currentAccount == capturedAccount }
            val release = CompletableDeferred<Unit>()
            var creates = 0
            var opens = 0
            val request = NewGroupSubmission("Team", "Plans", emptyList(), null, disappearingMessageSecs = 300L)
            val attempt =
                async {
                    runGroupCreationStages(
                        owner,
                        createOrRetry = {
                            request.createWith { _, _, options ->
                                creates++
                                assertEquals(300uL, options.disappearingMessageSecs)
                                release.await()
                                "canonical"
                            }
                        },
                        openCurrentChat = { opens++ },
                    )
                }
            try {
                runCurrent()
                assertEquals(1, creates)
                currentAccount = "second"
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, opens)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Replacing the runtime while create is held prevents opening in the replacement runtime. */
    @Test fun runtimeReplacementAfterCreateStopsUi() =
        runTest {
            var runtime = 1
            val owner = GroupCreationSession { runtime == 1 }
            val release = CompletableDeferred<Unit>()
            var opens = 0
            val attempt =
                async {
                    runGroupCreationStages(owner, {
                        release.await()
                        "canonical"
                    }, { opens++ })
                }
            try {
                runCurrent()
                runtime = 2
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, opens)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** The IO guard catches replacement after process-scope admission but before FFI invocation. */
    @Test fun runtimeReplacementAtQueuedIoBoundaryDoesNotInvokeNativeCreate() =
        runTest {
            var runtime = 1
            val owner = GroupCreationSession { runtime == 1 }
            val enterIo = CompletableDeferred<Unit>()
            var creates = 0
            val attempt =
                async {
                    runGroupCreationStages(
                        owner,
                        createOrRetry = {
                            enterIo.await()
                            owner.ensureCurrent()
                            creates++
                            "canonical"
                        },
                        openCurrentChat = {},
                    )
                }
            try {
                runCurrent()
                runtime = 2
                enterIo.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, creates)
            } finally {
                enterIo.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Partial creation retains the accepted ID and skipped notice through a failed projection and reopen. */
    @Test fun acceptedCreateThenReadFailureRetriesCanonicalGroup() =
        runTest {
            val owner = GroupCreationSession { true }
            var canonical: String? = null
            var creates = 0
            var opens = 0
            val outcomes =
                listOf(
                    MemberResolutionOutcomeFfi("alice", MemberResolutionStatusFfi.SELECTED, 1u),
                    MemberResolutionOutcomeFfi("bob", MemberResolutionStatusFfi.MISSING_KEY_PACKAGE, 0u),
                )
            repeat(2) { attempt ->
                val result =
                    runCatching {
                        runGroupCreationStages(
                            owner,
                            createOrRetry = {
                                canonical ?: run {
                                    creates++
                                    owner.acceptCreatedGroup(GroupCreationResultFfi("accepted", outcomes)) { it }
                                }
                            },
                            openCurrentChat = {
                                canonical = it
                                opens++
                                if (attempt == 0) error("Projection temporarily unavailable")
                                assertEquals("accepted", it)
                                assertTrue(owner.memberReportCaptured)
                                val skipped =
                                    createdGroupSkippedMembers(
                                        owner.skippedMembers,
                                        owner.memberReportCaptured,
                                        submitted = listOf("alice", "bob"),
                                        memberIds = listOf("self", "alice", "bob"),
                                    ) { it }
                                assertEquals(AppText.Plain("bob"), skipped)
                                assertEquals(
                                    R.string.toast_some_members_not_added,
                                    createdGroupNoticeTitle(showCreatedToast = false, skipped = skipped),
                                )
                            },
                        )
                    }
                assertEquals(attempt == 1, result.isSuccess)
            }
            assertEquals(1, creates)
            assertEquals(2, opens)
        }

    /** Without MDK's member report, the people missing from the created roster are the ones shown as not added. */
    @Test fun unavailableMemberReportFallsBackToTheCreatedRoster() {
        val owner = GroupCreationSession { true }
        owner.acceptCreatedGroup(GroupCreationResultFfi("earlier", emptyList())) { it }
        owner.forgetMemberReport()

        assertEquals(AppText.Plain("bob"), owner.rosterFallback(listOf("self", "ALICE")) { it })
        assertEquals(
            AppText.Resource(R.string.group_members_not_added_count, listOf(2)),
            owner.rosterFallback(listOf("self")) { "" },
        )
        assertNull(owner.rosterFallback(listOf("self", "alice", "bob")) { it })
    }

    /** Resolves the notice for a two-person submission against [memberIds] as the setup screen does after opening. */
    private fun GroupCreationSession.rosterFallback(
        memberIds: List<String>,
        displayName: (String) -> String,
    ) = createdGroupSkippedMembers(
        skippedMembers,
        memberReportCaptured,
        submitted = listOf("alice", "bob"),
        memberIds = memberIds,
        displayName = displayName,
    )

    /** Handled native failure has no canonical ID, so projection may not run. */
    @Test fun unacceptedFailureCannotEnterOpenStage() =
        runTest {
            val stages = mutableListOf<String>()
            runGroupCreationStages(
                GroupCreationSession { true },
                createOrRetry = {
                    stages += "create"
                    null
                },
                openCurrentChat = { stages += "open" },
            )
            assertEquals(listOf("create"), stages)
        }
}
