package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.GroupDetailsFfi
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Leaving as one of several admins steps down first, and MDK may hold that step back (#3289). A departure cancelled
 * while the user is still deciding must withdraw its question and change nothing, rather than demoting and leaving
 * later when someone taps Continue anyway. An admin transfer whose grant already landed must report the partial
 * state when the user waits on the step-down, instead of ending quietly with two admins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupDepartureQuestionTest {
    // Native timing callbacks may append while assertions inspect the recorded call sequence.
    private val calls = CopyOnWriteArrayList<String>()

    /** The 0.18 capability selects one native leave, even if a separate demotion would be refused. */
    @Test
    fun aCoAdminLeavesWithOneNativeChange() =
        runBlocking {
            val state = appState(catchingUpRuntime(atomicLeave = true))
            val account = checkNotNull(state.activeAccountRef)
            val result = state.leaveAsAdmin(account, GROUP) { error("Unexpected demotion") }
            assertTrue(result)
            val operations = listOf("groupManagementState", "leaveGroup")
            assertEquals(operations, calls.filter { it in operations })
        }

    /** The atomic leave retries only the acknowledged caution and never sends a separate role change. */
    @Test
    fun atomicLeaveAsksBeforeOverridingANativeCaution() =
        runBlocking {
            checkAtomicDecision(MarmotKitException.GroupCatchingUp(GROUP), "acknowledgeGroupCatchingUp", true)
            val undelivered = MarmotKitException.GroupCommitUndelivered(GROUP)
            checkAtomicDecision(undelivered, "acknowledgeGroupCommitUndelivered", true)
            checkAtomicDecision(MarmotKitException.GroupCatchingUp(GROUP), "acknowledgeGroupCatchingUp", false)
        }

    /** A snapshot that still lists the user as a member must respect MDK’s newer admin decision. */
    @Test
    fun aPromotedMemberCanWaitWithoutLeaving() =
        runBlocking {
            val catchingUp = MarmotKitException.GroupCatchingUp(GROUP)
            checkAtomicDecision(catchingUp, "acknowledgeGroupCatchingUp", false) { state, owner ->
                val controller =
                    ConversationController(
                        appState = state,
                        initialGroup = conversationTimelineTestGroup().copy(admins = listOf(OTHER_ADMIN)),
                        initialMemberSnapshot = GroupMemberSnapshot(roster()),
                    )
                controller.leaveWithRoster(owner, soleMember = false, activeAccountIdHex = SELF) {
                    error("Unexpected demotion")
                }
            }
            assertFalse("groupManagementState" in calls)
        }

    /** Exercise the real confirmation with a native refusal and check which operations follow the answer. */
    private suspend fun checkAtomicDecision(
        refusal: MarmotKitException,
        acknowledgement: String,
        proceed: Boolean,
        performLeave: suspend (WhiteNoiseAppState, String) -> Boolean = { state, owner ->
            state.leaveAsAdmin(owner, GROUP) { error("Unexpected demotion") }
        },
    ) {
        calls.clear()
        val state = appState(catchingUpRuntime(atomicLeave = true, refusal = refusal))
        val owner = checkNotNull(state.activeAccountRef)
        val leave =
            CoroutineScope(Job()).async(start = CoroutineStart.UNDISPATCHED) {
                performLeave(state, owner)
            }
        try {
            awaitWithMainLooper { state.groupChangeConfirmations.pending != null }
            assertEquals(1, calls.count { it == "leaveGroup" })
            checkNotNull(state.groupChangeConfirmations.pending).answer(proceed)
            awaitWithMainLooper { leave.isCompleted }
            assertEquals(!proceed, leave.isCancelled)
            if (proceed) assertTrue(leave.await())
            val expected = if (proceed) listOf("leaveGroup", acknowledgement, "leaveGroup") else listOf("leaveGroup")
            assertEquals(expected, calls.filter { it == "leaveGroup" || it.startsWith("acknowledge") })
            assertFalse("selfDemoteAdminDetailed" in calls)
        } finally {
            leave.cancel()
        }
    }

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

            assertFalse(controller.declineInviteWithConsent(null))
            assertTrue(controller.inviteDeclineStepDownRequested)
            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertFalse("selfDemoteAdminDetailed" in calls)

            assertTrue(controller.confirmInviteDeclineStepDown())
            assertFalse(controller.inviteDeclineStepDownRequested)
            assertEquals(
                listOf("declineGroupInvite", "declineGroupInvite", "selfDemoteAdminDetailed", "declineGroupInvite"),
                calls.filter { it == "declineGroupInvite" || it == "selfDemoteAdminDetailed" },
            )
            assertFalse(controller.group.pendingConfirmation)
            assertTrue(controller.group.archived)
        }

    /** A displayed co-admin can decline through a runtime that performs the whole leave atomically. */
    @Test
    fun nativeAdminDeclineRequiresOneDecision() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val native = inviteRuntime(invite, requiresSeparateStepDown = false)
            val controller = ConversationController(appState = appState(native), initialGroup = invite)
            assertTrue(controller.declineInviteWithConsent(null))
            assertFalse(controller.inviteDeclineStepDownRequested)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertFalse("selfDemoteAdminDetailed" in calls)
        }

    /** A member invitee declines directly, without a question or any change to group roles. */
    @Test
    fun memberInviteDeclinesWithoutSteppingDown() =
        runBlocking {
            val invite = conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(OTHER_ADMIN))
            val controller = ConversationController(appState = appState(inviteRuntime(invite)), initialGroup = invite)

            assertTrue(controller.declineInviteWithConsent(null))
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

            assertFalse(controller.declineInviteWithConsent(null))
            controller.cancelInviteDeclineStepDown()

            assertFalse(controller.inviteDeclineStepDownRequested)
            assertFalse("a cancelled question still allowed a step-down", controller.confirmInviteDeclineStepDown())
            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertFalse("selfDemoteAdminDetailed" in calls)
            assertTrue(controller.group.pendingConfirmation)
            assertTrue(SELF in controller.group.admins)
        }

    /** When the app's copy missed the admin role, MDK's refusal raises the question instead of a silent step-down. */
    @Test
    fun anUnexpectedAdminRefusalAsksBeforeSteppingDown() =
        runBlocking {
            val invite = conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(OTHER_ADMIN))
            val runtime = inviteRuntime(invite, requiresSeparateStepDown = true)
            val controller = ConversationController(appState = appState(runtime), initialGroup = invite)

            assertFalse(controller.declineInviteWithConsent(null))
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
            assertFalse(controller.declineInviteWithConsent(null))

            controller.applyGroupStateForTest(invite.copy(viaWelcomeMessageIdHex = "f2".repeat(32)))

            assertFalse(controller.inviteDeclineStepDownRequested)
            assertFalse(controller.confirmInviteDeclineStepDown())
            assertEquals(1, calls.count { it == "declineGroupInvite" })
            assertFalse("selfDemoteAdminDetailed" in calls)
        }

    /** A late legacy demotion cannot restore the old Welcome over its replacement or decline the replacement. */
    @Test
    fun newerInvitationSurvivesAnInFlightStepDown() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(
                    pendingConfirmation = true,
                    admins = listOf(SELF, OTHER_ADMIN),
                    viaWelcomeMessageIdHex = "f1".repeat(32),
                )
            val replacement = invite.copy(viaWelcomeMessageIdHex = "f2".repeat(32))
            val demoted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val native = inviteRuntime(invite)
            val runtime =
                object : MarmotInterface by native {
                    override suspend fun groupDetails(
                        accountRef: String,
                        groupIdHex: String,
                    ): GroupDetailsFfi {
                        val updated = replacement.copy(admins = listOf(OTHER_ADMIN))
                        return testGroupMutationResult(roster().map { it.memberIdHex }, updated.admins)
                            .details
                            .copy(group = updated)
                    }

                    override suspend fun selfDemoteAdminDetailed(
                        accountRef: String,
                        groupIdHex: String,
                    ): GroupMutationResultFfi {
                        val result = native.selfDemoteAdminDetailed(accountRef, groupIdHex)
                        demoted.complete(Unit)
                        release.await()
                        return result
                    }
                }
            val controller = ConversationController(appState = appState(runtime), initialGroup = invite)
            assertFalse(controller.declineInviteWithConsent(null))
            val decline =
                CoroutineScope(Job()).async(start = CoroutineStart.UNDISPATCHED) {
                    controller.confirmInviteDeclineStepDown()
                }
            try {
                awaitWithMainLooper { demoted.isCompleted }
                controller.applyGroupStateForTest(replacement)
                release.complete(Unit)
                awaitWithMainLooper { decline.isCompleted }
                assertFalse(decline.await())
                assertEquals(replacement.viaWelcomeMessageIdHex, controller.group.viaWelcomeMessageIdHex)
                assertFalse(SELF in controller.group.admins)
                assertTrue(controller.group.pendingConfirmation)
                assertEquals(2, calls.count { it == "declineGroupInvite" })
            } finally {
                release.complete(Unit)
                decline.cancel()
            }
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

            assertFalse(controller.declineInviteWithConsent(null))
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

            assertFalse(controller.declineInviteWithConsent(null))
            assertTrue(controller.inviteDeclineStepDownRequested)
            assertFalse(controller.confirmInviteDeclineStepDown())

            assertEquals(2, calls.count { it == "declineGroupInvite" })
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
            assertFalse(controller.declineInviteWithConsent(null))
            val scope = CoroutineScope(Job())
            val archive = scope.async(start = CoroutineStart.UNDISPATCHED) { controller.setArchived(true) }
            try {
                awaitWithMainLooper { controller.mutationInFlight }

                assertFalse(controller.confirmInviteDeclineStepDown())
                assertTrue("a busy lock dropped the user's answer", controller.inviteDeclineStepDownRequested)
                assertEquals(1, calls.count { it == "declineGroupInvite" })
            } finally {
                release.complete(Unit)
                archive.await()
            }
        }

    /** A refused legacy step-down leaves the invitation pending for a fresh decision. */
    @Test
    fun catchingUpDuringAdminDeclineKeepsTheInvite() =
        runBlocking {
            val invite =
                conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(SELF, OTHER_ADMIN))
            val state = appState(inviteRuntime(invite, holdStepDown = true))
            val controller = ConversationController(appState = state, initialGroup = invite)
            assertFalse(controller.declineInviteWithConsent(null))
            assertFalse(controller.confirmInviteDeclineStepDown())
            assertNull(state.groupChangeConfirmations.pending)
            assertEquals(AppText.Resource(R.string.invite_decline_wait_for_sync), state.toast?.detail)
            assertEquals(2, calls.count { it == "declineGroupInvite" })
            assertTrue(controller.group.pendingConfirmation)
            assertTrue(SELF in controller.group.admins)
        }

    /** An atomic decline refused by MDK never carries this Welcome’s decision into a native retry. */
    @Test
    fun undeliveredDeclineNeedsANewTap() =
        runBlocking {
            val invite = conversationTimelineTestGroup().copy(pendingConfirmation = true, admins = listOf(OTHER_ADMIN))
            val refusal = MarmotKitException.GroupCommitUndelivered(GROUP)
            val state = appState(inviteRuntime(invite, declineRefusal = refusal))
            val controller = ConversationController(appState = state, initialGroup = invite)
            assertFalse(controller.declineInviteWithConsent(null))
            assertTrue(controller.group.pendingConfirmation)
            assertEquals(AppText.Resource(R.string.invite_decline_check_delivery), state.toast?.detail)
            assertNull(state.groupChangeConfirmations.pending)
            val decisions = calls.filter { it.startsWith("acknowledge") || it == "declineGroupInvite" }
            assertEquals(listOf("declineGroupInvite"), decisions)
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
     * Models both atomic declines and the legacy [requiresSeparateStepDown] refusal. [lastAdmin] is native
     * authority, which can differ from the roles this device currently shows.
     */
    private fun inviteRuntime(
        invite: AppGroupRecordFfi,
        holdStepDown: Boolean = false,
        declineRefusal: MarmotKitException? = null,
        requiresSeparateStepDown: Boolean = SELF in invite.admins,
        lastAdmin: Boolean = invite.admins == listOf(SELF),
    ): MarmotInterface {
        var needsDemotion = requiresSeparateStepDown
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
                    if (declineRefusal != null) {
                        failSuspendedNativeCall(arguments, declineRefusal)
                    } else if (needsDemotion) {
                        failSuspendedNativeCall(arguments, MarmotKitException.AdminCannotSelfRemove(GROUP))
                    } else {
                        GroupInviteDeclineResultFfi(
                            steppedDown.copy(pendingConfirmation = false, archived = true),
                            mutation.summary,
                        )
                    }
                "selfDemoteAdminDetailed" -> {
                    check(needsDemotion)
                    when {
                        lastAdmin ->
                            failSuspendedNativeCall(arguments, MarmotKitException.WouldRemoveLastAdmin(GROUP))
                        holdStepDown ->
                            failSuspendedNativeCall(arguments, MarmotKitException.GroupCatchingUp(GROUP))
                        else -> {
                            needsDemotion = false
                            mutation.copy(details = mutation.details.copy(group = steppedDown))
                        }
                    }
                }
                else -> error("Unexpected native call: $name")
            }
        } as MarmotInterface
    }

    /** Answers the roster read and refuses every self-demotion as catching up; anything else is unexpected. */
    private fun catchingUpRuntime(
        atomicLeave: Boolean = false,
        refusal: MarmotKitException? = null,
    ): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
            _,
            method,
            arguments,
            ->
            val name = method.name.substringBefore('-')
            calls += name
            when (name) {
                "groupManagementState" ->
                    promoted().managementState.copy(
                        canLeave = atomicLeave,
                        requiresSelfDemoteBeforeLeave = !atomicLeave,
                    )
                "acknowledgeGroupCatchingUp", "acknowledgeGroupCommitUndelivered" -> true
                "leaveGroup" ->
                    if (refusal != null && calls.count { it == "leaveGroup" } == 1) {
                        failSuspendedNativeCall(arguments, refusal)
                    } else {
                        promoted().summary
                    }
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
