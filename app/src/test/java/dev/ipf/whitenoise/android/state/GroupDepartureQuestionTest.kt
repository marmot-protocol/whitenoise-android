package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupInviteDeclineResultFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Leaving as one of several admins steps down first, and MDK may hold that step back (#3289). A departure cancelled
 * while the user is still deciding must withdraw its question and change nothing, rather than demoting and leaving
 * later when someone taps Continue anyway. An admin transfer whose grant already landed must report the partial
 * state when the user waits on the step-down, instead of ending quietly with two admins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupDepartureQuestionTest {
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    /** Cancelling the leave while its question is open withdraws the question; no demotion or leave follows. */
    @Test
    fun cancellingTheLeaveWithdrawsItsQuestion() =
        runBlocking {
            val state = appState()
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = conversationTimelineTestGroup().copy(admins = listOf(SELF, OTHER_ADMIN)),
                    initialMemberSnapshot = GroupMemberSnapshot(roster()),
                )
            // Outside runBlocking's children, so a leave that ignores cancellation fails this test rather than hang.
            val leave = CoroutineScope(Job()).async(start = CoroutineStart.UNDISPATCHED) { controller.leaveGroup() }
            try {
                awaitWithMainLooper { state.groupChangeConfirmations.pending != null }

                leave.cancel()
                awaitWithMainLooper { leave.isCompleted }
            } finally {
                state.groupChangeConfirmations.pending?.answer(proceed = false)
            }

            assertTrue(leave.isCancelled)
            assertNull("the question outlived its departure", state.groupChangeConfirmations.pending)
            assertEquals(1, calls.count { it == "selfDemoteAdminDetailed" })
            assertFalse("the group was left without an answer", "leaveGroup" in calls)
        }

    /** Waiting on the step-down after the grant landed reports the partial transfer instead of ending quietly. */
    @Test
    fun waitingOnTheStepDownReportsThePartialTransfer() =
        runBlocking {
            val state = appState()
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = conversationTimelineTestGroup().copy(admins = listOf(SELF)),
                    initialMemberSnapshot = GroupMemberSnapshot(roster()),
                )
            rosterTracker(controller).transition(GroupRosterRefreshEvent.SUCCEEDED)
            val successor = roster().single { it.memberIdHex == OTHER_ADMIN }
            val transfer =
                CoroutineScope(Job()).async(start = CoroutineStart.UNDISPATCHED) { controller.transferAdmin(successor) }
            try {
                awaitWithMainLooper { state.groupChangeConfirmations.pending != null }
                checkNotNull(state.groupChangeConfirmations.pending).answer(proceed = false)
                awaitWithMainLooper { transfer.isCompleted }
            } finally {
                state.groupChangeConfirmations.pending?.answer(proceed = false)
            }

            assertFalse("waiting after the grant ended the transfer silently", transfer.isCancelled)
            assertFalse(transfer.await())
            val commits = calls.filter { it.endsWith("Detailed") }
            assertEquals(listOf("promoteAdminDetailed", "selfDemoteAdminDetailed"), commits)
            assertEquals(AppText.Resource(R.string.toast_granted_but_couldnt_step_down), state.toast?.title)
        }

    /** An admin invitee is asked first, and declines only after agreeing to the step-down MDK requires. */
    @Test
    fun adminInviteAsksThenDeclinesAfterSteppingDown() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val controller = ConversationController(appState = appState(inviteRuntime(invite)), initialGroup = invite)

            assertFalse(controller.declineInvite())
            assertTrue(controller.inviteDeclineStepDownRequested)
            assertFalse("Decline reached MDK before the user agreed", "declineGroupInvite" in calls)

            assertTrue(controller.confirmInviteDeclineStepDown())
            assertFalse(controller.inviteDeclineStepDownRequested)
            assertEquals(
                listOf("declineGroupInvite", "selfDemoteAdminDetailed", "declineGroupInvite"),
                calls.filter { it == "declineGroupInvite" || it == "selfDemoteAdminDetailed" },
            )
            assertFalse(controller.group.pendingConfirmation)
            assertTrue(controller.group.archived)
        }

    /** A member invitee declines directly, without a question or any change to group roles. */
    @Test
    fun memberInviteDeclinesWithoutSteppingDown() =
        runBlocking {
            val invite = conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(OTHER_ADMIN))
            val controller = ConversationController(appState = appState(inviteRuntime(invite)), initialGroup = invite)

            assertTrue(controller.declineInvite())
            assertFalse(controller.inviteDeclineStepDownRequested)
            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertFalse("selfDemoteAdminDetailed" in calls)
        }

    /** Cancelling the step-down question declines nothing and keeps the invitee an admin. */
    @Test
    fun cancellingTheStepDownQuestionChangesNothing() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val controller = ConversationController(appState = appState(inviteRuntime(invite)), initialGroup = invite)

            assertFalse(controller.declineInvite())
            controller.cancelInviteDeclineStepDown()

            assertFalse(controller.inviteDeclineStepDownRequested)
            assertFalse("a cancelled question still allowed a step-down", controller.confirmInviteDeclineStepDown())
            assertFalse("declineGroupInvite" in calls)
            assertFalse("selfDemoteAdminDetailed" in calls)
            assertTrue(controller.group.pendingConfirmation)
            assertTrue(SELF in controller.group.admins)
        }

    /** When the app's copy missed the admin role, MDK's refusal raises the question instead of a silent step-down. */
    @Test
    fun anUnexpectedAdminRefusalAsksBeforeSteppingDown() =
        runBlocking {
            val invite = conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(OTHER_ADMIN))
            val runtime = inviteRuntime(invite, selfAdmin = true)
            val controller = ConversationController(appState = appState(runtime), initialGroup = invite)

            assertFalse(controller.declineInvite())
            assertTrue(controller.inviteDeclineStepDownRequested)
            assertFalse("the step-down ran without consent", "selfDemoteAdminDetailed" in calls)

            assertTrue(controller.confirmInviteDeclineStepDown())
            assertEquals(1, calls.count { it == "selfDemoteAdminDetailed" })
            assertFalse(controller.group.pendingConfirmation)
        }

    /** Consent given for one invitation never steps down for a newer invitation to the same group. */
    @Test
    fun consentDoesNotCarryOverToANewerInvitation() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(
                    pendingConfirmation = true,
                    admins = listOf(SELF, OTHER_ADMIN),
                    viaWelcomeMessageIdHex = "f1".repeat(32),
                )
            val controller = ConversationController(appState = appState(inviteRuntime(invite)), initialGroup = invite)
            assertFalse(controller.declineInvite())

            controller.applyGroupStateForTest(invite.copy(viaWelcomeMessageIdHex = "f2".repeat(32)))

            assertFalse(controller.inviteDeclineStepDownRequested)
            assertFalse(controller.confirmInviteDeclineStepDown())
            assertFalse("declineGroupInvite" in calls)
            assertFalse("selfDemoteAdminDetailed" in calls)
        }

    /**
     * A sole admin keeps the pending invite and gets the same guidance as Leave in one tap, without a question that
     * would promise a step-down MDK refuses.
     */
    @Test
    fun soleAdminInviteKeepsTheGroupAdmin() =
        runBlocking {
            val invite = conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF))
            val state = appState(inviteRuntime(invite))
            val controller = ConversationController(appState = state, initialGroup = invite)

            assertFalse(controller.declineInvite())
            assertFalse(controller.inviteDeclineStepDownRequested)
            assertEquals(0, calls.count { it == "declineGroupInvite" })
            assertFalse("selfDemoteAdminDetailed" in calls)
            assertTrue(controller.group.pendingConfirmation)
            assertEquals(listOf(SELF), controller.group.admins)
            assertEquals(AppText.Resource(R.string.toast_make_another_admin_before_leaving), state.toast?.title)
        }

    /** When this copy still lists another admin, MDK's last-admin refusal stays the fallback after the question. */
    @Test
    fun staleSecondAdminFallsBackToMdkLastAdminRefusal() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val state = appState(inviteRuntime(invite, lastAdmin = true))
            val controller = ConversationController(appState = state, initialGroup = invite)

            assertFalse(controller.declineInvite())
            assertTrue(controller.inviteDeclineStepDownRequested)
            assertFalse(controller.confirmInviteDeclineStepDown())

            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertTrue(controller.group.pendingConfirmation)
            assertEquals(AppText.Resource(R.string.toast_make_another_admin_before_leaving), state.toast?.title)
        }

    /** An answer given while another conversation change holds the lock keeps the question open and changes nothing. */
    @Test
    fun aBusyConversationKeepsTheStepDownQuestion() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val release = CompletableDeferred<Unit>()
            val controller =
                ConversationController(
                    appState = appState(inviteRuntime(invite)),
                    initialGroup = invite,
                    groupArchivedUpdater = { _, _, archived ->
                        release.await()
                        invite.copy(archived = archived)
                    },
                )
            assertFalse(controller.declineInvite())
            val scope = CoroutineScope(Job())
            val archive = scope.async(start = CoroutineStart.UNDISPATCHED) { controller.setArchived(true) }
            try {
                awaitWithMainLooper { controller.mutationInFlight }

                assertFalse(controller.confirmInviteDeclineStepDown())
                assertTrue("a busy lock dropped the user's answer", controller.inviteDeclineStepDownRequested)
                assertFalse("declineGroupInvite" in calls)
            } finally {
                release.complete(Unit)
                archive.await()
            }
        }

    /** Waiting on the step-down leaves the same invitation pending with its original role. */
    @Test
    fun waitingOnAdminDeclineKeepsTheInvite() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val state = appState(inviteRuntime(invite, holdStepDown = true))
            val controller = ConversationController(appState = state, initialGroup = invite)
            assertFalse(controller.declineInvite())
            val decline =
                CoroutineScope(Job()).async(start = CoroutineStart.UNDISPATCHED) {
                    controller.confirmInviteDeclineStepDown()
                }
            try {
                awaitWithMainLooper { state.groupChangeConfirmations.pending != null }
                checkNotNull(state.groupChangeConfirmations.pending).answer(proceed = false)
                awaitWithMainLooper { decline.isCompleted }
            } finally {
                decline.cancel()
                state.groupChangeConfirmations.pending?.answer(proceed = false)
            }

            assertTrue(decline.isCancelled)
            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertTrue(controller.group.pendingConfirmation)
            assertTrue(SELF in controller.group.admins)
        }

    /** The test thread is Robolectric's main thread, so its looper runs between polls for main-thread steps. */
    private suspend fun awaitWithMainLooper(done: () -> Boolean) {
        withTimeout(QUESTION_TIMEOUT_MILLIS) {
            while (!done()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(POLL_MILLIS)
            }
        }
    }

    /** Creates one signed-in account with the supplied native runtime for the departure. */
    private fun appState(runtime: MarmotInterface = catchingUpRuntime()) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { SELF },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = SELF,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            initialMarmotRuntime = AppMarmotRuntime("test", runtime),
        )

    /**
     * Refuses admin declines before demotion and optionally holds back the step-down while catching up. [selfAdmin]
     * and [lastAdmin] are MDK's own view of the roles, which can differ from the app's copy of [invite].
     */
    private fun inviteRuntime(
        invite: AppGroupRecordFfi,
        holdStepDown: Boolean = false,
        selfAdmin: Boolean = SELF in invite.admins,
        lastAdmin: Boolean = invite.admins == listOf(SELF),
    ): MarmotInterface {
        var isAdmin = selfAdmin
        val steppedDown = invite.copy(admins = listOf(OTHER_ADMIN))
        val mutation = testGroupMutationResult(roster().map { it.memberIdHex }, steppedDown.admins)
        return Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
            _,
            method,
            arguments,
            ->
            val name = method.name.substringBefore('-')
            calls += name
            when (name) {
                "npub" -> null
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "groupCommitDelivery" -> null
                "declineGroupInvite" ->
                    if (isAdmin) {
                        failSuspendedNativeCall(arguments, MarmotKitException.AdminCannotSelfRemove(GROUP))
                    } else {
                        GroupInviteDeclineResultFfi(
                            steppedDown.copy(pendingConfirmation = false, archived = true),
                            mutation.summary,
                        )
                    }
                "selfDemoteAdminDetailed" -> {
                    check(isAdmin)
                    when {
                        lastAdmin ->
                            failSuspendedNativeCall(arguments, MarmotKitException.WouldRemoveLastAdmin(GROUP))
                        holdStepDown ->
                            failSuspendedNativeCall(arguments, MarmotKitException.GroupCatchingUp(GROUP))
                        else -> {
                            isAdmin = false
                            mutation.copy(details = mutation.details.copy(group = steppedDown))
                        }
                    }
                }
                else -> error("Unexpected native call: $name")
            }
        } as MarmotInterface
    }

    /** Answers the roster read and refuses every self-demotion as catching up; anything else is unexpected. */
    private fun catchingUpRuntime(): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
            _,
            method,
            arguments,
            ->
            val name = method.name.substringBefore('-')
            calls += name
            when (name) {
                "groupMembers" -> roster()
                "npub" -> null
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "groupCommitDelivery" -> null
                "selfDemoteAdminDetailed" ->
                    failSuspendedNativeCall(arguments, MarmotKitException.GroupCatchingUp(GROUP))
                "promoteAdminDetailed" -> promoted()
                else -> error("Unexpected native call: $name")
            }
        } as MarmotInterface

    /** The grant as MDK returns it: the successor joins self as admin. */
    private fun promoted(): GroupMutationResultFfi {
        val admins = listOf(SELF, OTHER_ADMIN)
        return testGroupMutationResult(admins + MEMBER, admins)
    }

    /** The controller's roster tracker, so a test can mark its roster authoritative. */
    private fun rosterTracker(controller: ConversationController): GroupRosterLoadTracker {
        val field = ConversationController::class.java.getDeclaredField("memberRosterLoadTracker")
        field.isAccessible = true
        return field.get(controller) as GroupRosterLoadTracker
    }

    /** Self, a second admin and one member, so leaving needs a self-demotion first. */
    private fun roster() =
        listOf(
            AppGroupMemberRecordFfi(SELF, ConversationTimelineTestIds.ACCOUNT_REF, true),
            AppGroupMemberRecordFfi(OTHER_ADMIN, null, false),
            AppGroupMemberRecordFfi(MEMBER, null, false),
        )

    /** Draft storage that keeps nothing. */
    private object NoDrafts : DraftPersistence {
        /** No drafts are ever restored. */
        override fun read(): Map<String, String> = emptyMap()

        /** Writes are discarded. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        val SELF = ConversationTimelineTestIds.ACCOUNT_ID
        val GROUP = ConversationTimelineTestIds.GROUP_ID
        val OTHER_ADMIN = "e1".repeat(32)
        val MEMBER = "e2".repeat(32)
        const val QUESTION_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
