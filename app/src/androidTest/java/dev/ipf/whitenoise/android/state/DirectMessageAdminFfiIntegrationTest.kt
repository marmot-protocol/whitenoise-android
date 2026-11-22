package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.CreateGroupOptionsFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import dev.ipf.whitenoise.android.MdkAdoptionDeviceAcceptance
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Verifies production DM creation and the chat details timer against the published native library. */
@RunWith(AndroidJUnit4::class)
@MdkAdoptionDeviceAcceptance
class DirectMessageAdminFfiIntegrationTest {
    /** Both devices get the founding roles and timer, and each can publish its own timer change. */
    @Test
    fun bothFoundingAdminsCanChangeTheTimer() =
        runBlocking {
            withParticipants { alice, bob ->
                val group = alice.startDirectChat(bob, 300L)
                bob.acceptWelcome(group)
                val admins = setOf(alice.account.accountIdHex, bob.account.accountIdHex)
                alice.assertChat(group, admins, 300uL)
                bob.assertChat(group, admins, 300uL)

                alice.native.updateMessageRetention(alice.account.label, group, 3_600uL)
                bob.awaitTimer(group, 3_600uL)
                bob.native.updateMessageRetention(bob.account.label, group, 604_800uL)
                alice.awaitTimer(group, 604_800uL)

                assertEquals(group, alice.startDirectChat(bob, 0L))
                assertEquals(group, bob.startDirectChat(alice, 300L))
                alice.assertChat(group, admins, 604_800uL)
                bob.assertChat(group, admins, 604_800uL)
            }
        }

    /** A legacy creator-only DM is reused with its original timer and member roles from either side. */
    @Test
    fun existingCreatorOnlyDmKeepsItsTimerAndRoles() =
        runBlocking {
            withParticipants { alice, bob ->
                val group =
                    alice.native
                        .createGroupWithOptions(
                            alice.account.label,
                            "",
                            listOf(bob.account.accountIdHex),
                            CreateGroupOptionsFfi(
                                description = null,
                                initialImage = null,
                                disappearingMessageSecs = 3_600uL,
                            ),
                        ).requireCreatedGroupId()
                bob.acceptWelcome(group)
                val admins = setOf(alice.account.accountIdHex)
                alice.assertChat(group, admins, 3_600uL)
                bob.assertChat(group, admins, 3_600uL)

                assertEquals(group, alice.startDirectChat(bob, 604_800L))
                assertEquals(group, bob.startDirectChat(alice, 300L))
                alice.assertChat(group, admins, 3_600uL)
                bob.assertChat(group, admins, 3_600uL)
            }
        }

    /** Gives each participant its own store and key material with a shared, local-only relay. */
    private suspend fun withParticipants(test: suspend (Participant, Participant) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MarmotAndroid.initialize(context)
        val root = File(context.cacheDir, "direct-timer-${UUID.randomUUID()}")
        check(root.mkdirs())
        try {
            LoopbackNostrRelay().use { relay ->
                withTimeout(90_000L) {
                    withParticipant(context, File(root, "alice"), relay.url) { alice ->
                        withParticipant(context, File(root, "bob"), relay.url) { bob ->
                            test(alice, bob)
                        }
                    }
                }
            }
        } finally {
            check(root.deleteRecursively())
        }
    }

    /** Mounts the production app-state entry point on the generated account and isolated native store. */
    private suspend fun withParticipant(
        context: Context,
        root: File,
        relayUrl: String,
        test: suspend (Participant) -> Unit,
    ) {
        val relays = listOf(relayUrl)
        val native =
            Marmot.newWithConfiguration(
                root.path,
                relays,
                MarmotOptions(
                    relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                    deployment = marmotDeploymentWhiteNoise(),
                ),
            )
        val preferenceName = "${root.parentFile?.name}-${root.name}"
        try {
            native.start()
            val account = native.createIdentity(relays, relays)
            val state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(NoDrafts),
                    accounts = listOf(account),
                    activeAccountRef = account.label,
                    accountIdHexResolver = { account.accountIdHex },
                    initialMarmotRuntime = AppMarmotRuntime(root.path, native),
                    preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE),
                )
            test(Participant(native, account, state))
        } finally {
            withContext(NonCancellable) {
                try {
                    native.shutdownAndClose()
                } finally {
                    context.deleteSharedPreferences(preferenceName)
                }
            }
        }
    }

    private class Participant(
        val native: Marmot,
        val account: AccountSummaryFfi,
        private val state: WhiteNoiseAppState,
    ) {
        /** Uses the same Android helper as New Message and Start DM from a profile. */
        suspend fun startDirectChat(
            peer: Participant,
            seconds: Long,
        ): String {
            assertTrue(state.setDefaultDisappearingMessagesSeconds(seconds))
            return state.createProfileChatGroup(requireNotNull(native.npub(peer.account.accountIdHex)))
        }

        /** Accepts the founding Welcome once the recipient's worker has processed it. */
        suspend fun acceptWelcome(group: String) {
            withTimeout(30_000L) {
                while (native.chatListRow(account.label, group) == null) delay(100L)
            }
            native.acceptGroupInvite(account.label, group)
        }

        /** Checks the full admin set, self permission and timer that Android reads for chat details. */
        suspend fun assertChat(
            group: String,
            admins: Set<String>,
            seconds: ULong,
        ) {
            val snapshot = native.groupConversationSnapshot(account.label, group)
            assertEquals(admins, snapshot.details.members.filter { it.isAdmin }.map { it.memberIdHex }.toSet())
            assertEquals(account.accountIdHex in admins, snapshot.managementState.isSelfAdmin)
            assertEquals(seconds, snapshot.details.group.disappearingMessageSecs)
        }

        /** Waits for the other participant's retention commit to arrive over the relay. */
        suspend fun awaitTimer(
            group: String,
            seconds: ULong,
        ) {
            withTimeout(30_000L) {
                while (native.groupDetails(account.label, group).group.disappearingMessageSecs != seconds) {
                    delay(100L)
                }
            }
        }
    }

    private object NoDrafts : DraftPersistence {
        /** Starts each test with an empty draft store. */
        override fun read(): Map<String, String> = emptyMap()

        /** Keeps drafts out of the installed app's store. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
