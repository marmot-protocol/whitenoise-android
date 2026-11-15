package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.marmotkit.RelayListFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Exercises the Settings mutations through their native publication boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountRelaySettingsEditTest {
    /** Adding a posting relay includes the read-only address in the replacement list. */
    @Test
    fun addingNip65RelayPreservesReadOnlyRelay() =
        runTest {
            val fixture = RelayFixture()
            fixture.app.addAccountRelay(ACCOUNT, RelayListKind.Nip65, ADDED)
            assertEquals(listOf(setOf(POSTING, OTHER_POSTING, READ_ONLY, ADDED)), fixture.nip65Writes)
        }

    /** Removing one of two posting relays still publishes the read-only address. */
    @Test
    fun removingNip65RelayPreservesReadOnlyRelay() =
        runTest {
            val fixture = RelayFixture()
            fixture.app.removeAccountRelay(ACCOUNT, RelayListKind.Nip65, POSTING)
            assertEquals(listOf(setOf(OTHER_POSTING, READ_ONLY)), fixture.nip65Writes)
        }

    /** The Inbox setter uses the Inbox projection, keeps an earlier addition and does not read the NIP-65 union. */
    @Test
    fun inboxEditsUseOnlyInboxRelays() =
        runTest {
            val fixture = RelayFixture(failFullRead = true)
            fixture.app.addAccountRelay(ACCOUNT, RelayListKind.Inbox, ADDED)
            fixture.app.removeAccountRelay(ACCOUNT, RelayListKind.Inbox, INBOX)
            assertEquals(listOf(setOf(INBOX, OTHER_INBOX, ADDED), setOf(OTHER_INBOX, ADDED)), fixture.inboxWrites)
            assertEquals(emptyList<Set<String>>(), fixture.nip65Writes)
        }

    /** Removing the last allowed posting relay is refused when the other posting relay is retired. */
    @Test
    fun removalThatLeavesOnlyReadOnlyRelaysIsRefused() =
        runTest {
            val fixture = RelayFixture(posting = listOf(POSTING, RETIRED), retired = setOf(RETIRED))
            assertNull(fixture.app.removeAccountRelay(ACCOUNT, RelayListKind.Nip65, POSTING))
            assertEquals(emptyList<Set<String>>(), fixture.nip65Writes)
        }

    /** Removing the retired relay itself still publishes the surviving posting and read-only relays. */
    @Test
    fun removingRetiredRelayKeepsPostingAndReadOnlyRelays() =
        runTest {
            val fixture = RelayFixture(posting = listOf(POSTING, RETIRED), retired = setOf(RETIRED))
            fixture.app.removeAccountRelay(ACCOUNT, RelayListKind.Nip65, RETIRED)
            assertEquals(listOf(setOf(POSTING, READ_ONLY)), fixture.nip65Writes)
        }

    /** A failed full-list read must never publish an incomplete replacement. */
    @Test
    fun nip65EditsDoNotPublishWhenFullListReadFails() =
        runTest {
            val fixture = RelayFixture(failFullRead = true)
            assertNull(fixture.app.addAccountRelay(ACCOUNT, RelayListKind.Nip65, ADDED))
            assertNull(fixture.app.removeAccountRelay(ACCOUNT, RelayListKind.Nip65, POSTING))
            assertEquals(emptyList<Set<String>>(), fixture.nip65Writes)
        }

    /**
     * Models the posting projection and the full declared list as separate native return values. Each
     * successful setter call replaces them, the way MarmotKit keeps [READ_ONLY] read-only, and [retired]
     * endpoints classify as RETIRED.
     */
    private class RelayFixture(
        failFullRead: Boolean = false,
        posting: List<String> = listOf(POSTING, OTHER_POSTING),
        retired: Set<String> = emptySet(),
    ) {
        private var declared = posting + READ_ONLY
        private var lists =
            AccountRelayListsFfi(
                complete = true,
                missing = emptyList(),
                defaultRelays = emptyList(),
                bootstrapRelays = emptyList(),
                nip65 = RelayListFfi(kind = 10_002uL, relays = posting),
                inbox = RelayListFfi(kind = 10_050uL, relays = listOf(INBOX, OTHER_INBOX)),
            )
        val nip65Writes = mutableListOf<Set<String>>()
        val inboxWrites = mutableListOf<Set<String>>()
        private val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "accountRelayLists" -> lists
                    "accountNip65Relays" -> {
                        check(!failFullRead) { "Full list unavailable" }
                        declared
                    }
                    "classifyRelayEndpoints" ->
                        (args!![0] as List<*>).map { raw ->
                            val endpoint = raw as String
                            val policy =
                                if (endpoint in retired) {
                                    RelayEndpointPolicyFfi.RETIRED
                                } else {
                                    RelayEndpointPolicyFfi.ALLOWED
                                }
                            RelayEndpointClassificationFfi(endpoint, endpoint, policy)
                        }
                    "setAccountNip65Relays" -> {
                        val written = (args!![1] as List<*>).map { it as String }
                        nip65Writes += written.toSet()
                        declared = written
                        lists = lists.copy(nip65 = lists.nip65.copy(relays = written - READ_ONLY))
                        lists
                    }
                    "setAccountInboxRelays" -> {
                        val written = (args!![1] as List<*>).map { it as String }
                        inboxWrites += written.toSet()
                        lists = lists.copy(inbox = lists.inbox.copy(relays = written))
                        lists
                    }
                    else -> error("Unexpected native call: ${method.name}")
                }
            } as MarmotInterface
        val app: WhiteNoiseAppState =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext<Context>(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = null,
                initialMarmotRuntime = AppMarmotRuntime("relay-settings-test", native),
            )
    }

    private companion object {
        const val ACCOUNT = "alice"
        const val POSTING = "wss://posting.example.com"
        const val OTHER_POSTING = "wss://other-posting.example.com"
        const val READ_ONLY = "wss://read-only.example.com"
        const val RETIRED = "wss://retired.example.com"
        const val INBOX = "wss://inbox.example.com"
        const val OTHER_INBOX = "wss://other-inbox.example.com"
        const val ADDED = "wss://1.1.1.1"
    }
}
