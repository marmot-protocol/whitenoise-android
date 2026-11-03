package dev.ipf.whitenoise.android.core.namecoin

import dev.ipf.whitenoise.android.core.namecoin.NamecoinTransport.NamecoinServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * Namecoin `.bit` NIP-05 resolution. `alice@example.bit`, `example.bit`,
 * `d/example`, `id/alice` -> Nostr pubkey hex (+ optional relay hints).
 *
 * Same closed loop the NIP-05 HTTP verifier serves for DNS names, but over
 * Namecoin's censorship-resistant name index via ElectrumX TLS. Port of the resolver core of `app/namecoin.odin` from whitenoise-linux
 * commit 79447095f2.
 *
 * ── TRUST MODEL ───────────────────────────────────────────────────────
 *
 * This resolver TRUSTS the pinned ElectrumX operators it talks to. It asks the
 * server for the latest transaction outputs bound to the name's scripthash and
 * reads the `nostr` field out of the value payload. It does NOT verify:
 *
 *  - that the returned transaction is included in the best Namecoin chain (no
 *    SPV proof, no block-header chain check),
 *  - that the returned transaction actually spends into the claimed
 *    scripthash on-chain (only that the server labelled it so in the history
 *    response),
 *  - that any of the servers it talked to agree with each other (there is no
 *    cross-server quorum: the first Resolved wins).
 *
 * A dishonest, compromised, or MITM'd ElectrumX server can therefore return a
 * fabricated name -> pubkey binding, and the invite/new-chat action will use
 * whichever pubkey that server claims. System-CA or per-operator TLS pinning
 * (see [NamecoinTransport]) rules out network-path MITM, but does not rule out
 * the operator themselves lying to us.
 *
 * This matches what the parallel Kotlin, Swift, and TypeScript resolvers in
 * the Nostr ecosystem do today: they treat the ElectrumX server as a trusted
 * resolver, the same way NIP-05 treats an HTTPS server. The mitigation
 * planned for a follow-up is either bundled SPV verification (block-header
 * chain + Merkle proof) or dispatch to a local `namecoind`; both are out of
 * scope for this PR.
 *
 * Users who want a stronger trust model should either run their own
 * ElectrumX / Namecoin full node (self-hosted trust) or wait for the
 * SPV/`namecoind` follow-up.
 *
 * ── ASYNC MODEL ───────────────────────────────────────────────────────
 *
 * Resolution is a plain suspending call (coroutine-idiomatic equivalent of
 * the Odin worker pool + frame-loop drain): callers launch it from their own
 * scope, cancellation abandons the lookup, and concurrent resolves of the
 * same identifier share one in-flight lookup via [inflight]. Positive and
 * definitive-negative outcomes are cached in memory for the session;
 * Unavailable is intentionally not cached (it is retryable).
 */
object NamecoinResolver {
    /** Resolution outcome, distinguishing not-found from unavailable so the UI can tell them apart. */
    sealed interface NamecoinResult {
        data class Resolved(
            val pubkeyHex: String, // 64 lowercase hex
            val relays: List<String>,
            val namecoinName: String,
            val localPart: String,
        ) : NamecoinResult

        /** The name definitively does not resolve (no record, no matching entry, expired). */
        data object NotFound : NamecoinResult

        /** Every pinned server failed at the transport layer; retryable. */
        data object Unavailable : NamecoinResult
    }

    // ── ElectrumX endpoints ─────────────────────────────────────────────
    //
    // Public Namecoin ElectrumX endpoints use either ordinary system-CA TLS
    // or a per-operator leaf public key pin. Pins are SHA-256 digests of
    // the SPKI, encoded as base64 like libcurl's `sha256//...` value. A pinned
    // handshake succeeds only for the matching pubkey; unpinned endpoints use
    // the normal system CA and hostname checks.
    //
    // Rotation runbook: when a pinned server rotates its cert, regenerate the
    // pin with
    //
    //   echo | openssl s_client -servername <host> -connect <host>:<port> 2>/dev/null \
    //     | openssl x509 -pubkey -noout \
    //     | openssl pkey -pubin -outform der \
    //     | openssl dgst -sha256 -binary | openssl enc -base64
    //
    // and update the entry. Removing a pin without replacing it opens the
    // server to trivial MITM.
    internal val DEFAULT_SERVERS =
        listOf(
            NamecoinServer(host = "electrumx.testls.space", port = 50002),
            NamecoinServer(
                host = "nmc2.bitcoins.sk",
                port = 57002,
                pinBase64 = "tkCCQ6AZPqU4Te1poKHeS5l/CRWYHz5n7J4LH22/LxQ=",
            ),
            NamecoinServer(
                host = "46.229.238.187",
                port = 57002,
                pinBase64 = "tkCCQ6AZPqU4Te1poKHeS5l/CRWYHz5n7J4LH22/LxQ=",
                tlsHostname = "nmc2.bitcoins.sk",
            ),
            NamecoinServer(
                host = "relay.testls.bit",
                port = 50012,
                pinBase64 = "Y9Zu+VV8eoqOBanmpk5QzJtnHwqzxb0ynJGiMRKVJBw=",
            ),
            NamecoinServer(
                host = "23.158.233.10",
                port = 50012,
                pinBase64 = "Y9Zu+VV8eoqOBanmpk5QzJtnHwqzxb0ynJGiMRKVJBw=",
                tlsHostname = "relay.testls.bit",
            ),
            NamecoinServer(host = "electrumx2.testls.space", port = 50002),
        )

    internal const val REQUEST_TIMEOUT_MILLIS = 15_000L
    internal const val RESOLVER_TIMEOUT_MILLIS = 20_000L

    private val core by lazy {
        NamecoinResolverCore(
            servers = DEFAULT_SERVERS,
            transport = NamecoinTransport(),
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
        )
    }

    // ── identifier gate ─────────────────────────────────────────────────

    /**
     * True when [identifier] should be routed to Namecoin resolution rather
     * than a DNS-based NIP-05 verifier. Cheap enough to gate a hot path.
     * Matches the front-door check the gitworkshop TS and Amethyst
     * implementations use (and the upstream `nc_is_bit`).
     */
    fun isBit(identifier: String): Boolean {
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) return false
        var lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("nostr:")) lower = lower.substring(6)
        if (lower.startsWith("d/")) return lower.length > 2
        if (lower.startsWith("id/")) return lower.length > 3
        return lower.endsWith(".bit") && lower.length > 4
    }

    /** Parse [raw] into a structured request; null for non-Namecoin identifiers. */
    internal fun parse(raw: String): NamecoinRecord.Parsed? {
        var input = raw.trim()
        if (input.isEmpty()) return null
        // Tolerate a leading NIP-21 `nostr:`.
        if (input.length >= 6 && input.substring(0, 6).equals("nostr:", ignoreCase = true)) {
            input = input.substring(6)
        }
        val lower = input.lowercase(Locale.ROOT)

        if (lower.startsWith("d/")) {
            if (lower.length == 2) return null
            return NamecoinRecord.Parsed(namecoinName = lower, localPart = "_", isDomain = true)
        }
        if (lower.startsWith("id/")) {
            if (lower.length == 3) return null
            return NamecoinRecord.Parsed(namecoinName = lower, localPart = "_", isDomain = false)
        }

        if (input.contains('@') && lower.endsWith(".bit")) {
            val atIdx = input.indexOf('@')
            val localRaw = input.substring(0, atIdx)
            val local = if (localRaw.isNotEmpty()) localRaw.lowercase(Locale.ROOT) else "_"
            val domainRaw = input.substring(atIdx + 1).lowercase(Locale.ROOT)
            if (!domainRaw.endsWith(".bit")) return null
            val domain = domainRaw.dropLast(4)
            if (domain.isEmpty()) return null
            return NamecoinRecord.Parsed(namecoinName = "d/$domain", localPart = local, isDomain = true)
        }

        if (lower.endsWith(".bit")) {
            val domain = lower.dropLast(4)
            if (domain.isEmpty()) return null
            return NamecoinRecord.Parsed(namecoinName = "d/$domain", localPart = "_", isDomain = true)
        }
        return null
    }

    // ── public resolution entry ─────────────────────────────────────────

    /**
     * Resolve [identifier] to a [NamecoinResult], suspending across all
     * network work. Callers are expected to gate with [isBit] first; a
     * non-Namecoin identifier returns [NamecoinResult.NotFound].
     */
    suspend fun resolve(identifier: String): NamecoinResult = core.resolve(identifier)
}

/** A name is treated as expired this many blocks after its last update. */
private const val NAME_EXPIRE_DEPTH = 36_000L

/**
 * The stateful resolver behind [NamecoinResolver]: session caches, in-flight
 * dedup, server fallback. Extracted as a class so tests can inject a mock
 * transport and their own server list.
 */
internal class NamecoinResolverCore(
    private val servers: List<NamecoinServer>,
    private val transport: NamecoinTransport,
    private val requestTimeoutMillis: Long,
    private val resolverTimeoutMillis: Long = NamecoinResolver.RESOLVER_TIMEOUT_MILLIS,
    private val hedgeDelayMillis: Long = 1_500L,
    private val negativeGraceMillis: Long = 1_500L,
) {
    private val cacheMutex = Mutex()

    /** Positive hit: normalized identifier -> resolved snapshot. */
    private val posCache = mutableMapOf<String, NamecoinResolver.NamecoinResult.Resolved>()

    /** Negative hit: presence means "definitively not found". */
    private val negCache = mutableSetOf<String>()

    /** In-flight lookups shared between concurrent callers of the same identifier. */
    private val inflightMutex = Mutex()
    private val inflight = mutableMapOf<String, CompletableDeferred<NamecoinResolver.NamecoinResult>>()

    private fun normalize(identifier: String): String = identifier.trim().lowercase(Locale.ROOT)

    suspend fun resolve(identifier: String): NamecoinResolver.NamecoinResult {
        if (!NamecoinResolver.isBit(identifier)) return NamecoinResolver.NamecoinResult.NotFound
        val key = normalize(identifier)

        cacheMutex.withLock {
            posCache[key]?.let { return it }
            if (key in negCache) return NamecoinResolver.NamecoinResult.NotFound
        }

        val (owner, deferred) =
            inflightMutex.withLock {
                val existing = inflight[key]
                if (existing != null) {
                    false to existing
                } else {
                    val created = CompletableDeferred<NamecoinResolver.NamecoinResult>()
                    inflight[key] = created
                    true to created
                }
            }
        if (!owner) return deferred.await()

        val result =
            try {
                withTimeoutOrNull(resolverTimeoutMillis) { resolveUncached(identifier) }
                    ?: NamecoinResolver.NamecoinResult.Unavailable
            } catch (cancelled: CancellationException) {
                // Release any waiters before propagating; their own scopes
                // decide whether to re-issue the lookup.
                deferred.complete(NamecoinResolver.NamecoinResult.Unavailable)
                inflightMutex.withLock { inflight.remove(key) }
                throw cancelled
            } catch (error: Throwable) {
                NamecoinResolver.NamecoinResult.Unavailable
            }

        cacheMutex.withLock {
            when (result) {
                is NamecoinResolver.NamecoinResult.Resolved -> {
                    negCache.remove(key)
                    posCache[key] = result
                }
                NamecoinResolver.NamecoinResult.NotFound -> negCache.add(key)
                NamecoinResolver.NamecoinResult.Unavailable -> Unit // retryable; never cached
            }
        }
        deferred.complete(result)
        inflightMutex.withLock { inflight.remove(key) }
        return result
    }

    // ── lookup pipeline ─────────────────────────────────────────────────

    private suspend fun resolveUncached(identifier: String): NamecoinResolver.NamecoinResult {
        val parsed = NamecoinResolver.parse(identifier) ?: return NamecoinResolver.NamecoinResult.NotFound

        // Root lookup with server fallback.
        val (status, valueJson) = fetchNameValueWithFallback(parsed.namecoinName)
        if (status != LookupStatus.Resolved || valueJson == null) {
            return when (status) {
                LookupStatus.NotFound -> NamecoinResolver.NamecoinResult.NotFound
                else -> NamecoinResolver.NamecoinResult.Unavailable
            }
        }

        val root = runCatching { JSONObject(valueJson) }.getOrNull() ?: return NamecoinResolver.NamecoinResult.NotFound

        // Walk the ifa-0001 `import` chain. Sub-import unavailability collapses
        // to "empty imported object" — only the root's availability drives the
        // top-level tri-state signal.
        val merged =
            NamecoinRecord.resolveValueWithImports(root, fetch = { importedName ->
                fetchImport(importedName)
            })

        return when (val extraction = NamecoinRecord.extractNostr(merged, parsed)) {
            is NamecoinRecord.Extraction.Resolved ->
                NamecoinResolver.NamecoinResult.Resolved(
                    pubkeyHex = extraction.pubkeyHex,
                    relays = extraction.relays,
                    namecoinName = parsed.namecoinName,
                    localPart = extraction.localPart,
                )
            NamecoinRecord.Extraction.NotFound -> NamecoinResolver.NamecoinResult.NotFound
        }
    }

    private enum class LookupStatus {
        Resolved,
        NotFound,
        Unavailable,
    }

    /**
     * Quartz-style bounded hedging: start in list order 1.5 seconds apart, or
     * immediately after a failure. A positive always wins. A definitive miss
     * prevents new attempts but leaves existing attempts a 1.5-second grace
     * period, so a slower positive can beat a faster negative.
     */
    private suspend fun fetchNameValueWithFallback(name: String): Pair<LookupStatus, String?> =
        coroutineScope {
            if (servers.isEmpty()) return@coroutineScope LookupStatus.Unavailable to null

            val events = Channel<LookupEvent>(Channel.UNLIMITED)
            var nextServer = 0
            var active = 0
            var scheduled: Job? = null
            var definitiveMiss = false
            var negativeGraceDeadline = 0L

            fun scheduleNext() {
                if (nextServer >= servers.size || definitiveMiss) return
                scheduled =
                    launch {
                        delay(hedgeDelayMillis)
                        events.send(LookupEvent.StartNext)
                    }
            }

            fun startNext() {
                val server = servers[nextServer++]
                active++
                launch {
                    events.send(LookupEvent.Completed(fetchNameValue(name, server)))
                }
            }

            startNext()
            scheduleNext()

            while (true) {
                val event =
                    if (definitiveMiss) {
                        val remaining = (negativeGraceDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                        withTimeoutOrNull(remaining) { events.receive() }
                            ?: return@coroutineScope LookupStatus.NotFound to null
                    } else {
                        events.receive()
                    }

                when (event) {
                    LookupEvent.StartNext -> {
                        if (!definitiveMiss && nextServer < servers.size) {
                            startNext()
                            scheduleNext()
                        }
                    }
                    is LookupEvent.Completed -> {
                        active--
                        when (event.outcome) {
                            is NameValueOutcome.Value ->
                                return@coroutineScope LookupStatus.Resolved to event.outcome.valueJson
                            NameValueOutcome.NotFound -> {
                                if (!definitiveMiss) {
                                    negativeGraceDeadline = System.currentTimeMillis() + negativeGraceMillis
                                }
                                definitiveMiss = true
                                scheduled?.cancel()
                                if (active == 0) return@coroutineScope LookupStatus.NotFound to null
                            }
                            NameValueOutcome.Unavailable -> {
                                if (active == 0 && (definitiveMiss || nextServer == servers.size)) {
                                    return@coroutineScope if (definitiveMiss) {
                                        LookupStatus.NotFound to null
                                    } else {
                                        LookupStatus.Unavailable to null
                                    }
                                }
                                if (!definitiveMiss && nextServer < servers.size) {
                                    scheduled?.cancel()
                                    startNext()
                                    scheduleNext()
                                }
                            }
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            LookupStatus.Unavailable to null
        }

    private sealed interface LookupEvent {
        data object StartNext : LookupEvent

        data class Completed(
            val outcome: NameValueOutcome,
        ) : LookupEvent
    }

    private sealed interface NameValueOutcome {
        data class Value(
            val valueJson: String,
        ) : NameValueOutcome

        data object NotFound : NameValueOutcome

        data object Unavailable : NameValueOutcome
    }

    /**
     * One server's view of [name]: server.version handshake, locally derived
     * scripthash -> get_history -> transaction.get, plus a best-effort expiry
     * check via headers.subscribe.
     */
    private suspend fun fetchNameValue(
        name: String,
        server: NamecoinServer,
    ): NameValueOutcome {
        // server.version handshake — some ElectrumX operators reject calls
        // before it. On failure the server is treated as unusable (same as the
        // upstream shim: the next server in the fallback list gets a turn).
        val versionReq =
            """{"jsonrpc":"2.0","id":1,"method":"server.version","params":["whitenoise-android/namecoin","1.4"]}"""
        transport.call(server, versionReq, 1, requestTimeoutMillis) ?: return NameValueOutcome.Unavailable

        // Derive the ElectrumX scripthash key for this name LOCALLY.
        val script = NamecoinScript.buildNameIndexScript(name.toByteArray(Charsets.UTF_8))
        val scripthash = NamecoinScript.electrumScriptHash(script)

        val historyReq =
            """{"jsonrpc":"2.0","id":2,"method":"blockchain.scripthash.get_history","params":["$scripthash"]}"""
        val historyBody =
            transport.call(server, historyReq, 2, requestTimeoutMillis) ?: return NameValueOutcome.Unavailable
        val lastEntry =
            lastHistoryEntry(historyBody)
                // Empty history is a definitive Namecoin miss (name never
                // registered / has no ops).
                ?: return NameValueOutcome.NotFound

        val txReq =
            """{"jsonrpc":"2.0","id":3,"method":"blockchain.transaction.get","params":["${lastEntry.txHash}",true]}"""
        val txBody =
            transport.call(server, txReq, 3, requestTimeoutMillis) ?: return NameValueOutcome.Unavailable

        // Best-effort expiry check via headers.subscribe.
        val headReq = """{"jsonrpc":"2.0","id":4,"method":"blockchain.headers.subscribe","params":[]}"""
        val currentHeight =
            transport.call(server, headReq, 4, requestTimeoutMillis)?.let(::headerHeight) ?: 0L
        if (currentHeight > 0 &&
            lastEntry.height > 0 &&
            currentHeight - lastEntry.height >= NAME_EXPIRE_DEPTH
        ) {
            return NameValueOutcome.NotFound
        }

        val value =
            extractValueFromTx(txBody, name)
                // The tx we got back does not carry this name's value. Treat as
                // definitive miss — the history entry pointed at something that is
                // not our name payload.
                ?: return NameValueOutcome.NotFound
        return NameValueOutcome.Value(value)
    }

    /** Fetch an imported name's parsed value tree; any failure collapses to null. */
    private suspend fun fetchImport(name: String): JSONObject? {
        val (status, raw) = fetchNameValueWithFallback(name)
        if (status != LookupStatus.Resolved || raw == null) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    // ── JSON extraction helpers ─────────────────────────────────────────

    private data class HistoryEntry(
        val txHash: String,
        val height: Long,
    )

    /** Pull the last entry of a get_history reply: array of {tx_hash, height}. */
    private fun lastHistoryEntry(body: String): HistoryEntry? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val arr = root.optJSONArray("result") ?: return null
        if (arr.length() == 0) return null
        val last = arr.optJSONObject(arr.length() - 1) ?: return null
        val txHash = last.optString("tx_hash", "").takeIf { it.isNotEmpty() } ?: return null
        val height = last.optDouble("height", Double.NaN)
        if (height.isNaN()) return null
        return HistoryEntry(txHash, height.toLong())
    }

    private fun headerHeight(body: String): Long {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return 0L
        val result = root.optJSONObject("result") ?: return 0L
        val height = result.optDouble("height", Double.NaN)
        return if (height.isNaN()) 0L else height.toLong()
    }

    /**
     * Extract the raw name value string from a verbose transaction reply by
     * walking its `vout[]` and matching the OP_NAME_UPDATE / FIRSTUPDATE vout
     * carrying [name].
     */
    private fun extractValueFromTx(
        body: String,
        name: String,
    ): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tx = root.optJSONObject("result") ?: return null
        val vout = tx.optJSONArray("vout") ?: return null
        val hexes = ArrayList<String>(vout.length())
        for (i in 0 until vout.length()) {
            val spk = vout.optJSONObject(i)?.optJSONObject("scriptPubKey") ?: continue
            val hex = spk.optString("hex", "")
            if (hex.isNotEmpty()) hexes.add(hex)
        }
        return NamecoinScript.extractNameValue(hexes, name)
    }
}
