package dev.ipf.whitenoise.android.core.namecoin

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed extraction and ifa-0001 import-expansion tests for
 * [NamecoinRecord], ported from the extraction/import sections of
 * `tests/app/namecoin_test.odin` (whitenoise-linux commit 79447095f2).
 *
 * The fail-closed branches are security-critical: they previously silently
 * substituted `_` (the domain owner) or a "first valid entry" for a missing
 * requested identity. Both fallbacks are gone; a missing entry resolves to
 * NotFound instead of another party's pubkey.
 */
class NamecoinRecordTest {
    private fun domainParsed(input: String): NamecoinRecord.Parsed = NamecoinResolver.parse(input)!!

    // ── extractFromDomain: fail closed ─────────────────────────────────

    @Test
    fun domainMissingLocalPartFailsClosed() {
        // Looking up alice@example.bit against a record that only names bob
        // and `_` must return NotFound — falling back to `_` (the domain
        // owner) would silently attach the wrong identity to alice's invite.
        val nostr =
            JSONObject(
                """{"names":{"bob":"${"a".repeat(64)}","_":"${"b".repeat(64)}"}}""",
            )
        val outcome = NamecoinRecord.extractFromDomain(nostr, domainParsed("alice@example.bit"))
        assertEquals(NamecoinRecord.Extraction.NotFound, outcome)
    }

    @Test
    fun domainRootWithoutUnderscoreFailsClosed() {
        // A `names` map with entries but no `_` must return NotFound for the
        // root. Picking "the first valid key" would have been an
        // attacker-controlled substitution.
        val nostr =
            JSONObject(
                """{"names":{"alice":"${"c".repeat(64)}","bob":"${"d".repeat(64)}"}}""",
            )
        val outcome = NamecoinRecord.extractFromDomain(nostr, domainParsed("example.bit"))
        assertEquals(NamecoinRecord.Extraction.NotFound, outcome)
    }

    @Test
    fun domainExactMatchResolves() {
        // Positive control: alice@example.bit returns alice's pubkey.
        val pubkey = "1".repeat(64)
        val nostr = JSONObject("""{"names":{"alice":"$pubkey"}}""")
        val outcome = NamecoinRecord.extractFromDomain(nostr, domainParsed("alice@example.bit"))
        assertTrue(outcome is NamecoinRecord.Extraction.Resolved)
        val resolved = outcome as NamecoinRecord.Extraction.Resolved
        assertEquals(pubkey, resolved.pubkeyHex)
        assertEquals("alice", resolved.localPart)
    }

    @Test
    fun domainRootWithUnderscoreResolves() {
        val pubkey = "2".repeat(64)
        val bob = "3".repeat(64)
        val nostr = JSONObject("""{"names":{"_":"$pubkey","bob":"$bob"}}""")
        val outcome = NamecoinRecord.extractFromDomain(nostr, domainParsed("example.bit"))
        assertTrue(outcome is NamecoinRecord.Extraction.Resolved)
        val resolved = outcome as NamecoinRecord.Extraction.Resolved
        assertEquals(pubkey, resolved.pubkeyHex)
        assertEquals("_", resolved.localPart)
    }

    @Test
    fun domainRejectsNonHexEntry() {
        val nostr = JSONObject("""{"names":{"alice":"not-a-pubkey"}}""")
        val outcome = NamecoinRecord.extractFromDomain(nostr, domainParsed("alice@example.bit"))
        assertEquals(NamecoinRecord.Extraction.NotFound, outcome)
    }

    @Test
    fun domainRelaysKeyedByPubkey() {
        val pubkey = "a".repeat(64)
        val nostr =
            JSONObject(
                """{"names":{"alice":"$pubkey"},"relays":{"$pubkey":["wss://one","wss://two"]}}""",
            )
        val outcome = NamecoinRecord.extractFromDomain(nostr, domainParsed("alice@example.bit"))
        val resolved = outcome as NamecoinRecord.Extraction.Resolved
        assertEquals(listOf("wss://one", "wss://two"), resolved.relays)
    }

    // ── extractFromIdentity: no cross-shape substitution ──────────────

    @Test
    fun identityRejectsNamesFallback() {
        // An id/ record that carries only a `names` sub-object (no top-level
        // `pubkey`) must NotFound — accepting `names._` there would silently
        // return whatever key that map holds instead of the identity's own
        // pubkey.
        val nostr = JSONObject("""{"names":{"_":"${"e".repeat(64)}"}}""")
        assertEquals(NamecoinRecord.Extraction.NotFound, NamecoinRecord.extractFromIdentity(nostr))
    }

    @Test
    fun identityAcceptsFlatPubkey() {
        val pubkey = "9".repeat(64)
        val nostr = JSONObject("""{"pubkey":"$pubkey"}""")
        val outcome = NamecoinRecord.extractFromIdentity(nostr)
        assertTrue(outcome is NamecoinRecord.Extraction.Resolved)
        assertEquals(pubkey, (outcome as NamecoinRecord.Extraction.Resolved).pubkeyHex)
    }

    @Test
    fun identityRelaysFlatArray() {
        val pubkey = "f".repeat(64)
        val nostr = JSONObject("""{"pubkey":"$pubkey","relays":["wss://relay"]}""")
        val outcome = NamecoinRecord.extractFromIdentity(nostr)
        assertEquals(listOf("wss://relay"), (outcome as NamecoinRecord.Extraction.Resolved).relays)
    }

    // ── extractNostr: simple string form ───────────────────────────────

    @Test
    fun simpleNostrStringOnlyResolvesForRoot() {
        val value = JSONObject("""{"nostr":"${"7".repeat(64)}"}""")
        // Root of a domain: resolves.
        val root = NamecoinRecord.extractNostr(value, domainParsed("example.bit"))
        assertTrue(root is NamecoinRecord.Extraction.Resolved)
        // Named local part: a bare string cannot name them. Fail closed.
        val named = NamecoinRecord.extractNostr(value, domainParsed("alice@example.bit"))
        assertEquals(NamecoinRecord.Extraction.NotFound, named)
    }

    // ── import expansion ───────────────────────────────────────────────

    @Test
    fun noImportReturnsEquivalentTree() =
        runBlocking {
            val raw = """{"nostr":{"pubkey":"${"a".repeat(64)}"}}"""
            val merged = NamecoinRecord.resolveValueWithImports(JSONObject(raw), fetch = { null })
            assertEquals(
                JSONObject(raw).getJSONObject("nostr").getString("pubkey"),
                merged.getJSONObject("nostr").getString("pubkey"),
            )
        }

    @Test
    fun emptyImportDropsImportKey() =
        runBlocking {
            // The reviewer explicitly called out `"import":[]` aborting
            // upstream before the clone-based rewrite. Guard it.
            val raw = """{"import":[],"nostr":{"pubkey":"${"b".repeat(64)}"}}"""
            val merged = NamecoinRecord.resolveValueWithImports(JSONObject(raw), fetch = { null })
            assertFalse(merged.has("import"))
            assertTrue(merged.has("nostr"))
        }

    @Test
    fun nestedMapSurvivesExpansion() =
        runBlocking {
            val raw =
                """{"import":[],"nostr":{"names":{"alice":"${"c".repeat(64)}"},""" +
                """"relays":{"${"c".repeat(64)}":["wss://one","wss://two"]}}}"""
            val merged = NamecoinRecord.resolveValueWithImports(JSONObject(raw), fetch = { null })
            val names = merged.getJSONObject("nostr").getJSONObject("names")
            assertEquals("c".repeat(64), names.getString("alice"))
            val relays =
                merged
                    .getJSONObject("nostr")
                    .getJSONObject("relays")
                    .getJSONArray("c".repeat(64))
            assertEquals(2, relays.length())
        }

    @Test
    fun importStringShorthandMerges() =
        runBlocking {
            val imported = JSONObject("""{"nostr":{"pubkey":"${"d".repeat(64)}"}}""")
            val root = JSONObject("""{"import":"d/other"}""")
            val merged =
                NamecoinRecord.resolveValueWithImports(root, fetch = { name ->
                    if (name == "d/other") imported else null
                })
            assertFalse(merged.has("import"))
            assertEquals("d".repeat(64), merged.getJSONObject("nostr").getString("pubkey"))
        }

    @Test
    fun importerWinsOverImportedKeys() =
        runBlocking {
            val imported = JSONObject("""{"nostr":{"pubkey":"${"e".repeat(64)}"},"other":1}""")
            val root =
                JSONObject("""{"import":"d/other","nostr":{"pubkey":"${"f".repeat(64)}"}}""")
            val merged = NamecoinRecord.resolveValueWithImports(root, fetch = { imported })
            // Importer's own nostr wins; the import's extra key merges in.
            assertEquals("f".repeat(64), merged.getJSONObject("nostr").getString("pubkey"))
            assertEquals(1, merged.getInt("other"))
        }

    @Test
    fun importSelectorWalksMap() =
        runBlocking {
            val imported =
                JSONObject(
                    """{"map":{"foo":{"nostr":{"pubkey":"${"1".repeat(64)}"}}}}""",
                )
            val root = JSONObject("""{"import":[["d/other","foo"]]}""")
            val merged = NamecoinRecord.resolveValueWithImports(root, fetch = { imported })
            assertEquals("1".repeat(64), merged.getJSONObject("nostr").getString("pubkey"))
        }

    @Test
    fun importSelectorFallsBackToWildcard() =
        runBlocking {
            val imported =
                JSONObject(
                    """{"map":{"*":{"nostr":{"pubkey":"${"2".repeat(64)}"}}}}""",
                )
            val root = JSONObject("""{"import":[["d/other","missing"]]}""")
            val merged = NamecoinRecord.resolveValueWithImports(root, fetch = { imported })
            assertEquals("2".repeat(64), merged.getJSONObject("nostr").getString("pubkey"))
        }

    @Test
    fun importCycleDoesNotRecurse() =
        runBlocking {
            // d/a imports d/b, d/b imports d/a. The visited set must stop the
            // cycle; both merges complete with whatever was available.
            val a = JSONObject("""{"import":"d/b","a":1}""")
            val b = JSONObject("""{"import":"d/a","b":2}""")
            val merged =
                NamecoinRecord.resolveValueWithImports(a, fetch = { name ->
                    when (name) {
                        "d/b" -> b
                        "d/a" -> a
                        else -> null
                    }
                })
            assertEquals(1, merged.getInt("a"))
            assertEquals(2, merged.getInt("b"))
        }

    @Test
    fun importDepthBudgetStopsChain() =
        runBlocking {
            // A self-importing record would recurse forever without the
            // budget; with the ifa-0001 default depth of 4 it stops and keeps
            // the base.
            val self = JSONObject("""{"import":"d/self","marker":true}""")
            val merged = NamecoinRecord.resolveValueWithImports(self, fetch = { self })
            assertTrue(merged.getBoolean("marker"))
            assertFalse(merged.has("import"))
        }

    @Test
    fun unavailableImportContributesNothing() =
        runBlocking {
            val root = JSONObject("""{"import":"d/missing","nostr":{"pubkey":"${"3".repeat(64)}"}}""")
            val merged = NamecoinRecord.resolveValueWithImports(root, fetch = { null })
            assertEquals("3".repeat(64), merged.getJSONObject("nostr").getString("pubkey"))
        }

    @Test
    fun expansionDoesNotMutateSource() =
        runBlocking {
            val root = JSONObject("""{"import":"d/other","nostr":{"pubkey":"${"4".repeat(64)}"}}""")
            NamecoinRecord.resolveValueWithImports(root, fetch = { JSONObject("""{"extra":true}""") })
            // Non-destructive contract: the source tree is untouched.
            assertTrue(root.has("import"))
            assertFalse(root.has("extra"))
        }
}
