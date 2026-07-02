package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure parts of [Lud16Resolver]: address parsing / well-known URL
 * construction and LNURL-pay `payRequest` response validation. The happy path
 * (a real `/.well-known/lnurlp/<name>` fetch) is left to instrumented/manual
 * testing so this unit test never touches the network — every `resolve` call
 * here short-circuits before any request.
 */
class Lud16ResolverTest {
    @Test
    fun buildsWellKnownUrlWithLowercasedDomainAndPreservedLocalCase() {
        assertEquals(
            "https://example.com/.well-known/lnurlp/alice",
            Lud16Resolver.wellKnownUrl("alice@example.com"),
        )
        // Domain is lowercased; the local part's case is preserved (#795).
        assertEquals(
            "https://example.com/.well-known/lnurlp/Alice",
            Lud16Resolver.wellKnownUrl("Alice@EXAMPLE.com"),
        )
        // Surrounding whitespace is trimmed before parsing.
        assertEquals(
            "https://wallet.example.com/.well-known/lnurlp/a_b-c.d",
            Lud16Resolver.wellKnownUrl("  a_b-c.d@wallet.example.com  "),
        )
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertNull(Lud16Resolver.wellKnownUrl(""))
        assertNull(Lud16Resolver.wellKnownUrl("   "))
        assertNull(Lud16Resolver.wellKnownUrl("alice"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@"))
        assertNull(Lud16Resolver.wellKnownUrl("@example.com"))
        // No dot in the domain.
        assertNull(Lud16Resolver.wellKnownUrl("alice@localhost"))
        // Extra '@'.
        assertNull(Lud16Resolver.wellKnownUrl("a@b@example.com"))
        // Whitespace inside.
        assertNull(Lud16Resolver.wellKnownUrl("al ice@example.com"))
        // Local part outside the LUD-16 character set (also a path-injection
        // vector since the local part lands in the URL path).
        assertNull(Lud16Resolver.wellKnownUrl("al/ice@example.com"))
        assertNull(Lud16Resolver.wellKnownUrl("../etc@example.com"))
        // A bare dot segment would traverse out of /.well-known/lnurlp/.
        assertNull(Lud16Resolver.wellKnownUrl(".@example.com"))
        assertNull(Lud16Resolver.wellKnownUrl("..@example.com"))
    }

    @Test
    fun rejectsMalformedDomainsBeforeUrlConstruction() {
        assertNull(Lud16Resolver.wellKnownUrl("alice@example.com/path"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@example.com?x=y"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@example.com#fragment"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@https://example.com"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@example..com"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@-example.com"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@example-.com"))
    }

    @Test
    fun rejectsPrivateLoopbackAndExplicitPortHosts() {
        assertNull(Lud16Resolver.wellKnownUrl("alice@127.0.0.1"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@10.0.0.1"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@192.168.1.1"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@169.254.1.1"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@foo.localhost"))
        // LNURL-pay is served on the implicit HTTPS port only.
        assertNull(Lud16Resolver.wellKnownUrl("alice@example.com:8080"))
        assertNull(Lud16Resolver.wellKnownUrl("alice@example.com:443"))
    }

    @Test
    fun resolveShortCircuitsOnBadInputWithoutNetwork() =
        runBlocking {
            assertFalse(Lud16Resolver.resolve(""))
            assertFalse(Lud16Resolver.resolve("alice"))
            assertFalse(Lud16Resolver.resolve("alice@localhost"))
            assertFalse(Lud16Resolver.resolve("alice@192.168.1.1"))
            assertFalse(Lud16Resolver.resolve("alice@example.com:8080"))
        }

    @Test
    fun acceptsValidPayRequest() {
        assertTrue(
            Lud16Resolver.isPayRequest(
                """
                {
                  "tag": "payRequest",
                  "callback": "https://example.com/lnurlp/alice/callback",
                  "minSendable": 1000,
                  "maxSendable": 100000000,
                  "metadata": "[[\"text/plain\",\"pay alice\"]]"
                }
                """.trimIndent(),
            ),
        )
        // min == max is a legal (fixed-amount) range.
        assertTrue(
            Lud16Resolver.isPayRequest(
                """{"tag":"payRequest","callback":"https://x.co/cb","minSendable":21,"maxSendable":21}""",
            ),
        )
    }

    @Test
    fun rejectsMalformedPayRequest() {
        assertFalse(Lud16Resolver.isPayRequest(""))
        assertFalse(Lud16Resolver.isPayRequest("not json"))
        assertFalse(Lud16Resolver.isPayRequest("[]"))
        // Wrong or missing tag.
        assertFalse(
            Lud16Resolver.isPayRequest(
                """{"tag":"withdrawRequest","callback":"https://x.co/cb","minSendable":1,"maxSendable":2}""",
            ),
        )
        assertFalse(
            Lud16Resolver.isPayRequest("""{"callback":"https://x.co/cb","minSendable":1,"maxSendable":2}"""),
        )
        // Callback missing, non-URL, or not HTTPS.
        assertFalse(Lud16Resolver.isPayRequest("""{"tag":"payRequest","minSendable":1,"maxSendable":2}"""))
        assertFalse(
            Lud16Resolver.isPayRequest("""{"tag":"payRequest","callback":"nonsense","minSendable":1,"maxSendable":2}"""),
        )
        assertFalse(
            Lud16Resolver.isPayRequest("""{"tag":"payRequest","callback":"http://x.co/cb","minSendable":1,"maxSendable":2}"""),
        )
        // Sendable bounds missing, non-numeric, zero, or inverted.
        assertFalse(
            Lud16Resolver.isPayRequest("""{"tag":"payRequest","callback":"https://x.co/cb","maxSendable":2}"""),
        )
        assertFalse(
            Lud16Resolver.isPayRequest("""{"tag":"payRequest","callback":"https://x.co/cb","minSendable":1}"""),
        )
        assertFalse(
            Lud16Resolver.isPayRequest(
                """{"tag":"payRequest","callback":"https://x.co/cb","minSendable":"1000","maxSendable":2000}""",
            ),
        )
        assertFalse(
            Lud16Resolver.isPayRequest(
                """{"tag":"payRequest","callback":"https://x.co/cb","minSendable":0,"maxSendable":2}""",
            ),
        )
        assertFalse(
            Lud16Resolver.isPayRequest(
                """{"tag":"payRequest","callback":"https://x.co/cb","minSendable":3,"maxSendable":2}""",
            ),
        )
    }
}
