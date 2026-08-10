package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RelayUrlsTest {
    @Test
    fun normalizeRelayUrlsTrimsDropsInvalidAndDeduplicates() {
        assertEquals(
            listOf("wss://relay.example", "wss://xn--e1afmkfd.xn--p1ai"),
            normalizeRelayUrls(
                listOf(
                    "  wss://relay.example  ",
                    "",
                    "wss://relay.example",
                    "WSS://relay.example",
                    "wss://пример.рф",
                    "wss://xn--e1afmkfd.xn--p1ai",
                    " ws://localhost:7777 ",
                    "https://relay.example",
                    "wss://",
                    "wss://?bad",
                    "wss://user:pass@relay.example",
                ),
            ),
        )
    }

    @Test
    fun relayUrlValidationRequiresSecureWebsocketWithHost() {
        assertEquals(true, isAcceptableRelayUrl("wss://relay.example"))
        assertEquals(true, isAcceptableRelayUrl("WSS://relay.example"))
        assertEquals(true, isAcceptableRelayUrl(" wss://relay.example/path "))
        assertEquals(true, isAcceptableRelayUrl("wss://relay.example:443"))
        assertEquals(true, isAcceptableRelayUrl("wss://пример.рф"))
        assertEquals(false, isAcceptableRelayUrl("ws://relay.example"))
        assertEquals(false, isAcceptableRelayUrl("https://relay.example"))
        assertEquals(false, isAcceptableRelayUrl("wss://"))
        assertEquals(false, isAcceptableRelayUrl("wss://?bad"))
        assertEquals(false, isAcceptableRelayUrl("wss://user:pass@relay.example"))
        assertEquals(false, isAcceptableRelayUrl("wss://bad host.example"))
        assertEquals(false, isAcceptableRelayUrl("not a url"))
        assertEquals(false, isAcceptableRelayUrl("wss://relay.example:7777"))
        assertEquals(false, isAcceptableRelayUrl("wss://relay.example:8443"))
    }

    @Test
    fun relayUrlValidationRejectsNonStandardPortsWithoutDns() {
        assertEquals(emptyList<String>(), normalizeRelayUrls(listOf("wss://relay.example:7777")))
    }

    @Test
    fun releaseRelayUrlValidationAllowsOnlyBootstrapHosts() {
        assertTrue(
            isAcceptableRelayUrl("wss://relay.us.whitenoise.chat", allowExternalRelayHosts = false),
        )
        assertTrue(
            isAcceptableRelayUrl("wss://relay.eu.whitenoise.chat", allowExternalRelayHosts = false),
        )
        assertFalse(
            isAcceptableRelayUrl("wss://relay.example", allowExternalRelayHosts = false),
        )
        assertEquals(
            emptyList<String>(),
            normalizeRelayUrls(listOf("wss://relay.example"), allowExternalRelayHosts = false),
        )
    }

    @Test
    fun debugRelayUrlValidationKeepsSelfHostedRelaysAvailable() {
        assertTrue(
            isAcceptableRelayUrl("wss://relay.example", allowExternalRelayHosts = true),
        )
        assertEquals(
            listOf("wss://relay.example"),
            normalizeRelayUrls(listOf("wss://relay.example"), allowExternalRelayHosts = true),
        )
    }

    @Test
    fun releaseDistinguishesUnsupportedExternalHostFromInvalidUrl() {
        assertEquals(
            RelayUrlValidationResult.UnsupportedHost,
            relayUrlValidationResult("wss://relay.example", allowExternalRelayHosts = false),
        )
        assertEquals(
            RelayUrlValidationResult.Invalid,
            relayUrlValidationResult("https://relay.example", allowExternalRelayHosts = false),
        )
        assertEquals(
            RelayUrlValidationResult.Acceptable,
            relayUrlValidationResult("wss://relay.us.whitenoise.chat", allowExternalRelayHosts = false),
        )
    }

    @Test
    fun releaseAdditionCleansImportedExternalRelaysBeforePublishing() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://relay.us.whitenoise.chat"),
            ),
            relayListAfterAddition(
                currentRelays = listOf("wss://external.example"),
                relayToAdd = "wss://relay.us.whitenoise.chat",
                allowExternalRelayHosts = false,
            ),
        )
        assertEquals(
            null,
            relayListAfterAddition(
                currentRelays = listOf("wss://relay.us.whitenoise.chat"),
                relayToAdd = "wss://external.example",
                allowExternalRelayHosts = false,
            ),
        )
    }

    @Test
    fun releaseRemovalCleansAllImportedExternalRelays() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://relay.us.whitenoise.chat"),
            ),
            relayListAfterRemoval(
                currentRelays =
                    listOf(
                        "wss://relay.us.whitenoise.chat",
                        "wss://one.external.example",
                        "wss://two.external.example",
                    ),
                relayToRemove = "wss://one.external.example",
                allowExternalRelayHosts = false,
            ),
        )
    }

    @Test
    fun releaseRemovalFallsBackToWhiteNoiseRelaysWhenOnlyExternalRelaysRemain() {
        assertEquals(
            RelayListEditPlan(
                relays = MarmotClient.bootstrapRelays,
            ),
            relayListAfterRemoval(
                currentRelays = listOf("wss://one.external.example", "wss://two.external.example"),
                relayToRemove = "wss://one.external.example",
                allowExternalRelayHosts = false,
            ),
        )
    }

    @Test
    fun debugRemovalKeepsOtherExternalRelays() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://two.external.example"),
            ),
            relayListAfterRemoval(
                currentRelays = listOf("wss://one.external.example", "wss://two.external.example"),
                relayToRemove = "wss://one.external.example",
                allowExternalRelayHosts = true,
            ),
        )
    }

    @Test
    fun releaseKeepsLastSupportedRelayButAllowsExternalCleanup() {
        val relays = listOf("wss://relay.us.whitenoise.chat", "wss://external.example")

        assertFalse(
            canRemoveRelay(
                currentRelays = relays,
                relay = "wss://relay.us.whitenoise.chat",
                allowExternalRelayHosts = false,
            ),
        )
        assertTrue(
            canRemoveRelay(
                currentRelays = relays,
                relay = "wss://external.example",
                allowExternalRelayHosts = false,
            ),
        )
    }

    @Test
    fun relayUrlValidationRejectsPrivateAndLoopbackHosts() {
        // SSRF guard: relay URLs sourced from protocol messages must not point
        // the client at loopback or the local network. See issue #82.
        assertEquals(false, isAcceptableRelayUrl("wss://[::1]:7777"))
        assertEquals(false, isAcceptableRelayUrl("wss://127.0.0.1"))
        assertEquals(false, isAcceptableRelayUrl("wss://10.0.0.1:7777"))
        assertEquals(false, isAcceptableRelayUrl("wss://192.168.1.1"))
        assertEquals(false, isAcceptableRelayUrl("wss://172.16.5.5"))
        assertEquals(false, isAcceptableRelayUrl("wss://169.254.0.1"))
        assertEquals(false, isAcceptableRelayUrl("wss://localhost:7777"))
        // normalizeRelayUrls drops them too.
        assertEquals(emptyList<String>(), normalizeRelayUrls(listOf("wss://127.0.0.1", "wss://[::1]:7777")))
    }

    @Test
    fun relayUrlResolveTimeCheckRejectsPrivateResolvedAddresses() {
        val resolveToLoopback: RelayHostResolver = { arrayOf(ipv4(127, 0, 0, 1)) }
        assertEquals(
            RelayResolveTimeCheckResult.Blocked,
            relayUrlResolveTimeCheckResult("wss://rebind.example", resolveToLoopback),
        )
        assertFalse(relayUrlPassesResolveTimeCheck("wss://rebind.example", resolveToLoopback))
        runBlocking {
            assertEquals(
                RelayResolveTimeCheckResult.Blocked,
                relayUrlsResolveTimeCheckResult(listOf("wss://rebind.example"), resolveToLoopback),
            )
            assertFalse(relayUrlsPassResolveTimeChecks(listOf("wss://rebind.example"), resolveToLoopback))
        }
    }

    @Test
    fun relayUrlResolveTimeCheckReportsDnsFailures() {
        val failResolve: RelayHostResolver = { null }
        assertEquals(
            RelayResolveTimeCheckResult.Unavailable,
            relayUrlResolveTimeCheckResult("wss://relay.example", failResolve),
        )
        assertFalse(relayUrlPassesResolveTimeCheck("wss://relay.example", failResolve))
        runBlocking {
            assertEquals(
                RelayResolveTimeCheckResult.Unavailable,
                relayUrlsResolveTimeCheckResult(listOf("wss://relay.example"), failResolve),
            )
            assertFalse(relayUrlsPassResolveTimeChecks(listOf("wss://relay.example"), failResolve))
        }
    }

    @Test
    fun relayUrlResolveTimeCheckAcceptsPublicResolvedAddresses() {
        val resolveToPublic: RelayHostResolver = { arrayOf(ipv4(8, 8, 8, 8)) }
        assertEquals(
            RelayResolveTimeCheckResult.Passed,
            relayUrlResolveTimeCheckResult("wss://relay.example", resolveToPublic),
        )
        assertTrue(relayUrlPassesResolveTimeCheck("wss://relay.example", resolveToPublic))
        runBlocking {
            assertEquals(
                RelayResolveTimeCheckResult.Passed,
                relayUrlsResolveTimeCheckResult(listOf("wss://relay.example"), resolveToPublic),
            )
            assertTrue(relayUrlsPassResolveTimeChecks(listOf("wss://relay.example"), resolveToPublic))
        }
    }

    @Test
    fun relayUrlResolveTimeCheckLeavesCheapValidationSynchronous() {
        // UI validation must not require DNS; a hostname that would fail at
        // resolve-time still passes the cheap gate.
        assertTrue(isAcceptableRelayUrl("wss://rebind.example"))
        val resolveToLoopback: RelayHostResolver = { arrayOf(ipv4(127, 0, 0, 1)) }
        assertFalse(relayUrlPassesResolveTimeCheck("wss://rebind.example", resolveToLoopback))
    }

    private fun ipv4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): InetAddress = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    @Test
    fun bootstrapRelaysSatisfyRelayUrlValidation() {
        assertEquals(emptyList<String>(), MarmotClient.bootstrapRelays.filterNot { isAcceptableRelayUrl(it) })
        assertEquals(
            emptyList<String>(),
            MarmotClient.bootstrapRelays.filterNot { isAcceptableRelayUrl(it, allowExternalRelayHosts = false) },
        )
    }

    @Test
    fun bootstrapRelaysUseOnlyWhiteNoiseRegionalRelays() {
        assertEquals(
            listOf(
                "wss://relay.us.whitenoise.chat",
                "wss://relay.eu.whitenoise.chat",
            ),
            MarmotClient.bootstrapRelays,
        )
    }

    @Test
    fun accountRelayListsExposeOnlyNip65AndInboxKinds() {
        assertEquals(
            listOf(RelayListKind.Nip65, RelayListKind.Inbox),
            RelayListKind.entries.toList(),
        )
    }
}
