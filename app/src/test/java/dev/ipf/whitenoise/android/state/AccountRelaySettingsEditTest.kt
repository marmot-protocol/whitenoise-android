package dev.ipf.whitenoise.android.state

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

    /** The Inbox setter uses the Inbox projection and does not read the NIP-65 union. */
    @Test
    fun inboxEditsUseOnlyInboxRelays() =
        runTest {
            val fixture = RelayFixture(failFullRead = true)
            fixture.app.addAccountRelay(ACCOUNT, RelayListKind.Inbox, ADDED)
            fixture.app.removeAccountRelay(ACCOUNT, RelayListKind.Inbox, INBOX)
            assertEquals(listOf(setOf(INBOX, OTHER_INBOX, ADDED), setOf(OTHER_INBOX)), fixture.inboxWrites)
            assertEquals(emptyList<Set<String>>(), fixture.nip65Writes)
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

    /** Models the write projection and full list as different native return values. */
    private class RelayFixture(
        failFullRead: Boolean = false,
    ) {
        private val lists =
            AccountRelayListsFfi(
                complete = true,
                missing = emptyList(),
                defaultRelays = emptyList(),
                bootstrapRelays = emptyList(),
                nip65 = RelayListFfi(kind = 10_002uL, relays = listOf(POSTING, OTHER_POSTING)),
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
                        listOf(POSTING, OTHER_POSTING, READ_ONLY)
                    }
                    "classifyRelayEndpoints" ->
                        (args!![0] as List<*>).map { raw ->
                            val endpoint = raw as String
                            RelayEndpointClassificationFfi(endpoint, endpoint, RelayEndpointPolicyFfi.ALLOWED)
                        }
                    "setAccountNip65Relays", "setAccountInboxRelays" -> {
                        val writes = if (method.name == "setAccountNip65Relays") nip65Writes else inboxWrites
                        writes += (args!![1] as List<*>).map { it as String }.toSet()
                        lists
                    }
                    else -> error("Unexpected native call: ${method.name}")
                }
            } as MarmotInterface
        val app =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { null },
                initialMarmotRuntime = AppMarmotRuntime("relay-settings-test", native),
            )
    }

    private companion object {
        const val ACCOUNT = "alice"
        const val POSTING = "wss://posting.example.com"
        const val OTHER_POSTING = "wss://other-posting.example.com"
        const val READ_ONLY = "wss://read-only.example.com"
        const val INBOX = "wss://inbox.example.com"
        const val OTHER_INBOX = "wss://other-inbox.example.com"
        const val ADDED = "wss://1.1.1.1"
    }
}
