package dev.ipf.whitenoise.android.ui.chats.newchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            var policies = 0
            var opens = 0
            val attempt =
                async {
                    runGroupCreationStages(owner, {
                        creates++
                        "canonical"
                    }, { policies++ }, { opens++ })
                }
            try {
                currentAccount = "second"
                runCurrent()
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, creates)
                assertEquals(0, policies)
                assertEquals(0, opens)
            } finally {
                attempt.cancel()
                attempt.join()
            }
        }

    /** An accepted native create keeps its captured-account retention commit but cannot open the replacement UI. */
    @Test fun heldNativeCreateThenAccountSwitchFinishesCapturedPolicyWithoutOpening() =
        runTest {
            val capturedAccount = "first"
            var currentAccount = capturedAccount
            val owner =
                GroupCreationSession(
                    nativeOwner = { true },
                    currentOwner = { currentAccount == capturedAccount },
                )
            val release = CompletableDeferred<Unit>()
            val policies = mutableListOf<Pair<String, String>>()
            var creates = 0
            var opens = 0
            val request = NewGroupSubmission("Team", "Plans", emptyList(), null)
            val attempt =
                async {
                    runGroupCreationStages(
                        owner,
                        createOrRetry = {
                            request.createWith { _, _, _, _ ->
                                creates++
                                release.await()
                                "canonical"
                            }
                        },
                        applyCapturedPolicy = { policies += capturedAccount to it },
                        openCurrentChat = { opens++ },
                    )
                }
            try {
                runCurrent()
                assertEquals(1, creates)
                currentAccount = "second"
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(listOf(capturedAccount to "canonical"), policies)
                assertEquals(0, opens)
            } finally {
                release.complete(Unit)
                attempt.cancel()
                attempt.join()
            }
        }

    /** Replacing the runtime while create is held prevents starting any policy stage in the replacement runtime. */
    @Test fun runtimeReplacementAfterCreateStopsNativePolicyAndUi() =
        runTest {
            var runtime = 1
            val owner = GroupCreationSession(nativeOwner = { runtime == 1 }, currentOwner = { runtime == 1 })
            val release = CompletableDeferred<Unit>()
            var policies = 0
            var opens = 0
            val attempt =
                async {
                    runGroupCreationStages(owner, {
                        release.await()
                        "canonical"
                    }, { policies++ }, { opens++ })
                }
            try {
                runCurrent()
                runtime = 2
                release.complete(Unit)
                assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, policies)
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
                        applyCapturedPolicy = {},
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

    /** Handled native failure has no canonical ID, so neither policy nor projection may run. */
    @Test fun unacceptedFailureCannotEnterPolicyOrOpenStages() =
        runTest {
            val stages = mutableListOf<String>()
            runGroupCreationStages(
                GroupCreationSession { true },
                createOrRetry = {
                    stages += "create"
                    null
                },
                applyCapturedPolicy = { stages += "policy" },
                openCurrentChat = { stages += "open" },
            )
            assertEquals(listOf("create"), stages)
        }
}
