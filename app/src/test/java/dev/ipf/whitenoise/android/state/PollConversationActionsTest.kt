package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Android poll boundary uses the pinned account and serializes with other group sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class PollConversationActionsTest {
    @Test
    fun voteWaitsForOtherGroupMutationAndUsesPinnedNativeIds() =
        runTest {
            val entered = CountDownLatch(1)
            val calls = mutableListOf<List<Any?>>()
            val native =
                native { name, args ->
                    assertEquals("castPollVote", name)
                    calls += args.take(4)
                    entered.countDown()
                    summary()
                }
            val state = appState(native)
            val controller = controller(state)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val otherMutation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.withGroupCommitLock(TARGET_REF, GROUP_ID) {
                        held.complete(Unit)
                        release.await()
                    }
                }
            held.await()
            val vote =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.castPollVote(MESSAGE_ID, listOf("native-option"))
                }
            assertFalse(entered.await(150, TimeUnit.MILLISECONDS))
            release.complete(Unit)
            otherMutation.join()
            assertEquals(SendAcceptDispositionFfi.PUBLISHED, vote.await())
            assertEquals(listOf(listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, listOf("native-option"))), calls)
            val logs = ShadowLog.getLogsForTag("WNPolls").joinToString { it.msg }
            assertTrue(logs.contains("outcome=PUBLISHED"))
            listOf(TARGET_REF, GROUP_ID, MESSAGE_ID, "native-option").forEach { assertFalse(logs.contains(it)) }
        }

    @Test
    fun pollCreationAlsoWaitsForTheGroupMutationLock() =
        runTest {
            val entered = CountDownLatch(1)
            val state =
                appState(
                    native { name, _ ->
                        assertEquals("createPoll", name)
                        entered.countDown()
                        summary()
                    },
                )
            val controller = controller(state)
            val release = CompletableDeferred<Unit>()
            val otherMutation =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.withGroupCommitLock(TARGET_REF, GROUP_ID) { release.await() }
                }
            val creation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.createPoll("Lunch?", listOf("Soup", "Salad"), PollTypeFfi.SINGLE_CHOICE, null)
                }
            assertFalse(entered.await(150, TimeUnit.MILLISECONDS))
            release.complete(Unit)
            otherMutation.join()
            assertTrue(creation.await())
        }

    @Test
    fun nativeFailureAllowsANewVote() =
        runTest {
            var attempt = 0
            val state =
                appState(
                    native { name, _ ->
                        assertEquals("castPollVote", name)
                        attempt++
                        if (attempt == 1) error("native vote rejected")
                        summary()
                    },
                )
            val controller = controller(state)
            assertNull(controller.castPollVote(MESSAGE_ID, listOf("native-option")))
            assertEquals(SendAcceptDispositionFfi.PUBLISHED, controller.castPollVote(MESSAGE_ID, listOf("native-option")))
            assertEquals(2, attempt)
        }

    @Test
    fun preservesPendingAndUnknownNativeOutcomes() =
        runTest {
            for (disposition in listOf(SendAcceptDispositionFfi.ACCEPTED_PENDING, SendAcceptDispositionFfi.COMPLETION_UNKNOWN)) {
                val controller = controller(appState(native { _, _ -> summary(disposition) }))
                assertEquals(disposition, controller.castPollVote(MESSAGE_ID, listOf("native-option")))
            }
        }

    private suspend fun controller(state: WhiteNoiseAppState) =
        ConversationController(
            state,
            group(),
            initialMemberSnapshot = memberSnapshot(),
            accountRefOverride = TARGET_REF,
            groupRosterReader = { _, _ ->
                GroupRosterFfi(
                    GROUP_ID,
                    listOf(GroupMemberDetailsFfi(TARGET_ID, TARGET_REF, true, true, true, "npub-$TARGET_ID", null)),
                    1uL,
                    1uL,
                    SelfMembershipFfi.MEMBER,
                    1u,
                    GroupLifecycleStateFfi.STABLE,
                )
            },
        ).also {
            it.retryMembers()
            assertTrue(it.canSendMessages)
        }

    private fun native(call: (String, Array<out Any?>) -> Any?): MarmotInterface =
        Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) { proxy, method, args ->
            when (val name = method.name.substringBefore('-')) {
                "toString" -> "poll-test-native"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> call(name, args.orEmpty())
            }
        } as MarmotInterface

    private fun summary(disposition: SendAcceptDispositionFfi = SendAcceptDispositionFfi.PUBLISHED) =
        SendSummaryFfi(1u, listOf(MESSAGE_ID), disposition, SendMaintenanceDispositionFfi.READY)

    private fun appState(native: MarmotInterface) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoopDraftPersistence()),
            accountIdHexResolver = { PREVIOUS_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = PREVIOUS_REF,
                        accountIdHex = PREVIOUS_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                    AccountSummaryFfi(
                        label = TARGET_REF,
                        accountIdHex = TARGET_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = PREVIOUS_REF,
            initialMarmotRuntime = AppMarmotRuntime("test", native),
        )

    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = TARGET_ID,
                    account = TARGET_REF,
                    local = true,
                ),
            ),
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Pinned group",
            description = "",
            admins = listOf(TARGET_ID),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "04".repeat(32),
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
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class NoopDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val PREVIOUS_REF = "alice"
        const val TARGET_REF = "bob"
        val PREVIOUS_ID = "a1".repeat(32)
        val TARGET_ID = "e5".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val MESSAGE_ID = "c3".repeat(32)
    }
}
