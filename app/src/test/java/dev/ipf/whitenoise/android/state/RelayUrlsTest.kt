package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.whitenoise.android.core.MarmotClient
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
    fun relayUrlValidationAllowsWhiteNoiseAndExternalPublicHosts() {
        assertTrue(isAcceptableRelayUrl("wss://relay.us.whitenoise.chat"))
        assertTrue(isAcceptableRelayUrl("wss://relay.eu.whitenoise.chat"))
        assertTrue(isAcceptableRelayUrl("wss://relay.example"))
        assertEquals(
            listOf("wss://relay.example"),
            normalizeRelayUrls(listOf("wss://relay.example")),
        )
    }

    @Test
    fun relayUrlValidationDistinguishesValidExternalHostFromInvalidUrl() {
        assertEquals(
            RelayUrlValidationResult.Invalid,
            relayUrlValidationResult("https://relay.example"),
        )
        assertEquals(
            RelayUrlValidationResult.Acceptable,
            relayUrlValidationResult("wss://relay.example"),
        )
    }

    @Test
    fun additionPreservesImportedExternalRelaysWhenAppendingWhiteNoiseRelay() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://external.example", "wss://relay.us.whitenoise.chat"),
                requiredRelay = "wss://relay.us.whitenoise.chat",
            ),
            relayListAfterAddition(
                currentRelays = listOf("wss://external.example"),
                relayToAdd = "wss://relay.us.whitenoise.chat",
            ),
        )
    }

    @Test
    fun additionAcceptsExternalRelayAndPreservesWhiteNoiseRelay() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://relay.us.whitenoise.chat", "wss://external.example"),
                requiredRelay = "wss://external.example",
            ),
            relayListAfterAddition(
                currentRelays = listOf("wss://relay.us.whitenoise.chat"),
                relayToAdd = "wss://external.example",
            ),
        )
    }

    @Test
    fun removalPreservesUnselectedImportedExternalRelays() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://relay.us.whitenoise.chat", "wss://two.external.example"),
            ),
            relayListAfterRemoval(
                currentRelays =
                    listOf(
                        "wss://relay.us.whitenoise.chat",
                        "wss://one.external.example",
                        "wss://two.external.example",
                    ),
                relayToRemove = "wss://one.external.example",
            ),
        )
    }

    @Test
    fun removalKeepsRemainingExternalRelayWithoutReplacingItWithDefaults() {
        assertEquals(
            RelayListEditPlan(
                relays = listOf("wss://two.external.example"),
            ),
            relayListAfterRemoval(
                currentRelays = listOf("wss://one.external.example", "wss://two.external.example"),
                relayToRemove = "wss://one.external.example",
            ),
        )
    }

    @Test
    fun removalFallsBackToWhiteNoiseRelaysWhenNoRelayRemains() {
        assertEquals(
            RelayListEditPlan(
                relays = MarmotClient.bootstrapRelays,
            ),
            relayListAfterRemoval(
                currentRelays = listOf("wss://one.external.example"),
                relayToRemove = "wss://one.external.example",
            ),
        )
    }

    @Test
    fun externalRelayKeepsWhiteNoiseRelayRemovable() {
        val relays = listOf("wss://relay.us.whitenoise.chat", "wss://external.example")

        assertTrue(canRemoveRelay(currentRelays = relays, relay = "wss://relay.us.whitenoise.chat"))
        assertTrue(canRemoveRelay(currentRelays = relays, relay = "wss://external.example"))
        assertFalse(canRemoveRelay(currentRelays = listOf(relays.first()), relay = relays.first()))
    }

    @Test
    fun publishClassificationPreservesAllowedExternalRelaysAndDropsUnsafeImports() {
        val plan =
            RelayListEditPlan(
                relays = listOf("wss://external.example", "wss://retired.example", "wss://relay.us.whitenoise.chat"),
                requiredRelay = "wss://relay.us.whitenoise.chat",
            )

        assertEquals(
            listOf("wss://external.example", "wss://relay.us.whitenoise.chat"),
            allowedRelayUrlsForPublish(
                plan,
                listOf(
                    classified("wss://external.example", RelayEndpointPolicyFfi.ALLOWED),
                    classified("wss://retired.example", RelayEndpointPolicyFfi.RETIRED),
                    classified("wss://relay.us.whitenoise.chat", RelayEndpointPolicyFfi.ALLOWED),
                ),
            ),
        )
    }

    @Test
    fun publishClassificationRejectsNewRelayThatMarmotKitBlocks() {
        val newRelay = "wss://unsafe.example"

        assertEquals(
            null,
            allowedRelayUrlsForPublish(
                RelayListEditPlan(
                    relays = listOf("wss://external.example", newRelay),
                    requiredRelay = newRelay,
                ),
                listOf(
                    classified("wss://external.example", RelayEndpointPolicyFfi.ALLOWED),
                    classified(newRelay, RelayEndpointPolicyFfi.UNSAFE),
                ),
            ),
        )
    }

    @Test
    fun publishClassificationAcceptsMarmotKitTrailingSlashNormalization() {
        val newRelay = "wss://external.example"

        assertEquals(
            listOf("wss://external.example/"),
            allowedRelayUrlsForPublish(
                RelayListEditPlan(relays = listOf(newRelay), requiredRelay = newRelay),
                listOf(
                    classified(
                        endpoint = newRelay,
                        policy = RelayEndpointPolicyFfi.ALLOWED,
                        normalizedEndpoint = "$newRelay/",
                    ),
                ),
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
    }

    @Test
    fun relayUrlResolveTimeCheckReportsDnsFailures() {
        val failResolve: RelayHostResolver = { null }
        assertEquals(
            RelayResolveTimeCheckResult.Unavailable,
            relayUrlResolveTimeCheckResult("wss://relay.example", failResolve),
        )
        assertFalse(relayUrlPassesResolveTimeCheck("wss://relay.example", failResolve))
    }

    @Test
    fun relayUrlResolveTimeCheckAcceptsPublicResolvedAddresses() {
        val resolveToPublic: RelayHostResolver = { arrayOf(ipv4(8, 8, 8, 8)) }
        assertEquals(
            RelayResolveTimeCheckResult.Passed,
            relayUrlResolveTimeCheckResult("wss://relay.example", resolveToPublic),
        )
        assertTrue(relayUrlPassesResolveTimeCheck("wss://relay.example", resolveToPublic))
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

    private fun classified(
        endpoint: String,
        policy: RelayEndpointPolicyFfi,
        normalizedEndpoint: String? = endpoint,
    ): RelayEndpointClassificationFfi =
        RelayEndpointClassificationFfi(
            endpoint = endpoint,
            normalizedEndpoint = normalizedEndpoint,
            policy = policy,
        )

    @Test
    fun bootstrapRelaysSatisfyRelayUrlValidation() {
        assertEquals(emptyList<String>(), MarmotClient.bootstrapRelays.filterNot { isAcceptableRelayUrl(it) })
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
