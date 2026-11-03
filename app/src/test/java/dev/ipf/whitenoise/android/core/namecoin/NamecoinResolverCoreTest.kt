package dev.ipf.whitenoise.android.core.namecoin

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolver-pipeline tests for [NamecoinResolverCore]: the ElectrumX call
 * sequence, server fallback semantics, expiry, caching, and the in-flight
 * dedup that replaces the upstream pending-queue dedup. All network is
 * faked; these tests never open a socket.
 */
class NamecoinResolverCoreTest {
    private val servers =
        listOf(
            NamecoinTransport.NamecoinServer("one.example", 50004, pinBase64 = "cGluMQ=="),
            NamecoinTransport.NamecoinServer("two.example", 50004, pinBase64 = "cGluMg=="),
        )

    /** Canned ElectrumX answers keyed per server; null means transport failure. */
    private class FakeTransport(
        val latencyMillis: Long = 0,
        val latencyByServer: Map<String, Long> = emptyMap(),
        val handler: (server: String, id: Int, method: String, params: List<String>) -> String?,
    ) : NamecoinTransport() {
        val requests = ConcurrentLinkedQueue<String>()
        val startedAt = ConcurrentLinkedQueue<Pair<String, Long>>()

        override suspend fun call(
            server: NamecoinServer,
            requestJson: String,
            wantId: Int,
            timeoutMillis: Long,
        ): String? {
            startedAt += server.host to System.nanoTime()
            val latency = latencyByServer[server.host] ?: latencyMillis
            if (latency > 0) delay(latency)
            requests += requestJson
            val req = JSONObject(requestJson)
            val params =
                req
                    .optJSONArray("params")
                    ?.let { arr ->
                        buildList {
                            for (i in 0 until arr.length()) add(arr.opt(i)?.toString() ?: "")
                        }
                    }.orEmpty()
            return handler(server.host, req.getInt("id"), req.getString("method"), params)
        }
    }

    /** Value-carrying OP_NAME_UPDATE vout hex for [name] -> [value]. */
    private fun nameUpdateVoutHex(
        name: String,
        value: String,
    ): String {
        val script =
            byteArrayOf(0x53) + pushEncoded(name.toByteArray()) + pushEncoded(value.toByteArray()) +
                byteArrayOf(0x6d, 0x75, 0x6a)
        return script.joinToString("") { "%02x".format(it) }
    }

    /** Direct-length push below 0x4c, PUSHDATA1 beyond — the real consensus encoding. */
    private fun pushEncoded(data: ByteArray): ByteArray =
        if (data.size < 0x4c) {
            byteArrayOf(data.size.toByte()) + data
        } else {
            byteArrayOf(0x4c, data.size.toByte()) + data
        }

    /** The scripthash an ElectrumX server would be queried with for [name]. */
    private fun scripthashOf(name: String): String = NamecoinScript.electrumScriptHash(NamecoinScript.buildNameIndexScript(name.toByteArray()))

    /**
     * The canonical happy-path handler: version handshake, one history entry,
     * the tx carrying [valueJson] for [name], headers at [currentHeight].
     */
    private fun happyPath(
        name: String,
        valueJson: String,
        historyHeight: Long = 100,
        currentHeight: Long = 200,
    ): (String, Int, String, List<String>) -> String? =
        { _, id, method, params ->
            when (method) {
                "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                "blockchain.scripthash.get_history" -> {
                    if (params.firstOrNull() == scripthashOf(name)) {
                        """{"jsonrpc":"2.0","id":$id,"result":[{"tx_hash":"abcd","height":$historyHeight}]}"""
                    } else {
                        """{"jsonrpc":"2.0","id":$id,"result":[]}"""
                    }
                }
                "blockchain.transaction.get" ->
                    """{"jsonrpc":"2.0","id":$id,"result":{"vout":[""" +
                        """{"scriptPubKey":{"hex":"${nameUpdateVoutHex(name, valueJson)}"}}]}}"""
                "blockchain.headers.subscribe" ->
                    """{"jsonrpc":"2.0","id":$id,"result":{"height":$currentHeight}}"""
                else -> null
            }
        }

    private fun coreFor(
        transport: FakeTransport,
        serverList: List<NamecoinTransport.NamecoinServer> = servers,
        hedgeDelayMillis: Long = 1_500,
        negativeGraceMillis: Long = 1_500,
        resolverTimeoutMillis: Long = 20_000,
    ) = NamecoinResolverCore(
        servers = serverList,
        transport = transport,
        requestTimeoutMillis = 5_000,
        resolverTimeoutMillis = resolverTimeoutMillis,
        hedgeDelayMillis = hedgeDelayMillis,
        negativeGraceMillis = negativeGraceMillis,
    )

    // ── happy path ─────────────────────────────────────────────────────

    @Test
    fun resolvesIdentityShapeRecord() =
        runBlocking {
            val pubkey = "a".repeat(64)
            val transport =
                FakeTransport(
                    handler = happyPath("d/example", """{"nostr":{"pubkey":"$pubkey"}}"""),
                )
            val result = coreFor(transport).resolve("example.bit")
            assertTrue(result is NamecoinResolver.NamecoinResult.Resolved)
            val resolved = result as NamecoinResolver.NamecoinResult.Resolved
            assertEquals(pubkey, resolved.pubkeyHex)
            assertEquals("d/example", resolved.namecoinName)
            assertEquals("_", resolved.localPart)
        }

    @Test
    fun resolvesDomainNamesEntryForLocalPart() =
        runBlocking {
            val alice = "b".repeat(64)
            val owner = "c".repeat(64)
            val value = """{"nostr":{"names":{"alice":"$alice","_":"$owner"}}}"""
            val transport = FakeTransport(handler = happyPath("d/example", value))
            val result = coreFor(transport).resolve("alice@example.bit")
            assertTrue(result is NamecoinResolver.NamecoinResult.Resolved)
            assertEquals(alice, (result as NamecoinResolver.NamecoinResult.Resolved).pubkeyHex)
        }

    @Test
    fun domainRecordMissingLocalPartFailsClosed() =
        runBlocking {
            // alice@example.bit against a record naming only bob + `_`: the
            // domain owner's key must NOT be substituted.
            val value = """{"nostr":{"names":{"bob":"${"d".repeat(64)}","_":"${"e".repeat(64)}"}}}"""
            val transport = FakeTransport(handler = happyPath("d/example", value))
            val result = coreFor(transport).resolve("alice@example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, result)
        }

    @Test
    fun resolvesThroughImportChain() =
        runBlocking {
            val pubkey = "f".repeat(64)
            val transport =
                FakeTransport { _, id, method, params ->
                    when (method) {
                        "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                        "blockchain.scripthash.get_history" -> {
                            when (params.firstOrNull()) {
                                scripthashOf("d/example"), scripthashOf("d/imported") ->
                                    """{"jsonrpc":"2.0","id":$id,"result":[{"tx_hash":"abcd","height":100}]}"""
                                else -> """{"jsonrpc":"2.0","id":$id,"result":[]}"""
                            }
                        }
                        "blockchain.transaction.get" -> {
                            // The fake history returns the same tx hash for
                            // both names; answer with the vout matching the
                            // scripthash the caller asked about by echoing
                            // both name vouts — the extractor picks by name.
                            val example = nameUpdateVoutHex("d/example", """{"import":"d/imported"}""")
                            val imported =
                                nameUpdateVoutHex("d/imported", """{"nostr":{"pubkey":"$pubkey"}}""")
                            """{"jsonrpc":"2.0","id":$id,"result":{"vout":[""" +
                                """{"scriptPubKey":{"hex":"$example"}},{"scriptPubKey":{"hex":"$imported"}}]}}"""
                        }
                        "blockchain.headers.subscribe" -> """{"jsonrpc":"2.0","id":$id,"result":{"height":200}}"""
                        else -> null
                    }
                }
            val result = coreFor(transport).resolve("example.bit")
            assertTrue(result is NamecoinResolver.NamecoinResult.Resolved)
            assertEquals(pubkey, (result as NamecoinResolver.NamecoinResult.Resolved).pubkeyHex)
        }

    // ── fallback semantics ─────────────────────────────────────────────

    @Test
    fun emptyHistoryIsDefinitiveNotFound() =
        runBlocking {
            val transport =
                FakeTransport { _, id, method, _ ->
                    when (method) {
                        "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                        "blockchain.scripthash.get_history" -> """{"jsonrpc":"2.0","id":$id,"result":[]}"""
                        "blockchain.headers.subscribe" -> """{"jsonrpc":"2.0","id":$id,"result":{"height":200}}"""
                        else -> null
                    }
                }
            val result = coreFor(transport).resolve("example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, result)
        }

    @Test
    fun allServersTransportFailureIsUnavailable() =
        runBlocking {
            val transport = FakeTransport { _, _, _, _ -> null }
            val result = coreFor(transport).resolve("example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.Unavailable, result)
        }

    @Test
    fun fallsBackToSecondServer() =
        runBlocking {
            val pubkey = "1".repeat(64)
            val happy = happyPath("d/example", """{"nostr":{"pubkey":"$pubkey"}}""")
            val transport =
                FakeTransport { server, id, method, params ->
                    if (server == "one.example") {
                        null // first server down
                    } else {
                        happy(server, id, method, params)
                    }
                }
            val result = coreFor(transport).resolve("example.bit")
            assertTrue(result is NamecoinResolver.NamecoinResult.Resolved)
            assertEquals(pubkey, (result as NamecoinResolver.NamecoinResult.Resolved).pubkeyHex)
        }

    @Test
    fun definitiveMissLatchesAcrossTransportFailure() =
        runBlocking {
            // Server one says definitively "no such name" (empty history),
            // server two is down: the latched miss wins over the transport
            // failure, matching upstream.
            val transport =
                FakeTransport { server, id, method, _ ->
                    if (server == "two.example") return@FakeTransport null
                    when (method) {
                        "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                        "blockchain.scripthash.get_history" -> """{"jsonrpc":"2.0","id":$id,"result":[]}"""
                        else -> null
                    }
                }
            val result = coreFor(transport).resolve("example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, result)
        }

    @Test
    fun expiredNameIsNotFound() =
        runBlocking {
            // Last update 100, chain tip 40000: 39900 >= 36000 deep → expired.
            val transport =
                FakeTransport(
                    handler =
                        happyPath(
                            "d/example",
                            """{"nostr":{"pubkey":"${"2".repeat(64)}"}}""",
                            historyHeight = 100,
                            currentHeight = 40_000,
                        ),
                )
            val result = coreFor(transport).resolve("example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, result)
        }

    @Test
    fun txWithoutNameValueIsNotFound() =
        runBlocking {
            // History points at a tx whose vouts carry a DIFFERENT name.
            val transport =
                FakeTransport { _, id, method, params ->
                    when (method) {
                        "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                        "blockchain.scripthash.get_history" ->
                            """{"jsonrpc":"2.0","id":$id,"result":[{"tx_hash":"abcd","height":100}]}"""
                        "blockchain.transaction.get" ->
                            """{"jsonrpc":"2.0","id":$id,"result":{"vout":[""" +
                                """{"scriptPubKey":{"hex":"${nameUpdateVoutHex("d/other", "{}")}"}}]}}"""
                        "blockchain.headers.subscribe" -> """{"jsonrpc":"2.0","id":$id,"result":{"height":200}}"""
                        else -> null
                    }
                }
            val result = coreFor(transport).resolve("example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, result)
        }

    @Test
    fun slowPositiveBeatsFastNegativeAndStopsNewHedges() =
        runBlocking {
            val pubkey = "6".repeat(64)
            val happy = happyPath("d/example", """{"nostr":{"pubkey":"$pubkey"}}""")
            val third = NamecoinTransport.NamecoinServer("three.example", 50004, pinBase64 = "cGluMw==")
            val transport =
                FakeTransport(latencyByServer = mapOf("one.example" to 100)) { server, id, method, params ->
                    when {
                        server == "one.example" -> happy(server, id, method, params)
                        method == "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                        method == "blockchain.scripthash.get_history" -> """{"jsonrpc":"2.0","id":$id,"result":[]}"""
                        else -> null
                    }
                }
            val result =
                coreFor(
                    transport = transport,
                    serverList = servers + third,
                    hedgeDelayMillis = 50,
                    negativeGraceMillis = 600,
                ).resolve("example.bit")
            assertEquals(pubkey, (result as NamecoinResolver.NamecoinResult.Resolved).pubkeyHex)
            assertTrue(transport.startedAt.none { it.first == "three.example" })
        }

    @Test
    fun startsNextServerAfterHedgeDelay() =
        runBlocking {
            val pubkey = "7".repeat(64)
            val happy = happyPath("d/example", """{"nostr":{"pubkey":"$pubkey"}}""")
            val transport =
                FakeTransport(latencyByServer = mapOf("one.example" to 500)) { server, id, method, params ->
                    if (server == "one.example") null else happy(server, id, method, params)
                }
            val result = coreFor(transport, hedgeDelayMillis = 80).resolve("example.bit")
            assertTrue(result is NamecoinResolver.NamecoinResult.Resolved)
            val first = transport.startedAt.first { it.first == "one.example" }.second
            val second = transport.startedAt.first { it.first == "two.example" }.second
            val delayMillis = (second - first) / 1_000_000
            assertTrue("second hedge started after ${delayMillis}ms", delayMillis >= 60)
            assertTrue("failure did not wait for the stalled first server", delayMillis < 400)
        }

    @Test
    fun emptyServerListIsUnavailable() =
        runBlocking {
            val transport = FakeTransport { _, _, _, _ -> error("must not be called") }
            assertEquals(
                NamecoinResolver.NamecoinResult.Unavailable,
                coreFor(transport, serverList = emptyList()).resolve("example.bit"),
            )
        }

    @Test
    fun overallResolverBudgetBoundsHedging() =
        runBlocking {
            val transport = FakeTransport(latencyMillis = 5_000) { _, _, _, _ -> null }
            val started = System.nanoTime()
            val result = coreFor(transport, resolverTimeoutMillis = 150).resolve("example.bit")
            assertEquals(NamecoinResolver.NamecoinResult.Unavailable, result)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("resolver budget took ${elapsed}ms", elapsed < 2_000)
        }

    // ── caching + dedup ────────────────────────────────────────────────

    @Test
    fun positiveResultIsCached() =
        runBlocking {
            val pubkey = "3".repeat(64)
            val historyCalls = AtomicInteger(0)
            val happy = happyPath("d/example", """{"nostr":{"pubkey":"$pubkey"}}""")
            val transport =
                FakeTransport { server, id, method, params ->
                    if (method == "blockchain.scripthash.get_history") historyCalls.incrementAndGet()
                    happy(server, id, method, params)
                }
            val core = coreFor(transport)
            repeat(3) {
                val result = core.resolve("Example.BIT")
                assertTrue(result is NamecoinResolver.NamecoinResult.Resolved)
            }
            // The first server answers, so exactly one network lookup happens;
            // repeat resolves read the session cache.
            assertEquals(1, historyCalls.get())
        }

    @Test
    fun negativeResultIsCachedButUnavailableIsNot() =
        runBlocking {
            val historyCalls = AtomicInteger(0)
            val versionCalls = AtomicInteger(0)
            var available = true
            val transport =
                FakeTransport { _, id, method, _ ->
                    if (method == "server.version") versionCalls.incrementAndGet()
                    if (method == "blockchain.scripthash.get_history") historyCalls.incrementAndGet()
                    if (!available) return@FakeTransport null
                    when (method) {
                        "server.version" -> """{"jsonrpc":"2.0","id":$id,"result":["fake","1.4"]}"""
                        "blockchain.scripthash.get_history" -> """{"jsonrpc":"2.0","id":$id,"result":[]}"""
                        "blockchain.headers.subscribe" -> """{"jsonrpc":"2.0","id":$id,"result":{"height":200}}"""
                        else -> null
                    }
                }
            val core = coreFor(transport)
            // A fast definitive miss stops new hedges, then the miss is cached.
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, core.resolve("example.bit"))
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, core.resolve("example.bit"))
            assertEquals(1, historyCalls.get())

            // Unavailable: NOT cached — each resolve retries the network
            // (both servers, at the version handshake).
            available = false
            versionCalls.set(0)
            assertEquals(NamecoinResolver.NamecoinResult.Unavailable, core.resolve("unavailable.bit"))
            assertEquals(NamecoinResolver.NamecoinResult.Unavailable, core.resolve("unavailable.bit"))
            assertEquals(4, versionCalls.get())
        }

    @Test
    fun concurrentResolvesShareOneInFlightLookup() =
        runBlocking {
            val pubkey = "4".repeat(64)
            val historyCalls = AtomicInteger(0)
            val happy = happyPath("d/example", """{"nostr":{"pubkey":"$pubkey"}}""")
            val transport =
                FakeTransport(latencyMillis = 100) { server, id, method, params ->
                    if (method == "blockchain.scripthash.get_history") historyCalls.incrementAndGet()
                    happy(server, id, method, params)
                }
            val core = coreFor(transport)
            // Concurrent resolves sharing ONE normalized key (trim +
            // lowercase, matching upstream nc_normalize) collapse into a
            // single in-flight network lookup. Distinct spellings that
            // normalize differently (d/example vs example.bit) are separate
            // cache keys upstream as well, so they are not collapsed here.
            val results =
                listOf(
                    "example.bit",
                    "Example.BIT",
                    " example.bit ",
                    "EXAMPLE.bit",
                    "example.bit",
                    " Example.bit",
                    "example.BIT",
                    "EXAMPLE.BIT",
                ).map { async { core.resolve(it) } }
                    .awaitAll()
            assertTrue(results.all { it is NamecoinResolver.NamecoinResult.Resolved })
            assertEquals(1, historyCalls.get())
        }

    @Test
    fun nonBitInputIsRejectedWithoutNetwork() =
        runBlocking {
            val transport = FakeTransport { _, _, _, _ -> error("must not be called") }
            val result = coreFor(transport).resolve("alice@example.com")
            assertEquals(NamecoinResolver.NamecoinResult.NotFound, result)
        }

    // ── request shape ──────────────────────────────────────────────────

    @Test
    fun issuesUpstreamCallSequence() =
        runBlocking {
            val transport =
                FakeTransport(
                    handler = happyPath("d/example", """{"nostr":{"pubkey":"${"5".repeat(64)}"}}"""),
                )
            coreFor(transport).resolve("example.bit")
            val methods =
                transport.requests
                    .map { JSONObject(it).getString("method") }
                    .toList()
            assertEquals(
                listOf(
                    "server.version",
                    "blockchain.scripthash.get_history",
                    "blockchain.transaction.get",
                    "blockchain.headers.subscribe",
                ),
                methods,
            )
            // The history call carries the LOCALLY derived scripthash.
            val historyReq = transport.requests.map(::JSONObject).first { it.getString("method") == "blockchain.scripthash.get_history" }
            assertEquals(scripthashOf("d/example"), historyReq.getJSONArray("params").getString(0))
        }
}
