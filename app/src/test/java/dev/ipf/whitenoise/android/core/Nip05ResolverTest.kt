package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the input-validation paths of [Nip05Resolver] that short-circuit to
 * null BEFORE any network request — malformed identifiers and private/loopback
 * domains. The happy path (a real `/.well-known/nostr.json` fetch) is left to
 * instrumented/manual testing so this unit test never touches the network.
 */
class Nip05ResolverTest {
    @Test
    fun rejectsMalformedIdentifiersWithoutNetwork() =
        runBlocking {
            assertNull(Nip05Resolver.resolve(""))
            assertNull(Nip05Resolver.resolve("   "))
            assertNull(Nip05Resolver.resolve("alice"))
            assertNull(Nip05Resolver.resolve("alice@"))
            assertNull(Nip05Resolver.resolve("@example.com"))
            // No dot in the domain.
            assertNull(Nip05Resolver.resolve("alice@localhost"))
            // Extra '@'.
            assertNull(Nip05Resolver.resolve("a@b@example.com"))
            // Whitespace inside.
            assertNull(Nip05Resolver.resolve("al ice@example.com"))
            // Local part outside the NIP-05 character set.
            assertNull(Nip05Resolver.resolve("al/ice@example.com"))
        }

    @Test
    fun rejectsPrivateAndLoopbackDomainsWithoutNetwork() =
        runBlocking {
            // Domain still needs a dot to be a NIP-05 shape; these have one and
            // resolve to private/loopback addresses, so HostSafety blocks them.
            assertNull(Nip05Resolver.resolve("alice@127.0.0.1"))
            assertNull(Nip05Resolver.resolve("alice@10.0.0.1"))
            assertNull(Nip05Resolver.resolve("alice@192.168.1.1"))
            assertNull(Nip05Resolver.resolve("alice@169.254.1.1"))
            assertNull(Nip05Resolver.resolve("alice@foo.localhost"))
        }

    @Test
    fun rejectsExplicitPortsWithoutNetwork() =
        runBlocking {
            // NIP-05 well-known is served on the implicit HTTPS port. Any
            // explicit port is a URL authority delimiter, not a hostname
            // character, and is rejected before URL construction or network.
            assertNull(Nip05Resolver.resolve("alice@example.com:8080"))
            assertNull(Nip05Resolver.resolve("alice@example.com:443"))
            assertNull(Nip05Resolver.resolve("alice@example.com:80"))
            assertNull(Nip05Resolver.resolve("alice@example.com:22"))
        }

    @Test
    fun rejectsUrlDelimitersInDomainBeforeBuildingWellKnownUrl() {
        val invalidDomains =
            listOf(
                "example.com/path",
                "example.com?name=bob",
                "example.com:443",
                "example.com\\evil",
            )

        invalidDomains.forEach { domain ->
            assertNull("domain=$domain", Nip05Resolver.buildUrl(local = "alice", domain = domain))
        }
    }

    @Test
    fun buildsWellKnownUrlForStrictHostnameDomain() {
        assertEquals(
            "https://example.com/.well-known/nostr.json?name=alice",
            Nip05Resolver.buildUrl(local = "alice", domain = "example.com")?.toString(),
        )
    }
}
