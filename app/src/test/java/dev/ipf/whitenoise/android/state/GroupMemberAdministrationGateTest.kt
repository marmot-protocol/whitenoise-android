package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.MutableState
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupMemberAdministrationGateTest {
    private val invitee = "cc".repeat(32)

    @Test
    fun loadingRosterRejectsInviteAndClearsStaleMutationErrorBeforeCallingRuntime() =
        runBlocking {
            val controller = ConversationController(appState(), group())
            assertEquals(GroupRosterLoadState.LOADING, controller.memberRosterState)
            replaceLastMutationError(controller, "Previous unrelated failure")

            assertFalse(controller.inviteMembers(listOf("bob")))

            assertNull(controller.lastMutationError)
        }

    /**
     * The optimistic presentation path: a warm seed lets the Add member action
     * present enabled while the roster is LOADING, but an invite tapped from
     * it must still be rejected before reaching the runtime until the roster
     * is READY, and must reach the runtime once it is.
     */
    @Test
    fun seededAdminInviteStillWaitsForTheAuthoritativeRosterBeforeReachingRuntime() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot =
                        GroupMemberSnapshot(
                            listOf(
                                member("alice", account = "alice", local = true),
                                member("bob"),
                            ),
                        ),
                )
            assertTrue("the warm seed must prove membership", controller.seededSelfMember)
            assertTrue(controller.isSelfAdmin)
            assertEquals(GroupRosterLoadState.LOADING, controller.memberRosterState)

            assertFalse(controller.inviteMembers(listOf("carol")))
            assertEquals("no runtime call may happen before the roster is authoritative", 0, runtimeAccess.callCount)

            rosterTracker(controller).transition(GroupRosterRefreshEvent.SUCCEEDED)
            assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)
            controller.inviteMembers(listOf("carol"))
            assertTrue("the same invite must reach the runtime once the roster is READY", runtimeAccess.callCount > 0)
        }

    @Test
    fun rosterLosingAuthorityWhileInviteWaitsForCommitLockSkipsRuntime() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller = readyController(appState)
            val tracker = rosterTracker(controller)
            assertEquals(GroupRosterLoadState.READY, controller.memberRosterState)

            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val holder =
                async {
                    appState.withGroupCommitLock("alice", "group") {
                        lockHeld.complete(Unit)
                        releaseLock.await()
                    }
                }
            lockHeld.await()
            val invite =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.inviteMembers(listOf("carol"))
                }
            // UNDISPATCHED runs through all synchronous preconditions. Remaining
            // active here proves the operation reached the held commit lock.
            assertTrue(invite.isActive)

            tracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
            releaseLock.complete(Unit)

            assertFalse(invite.await())
            holder.await()
            assertEquals(0, runtimeAccess.callCount)
            assertRosterChangedFailure(appState, controller, R.string.toast_couldnt_add_members)
        }

    @Test
    fun rosterLosingAuthorityWhileRemoveWaitsForCommitLockSkipsRuntime() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller = readyController(appState)
            val tracker = rosterTracker(controller)
            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val holder = holdGroupCommitLock(appState, lockHeld, releaseLock)
            lockHeld.await()

            val remove =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.removeMember(member("bob"))
                }
            assertTrue(remove.isActive)
            tracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
            releaseLock.complete(Unit)

            assertFalse(remove.await())
            holder.await()
            assertEquals(0, runtimeAccess.callCount)
            assertRosterChangedFailure(appState, controller, R.string.toast_couldnt_remove_member)
        }

    @Test
    fun targetDisappearingWhilePromotionWaitsForCommitLockSkipsRuntime() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller = readyController(appState)
            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val holder = holdGroupCommitLock(appState, lockHeld, releaseLock)
            lockHeld.await()

            val promote =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setMemberAdmin(member("bob"), admin = true)
                }
            assertTrue(promote.isActive)
            replaceMembers(controller, listOf(member("alice", account = "alice", local = true)))
            releaseLock.complete(Unit)

            assertFalse(promote.await())
            holder.await()
            assertEquals(0, runtimeAccess.callCount)
            assertRosterChangedFailure(appState, controller, R.string.toast_couldnt_update_admin)
        }

    @Test
    fun rosterLosingAuthorityWhileDemotionWaitsForCommitLockSkipsRuntime() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller = readyController(appState, admins = listOf("alice", "bob"))
            val tracker = rosterTracker(controller)
            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val holder = holdGroupCommitLock(appState, lockHeld, releaseLock)
            lockHeld.await()

            val demote =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.setMemberAdmin(member("bob"), admin = false)
                }
            assertTrue(demote.isActive)
            tracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
            releaseLock.complete(Unit)

            assertFalse(demote.await())
            holder.await()
            assertEquals(0, runtimeAccess.callCount)
            assertRosterChangedFailure(appState, controller, R.string.toast_couldnt_update_admin)
        }

    @Test
    fun becomingSoleAdminWhileSelfDemotionWaitsForCommitLockShowsRetryOutsideLock() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller = readyController(appState, admins = listOf("alice", "bob"))
            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val holder = holdGroupCommitLock(appState, lockHeld, releaseLock)
            lockHeld.await()

            val demote =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.stepDownAsAdmin()
                }
            assertTrue(demote.isActive)
            replaceGroup(controller, controller.group.copy(admins = listOf("alice")))
            releaseLock.complete(Unit)

            assertFalse(demote.await())
            holder.await()
            assertEquals(0, runtimeAccess.callCount)
            assertNull(controller.lastMutationError)
            assertEquals(
                ToastMessage(
                    title = AppText.Resource(R.string.toast_keep_one_admin),
                    detail = AppText.Resource(R.string.toast_promote_before_removing_admin),
                ),
                appState.toast,
            )
        }

    @Test
    fun targetDisappearingWhileTransferWaitsForCommitLockSkipsBothCommits() =
        runBlocking {
            val runtimeAccess = RuntimeAccessRecorder()
            val appState = appState(runtimeAccess)
            val controller = readyController(appState)
            val lockHeld = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()
            val holder = holdGroupCommitLock(appState, lockHeld, releaseLock)
            lockHeld.await()

            val transfer =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.transferAdmin(member("bob"))
                }
            assertTrue(transfer.isActive)
            replaceMembers(controller, listOf(member("alice", account = "alice", local = true)))
            releaseLock.complete(Unit)

            assertFalse(transfer.await())
            holder.await()
            assertEquals(0, runtimeAccess.callCount)
            assertNull(controller.lastMutationError)
        }

    @Test
    fun profileInvitesPreserveRecipientGuidanceAndFailureCounts() =
        runBlocking {
            for ((failure, resource) in inviteFailures()) {
                var calls = 0
                val state =
                    appState(marmotAccessObserver = {
                        calls += 1
                        throw failure
                    })
                state.setContactNickname(invitee, "Carol")
                assertFalse(state.inviteProfileToGroups(invitee, listOf("one", "two")))
                assertEquals(2, calls)
                assertEquals(AppText.Resource(R.string.toast_couldnt_add_members), state.toast?.title)
                assertEquals(expectedInviteDetail(state, resource), state.toast?.detail)
                assertTrue(state.toast?.copyable == true)
            }
        }

    @Test
    fun conversationInvitesUseTypedRecipientAndNeutralGenericFailure() =
        runBlocking {
            for ((failure, resource) in inviteFailures()) {
                var calls = 0
                val state =
                    appState(marmotAccessObserver = {
                        calls += 1
                        throw failure
                    })
                state.setContactNickname(invitee, "Carol")
                val controller = readyController(state)
                assertFalse(controller.inviteMembers(listOf(invitee)))
                assertEquals(1, calls)
                assertEquals(expectedInviteDetail(state, resource), controller.lastMutationError?.message)
                assertEquals(AppText.Resource(R.string.toast_couldnt_add_members), state.toast?.title)
                assertEquals(expectedInviteDetail(state, resource), state.toast?.detail)
            }
        }

    @Test
    fun conversationInvalidKeyDoesNotBlameOneMemberInAMultipleMemberInvite() =
        runBlocking {
            val state = appState(marmotAccessObserver = { throw MarmotKitException.InvalidKeyPackageEvent("private") })
            state.setContactNickname(invitee, "Carol")
            val controller = readyController(state)
            assertFalse(controller.inviteMembers(listOf(invitee, "dd".repeat(32))))
            assertEquals(AppText.Resource(R.string.error_invalid_key_package), controller.lastMutationError?.message)
            assertEquals(AppText.Resource(R.string.error_invalid_key_package), state.toast?.detail)
        }

    private fun inviteFailures(): List<Pair<Throwable, Int>> =
        listOf(
            MarmotKitException.MissingKeyPackage(invitee) to R.string.error_missing_key_package_for,
            MarmotKitException.MissingMemberInboxRoute(invitee) to R.string.error_missing_member_inbox_for,
            MarmotKitException.InvalidKeyPackageEvent("private") to R.string.error_invalid_key_package_for,
            MarmotKitException.Publish("private") to R.string.error_try_again,
            MarmotKitException.Runtime("private") to R.string.error_try_again,
            IllegalStateException("private") to R.string.error_try_again,
        )

    private fun expectedInviteDetail(
        state: WhiteNoiseAppState,
        resource: Int,
    ): AppText {
        val arguments = if (resource == R.string.error_try_again) emptyList() else listOf(state.displayName(invitee))
        return AppText.Resource(resource, arguments)
    }

    private fun rosterTracker(controller: ConversationController): GroupRosterLoadTracker {
        val field = ConversationController::class.java.getDeclaredField("memberRosterLoadTracker")
        field.isAccessible = true
        return field.get(controller) as GroupRosterLoadTracker
    }

    private fun assertRosterChangedFailure(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        title: Int,
    ) {
        assertEquals(
            AppText.Plain(ConversationControllerCopy().groupRosterChanged),
            controller.lastMutationError?.message,
        )
        assertEquals(
            ToastMessage(
                title = AppText.Resource(title),
                detail = AppText.Resource(R.string.toast_group_roster_changed),
            ),
            appState.toast,
        )
    }

    private fun readyController(
        appState: WhiteNoiseAppState,
        admins: List<String> = listOf("alice"),
    ): ConversationController {
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(admins),
                initialMemberSnapshot =
                    GroupMemberSnapshot(
                        listOf(
                            member("alice", account = "alice", local = true),
                            member("bob"),
                        ),
                    ),
            )
        rosterTracker(controller).transition(GroupRosterRefreshEvent.SUCCEEDED)
        return controller
    }

    private fun kotlinx.coroutines.CoroutineScope.holdGroupCommitLock(
        appState: WhiteNoiseAppState,
        lockHeld: CompletableDeferred<Unit>,
        releaseLock: CompletableDeferred<Unit>,
    ) = async {
        appState.withGroupCommitLock("alice", "group") {
            lockHeld.complete(Unit)
            releaseLock.await()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun replaceMembers(
        controller: ConversationController,
        members: List<AppGroupMemberRecordFfi>,
    ) {
        val field = ConversationController::class.java.getDeclaredField("members\$delegate")
        field.isAccessible = true
        (field.get(controller) as MutableState<List<AppGroupMemberRecordFfi>>).value = members
    }

    @Suppress("UNCHECKED_CAST")
    private fun replaceGroup(
        controller: ConversationController,
        group: AppGroupRecordFfi,
    ) {
        val field = ConversationController::class.java.getDeclaredField("group\$delegate")
        field.isAccessible = true
        (field.get(controller) as MutableState<AppGroupRecordFfi>).value = group
    }

    @Suppress("UNCHECKED_CAST")
    private fun replaceLastMutationError(
        controller: ConversationController,
        error: String,
    ) {
        val field = ConversationController::class.java.getDeclaredField("lastMutationError\$delegate")
        field.isAccessible = true
        (field.get(controller) as MutableState<ErrorPresentation?>).value =
            ErrorPresentation(AppText.Plain(error), "operation=TEST\nerror=TEST")
    }

    private fun appState(
        runtimeAccess: RuntimeAccessRecorder? = null,
        marmotAccessObserver: (() -> Unit)? = runtimeAccess?.let { it::record },
    ) = WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext(),
        draftStore = DraftStore(GroupMemberAdministrationDraftPersistence()),
        accountIdHexResolver = { it },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = "alice",
                    accountIdHex = "alice",
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = "alice",
        marmotAccessObserver = marmotAccessObserver,
    )

    private fun group(admins: List<String> = listOf("alice")) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = "group",
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Test Group",
            description = "",
            admins = admins,
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "nostr",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun member(
        memberIdHex: String,
        account: String? = null,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberIdHex,
        account = account,
        local = local,
    )
}

private class RuntimeAccessRecorder {
    var callCount = 0
        private set

    fun record() {
        callCount += 1
    }
}

private class GroupMemberAdministrationDraftPersistence : DraftPersistence {
    private val values = mutableMapOf<String, String>()

    override fun read(): Map<String, String> = values.toMap()

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
