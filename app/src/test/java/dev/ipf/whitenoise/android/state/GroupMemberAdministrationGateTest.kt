package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupMemberAdministrationGateTest {
    @Test
    fun loadingRosterRejectsInviteBeforeCallingRuntime() =
        runBlocking {
            val controller = ConversationController(appState(), group())
            assertEquals(GroupRosterLoadState.LOADING, controller.memberRosterState)

            assertFalse(controller.inviteMembers(listOf("bob")))

            assertNull(controller.lastMutationError)
        }

    @Test
    fun rosterLosingAuthorityWhileInviteWaitsForCommitLockSkipsRuntime() =
        runBlocking {
            val appState = appState()
            val controller = ConversationController(appState, group())
            val tracker = rosterTracker(controller)
            tracker.transition(GroupRosterRefreshEvent.SUCCEEDED)
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
                    controller.inviteMembers(listOf("bob"))
                }

            tracker.transition(GroupRosterRefreshEvent.INCONSISTENT)
            releaseLock.complete(Unit)

            assertFalse(invite.await())
            holder.await()
            assertNull(controller.lastMutationError)
        }

    private fun rosterTracker(controller: ConversationController): GroupRosterLoadTracker {
        val field = ConversationController::class.java.getDeclaredField("memberRosterLoadTracker")
        field.isAccessible = true
        return field.get(controller) as GroupRosterLoadTracker
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(GroupMemberAdministrationDraftPersistence()),
            accountIdHexResolver = { null },
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
        )

    private fun group() =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = "group",
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Test Group",
            description = "",
            admins = listOf("alice"),
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
