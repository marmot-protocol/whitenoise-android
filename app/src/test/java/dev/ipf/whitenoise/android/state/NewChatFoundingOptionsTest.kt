package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.DirectConversationOptionsFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupSubmission
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewChatFoundingOptionsTest {
    /** The per-account timer goes through canonical direct creation, including when it is Off. */
    @Test
    fun directChatPassesTheCapturedDefaultToMarmotKit() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences("new-chat-founding-options", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val calls = mutableListOf<DirectStartCall>()
            val state = recordingState(context, preferences, activeAccount = "alice", calls = calls)

            assertEquals("direct-1", state.createProfileChatGroup("npub1bob"))
            assertTrue(state.setDefaultDisappearingMessagesSeconds(604_800L))
            assertEquals("direct-2", state.createProfileChatGroup("npub1carol"))

            assertEquals(
                listOf(
                    DirectStartCall("alice", "npub1bob", 0uL),
                    DirectStartCall("alice", "npub1carol", 604_800uL),
                ),
                calls,
            )
        }

    /** A save for an account that is no longer active is refused and leaves both accounts' defaults unchanged. */
    @Test
    fun staleAccountSaveIsRejectedWithoutChangingEitherAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("stale-default-save", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val state = recordingState(context, preferences, activeAccount = "bob")

        assertFalse(state.setDefaultDisappearingMessagesSeconds(604_800L, accountRef = "alice"))
        assertEquals(0L, state.defaultDisappearingMessagesSeconds("alice"))
        assertEquals(0L, state.defaultDisappearingMessagesSeconds("bob"))
    }

    /** Each reconstructed app state starts its next DM with its own active account's saved timer. */
    @Test
    fun reconstructedActiveAccountUsesItsOwnDefaultForTheNextDirectChat() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences("account-default-switch", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val aliceCalls = mutableListOf<DirectStartCall>()
            val alice = recordingState(context, preferences, activeAccount = "alice", calls = aliceCalls)
            assertTrue(alice.setDefaultDisappearingMessagesSeconds(300L))
            alice.createProfileChatGroup("npub1peer")

            val bobCalls = mutableListOf<DirectStartCall>()
            val bob = recordingState(context, preferences, activeAccount = "bob", calls = bobCalls)
            assertTrue(bob.setDefaultDisappearingMessagesSeconds(604_800L))
            bob.createProfileChatGroup("npub1peer")

            assertEquals(300uL, aliceCalls.single().retentionSeconds)
            assertEquals(604_800uL, bobCalls.single().retentionSeconds)
            assertEquals(300L, bob.defaultDisappearingMessagesSeconds("alice"))
            assertEquals(604_800L, bob.defaultDisappearingMessagesSeconds("bob"))
        }

    /** A timer chosen for one named group reaches its founding options without changing the saved default. */
    @Test
    fun oneOffNamedGroupOverrideDoesNotChangeTheSavedAccountDefault() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences("one-off-group-default", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val state = recordingState(context, preferences, activeAccount = "alice")
            assertTrue(state.setDefaultDisappearingMessagesSeconds(604_800L))

            NewGroupSubmission(
                name = "Short-lived",
                description = null,
                members = emptyList(),
                image = null,
                disappearingMessageSecs = 3_600L,
            ).createWith { _, _, options ->
                assertEquals(3_600uL, options.disappearingMessageSecs)
                "created-group"
            }

            assertEquals(604_800L, state.defaultDisappearingMessagesSeconds("alice"))
        }

    /** Builds an app state that records the arguments passed to the direct-conversation operation. */
    private fun recordingState(
        context: Context,
        preferences: android.content.SharedPreferences,
        activeAccount: String,
        calls: MutableList<DirectStartCall> = mutableListOf(),
    ): WhiteNoiseAppState {
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "startDirectConversationWithOptions" -> {
                        val options = arguments!![2] as DirectConversationOptionsFfi
                        calls +=
                            DirectStartCall(
                                account = arguments[0] as String,
                                peer = arguments[1] as String,
                                retentionSeconds = options.disappearingMessageSecs,
                            )
                        "direct-${calls.size}"
                    }
                    "toString" -> "NewChatFoundingOptionsMarmotFake"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected Marmot call: ${method.name}")
                }
            } as MarmotInterface
        val runtime = AppMarmotRuntime(rootPath = "test", marmot = marmot)
        val accounts =
            listOf("alice", "bob").mapIndexed { index, label ->
                AccountSummaryFfi(label, (if (index == 0) "aa" else "bb").repeat(32), true, false, false, true, false)
            }
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NewChatFoundingOptionsDraftPersistence),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = activeAccount,
            initialMarmotRuntime = runtime,
            marmotRuntimeFactory = { runtime },
            preferences = preferences,
        )
    }

    /** One recorded direct-conversation start: the account, the peer and the founding timer. */
    private data class DirectStartCall(
        val account: String,
        val peer: String,
        val retentionSeconds: ULong,
    )
}

private object NewChatFoundingOptionsDraftPersistence : DraftPersistence {
    /** Starts each test with no saved drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Discards drafts so tests never touch a real draft store. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
