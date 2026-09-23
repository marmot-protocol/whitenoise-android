package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.CreateGroupOptionsFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupSubmission
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewChatFoundingOptionsTest {
    @Test
    fun directChatUsesCapturedAccountDefaultInFoundingOptions() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences("new-chat-founding-options", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            var receivedAccount: String? = null
            var receivedName: String? = null
            var receivedMembers: List<String>? = null
            var receivedOptions: CreateGroupOptionsFfi? = null
            val marmot =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { proxy, method, arguments ->
                    when (method.name) {
                        "createGroupWithOptions" -> {
                            receivedAccount = arguments!![0] as String
                            receivedName = arguments[1] as String
                            @Suppress("UNCHECKED_CAST")
                            receivedMembers = arguments[2] as List<String>
                            receivedOptions = arguments[3] as CreateGroupOptionsFfi
                            "created-group"
                        }
                        "toString" -> "NewChatFoundingOptionsMarmotFake"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.firstOrNull()
                        else -> error("Unexpected Marmot call: ${method.name}")
                    }
                } as MarmotInterface
            val runtime = AppMarmotRuntime(rootPath = "test", marmot = marmot)
            val state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(NewChatFoundingOptionsDraftPersistence),
                    accountIdHexResolver = { null },
                    accounts = listOf(AccountSummaryFfi("alice", "aa".repeat(32), true, false, false, true)),
                    activeAccountRef = "alice",
                    initialMarmotRuntime = runtime,
                    marmotRuntimeFactory = { runtime },
                    preferences = preferences,
                )
            state.setDefaultDisappearingMessagesSeconds(604_800L)

            assertEquals("created-group", state.createProfileChatGroup("npub1bob"))
            assertEquals("alice", receivedAccount)
            assertEquals("", receivedName)
            assertEquals(listOf("npub1bob"), receivedMembers)
            assertNull(receivedOptions?.description)
            assertNull(receivedOptions?.initialImage)
            assertEquals(604_800uL, receivedOptions?.disappearingMessageSecs)
        }

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

    @Test
    fun reconstructedActiveAccountUsesItsOwnDefaultForTheNextDirectChat() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val preferences = context.getSharedPreferences("account-default-switch", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val aliceCalls = mutableListOf<CreatedGroupCall>()
            val alice = recordingState(context, preferences, activeAccount = "alice", calls = aliceCalls)
            assertTrue(alice.setDefaultDisappearingMessagesSeconds(300L))
            alice.createProfileChatGroup("npub1peer")

            val bobCalls = mutableListOf<CreatedGroupCall>()
            val bob = recordingState(context, preferences, activeAccount = "bob", calls = bobCalls)
            assertTrue(bob.setDefaultDisappearingMessagesSeconds(604_800L))
            bob.createProfileChatGroup("npub1peer")

            assertEquals(300uL, aliceCalls.single().options.disappearingMessageSecs)
            assertEquals(604_800uL, bobCalls.single().options.disappearingMessageSecs)
            assertEquals(300L, bob.defaultDisappearingMessagesSeconds("alice"))
            assertEquals(604_800L, bob.defaultDisappearingMessagesSeconds("bob"))
        }

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

    private fun recordingState(
        context: Context,
        preferences: android.content.SharedPreferences,
        activeAccount: String,
        calls: MutableList<CreatedGroupCall> = mutableListOf(),
    ): WhiteNoiseAppState {
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "createGroupWithOptions" -> {
                        @Suppress("UNCHECKED_CAST")
                        calls +=
                            CreatedGroupCall(
                                account = arguments!![0] as String,
                                members = arguments[2] as List<String>,
                                options = arguments[3] as CreateGroupOptionsFfi,
                            )
                        "created-${calls.size}"
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
                AccountSummaryFfi(label, (if (index == 0) "aa" else "bb").repeat(32), true, false, false, true)
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

    private data class CreatedGroupCall(
        val account: String,
        val members: List<String>,
        val options: CreateGroupOptionsFfi,
    )
}

private object NewChatFoundingOptionsDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
