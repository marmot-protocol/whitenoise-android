package dev.ipf.whitenoise.android.core.namecoin

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Namecoin `.bit` NIP-05 record layer: ifa-0001 `import` expansion and the
 * fail-closed `nostr` field extraction, ported from the parsing half of
 * `app/namecoin.odin` (whitenoise-linux commit 79447095f2).
 *
 * Fail-closed semantics carried over verbatim:
 *  - a missing local part NEVER silently substitutes the domain owner's key
 *    (`alice@example.bit` resolves to alice's entry or to nothing);
 *  - a root lookup against a record without an `_` entry returns NotFound
 *    instead of picking an arbitrary `names` map entry;
 *  - identity extraction requires a top-level `pubkey`; there is no fallback
 *    to a `names` sub-object's owner key;
 *  - the `_` fallback for `d/` root lookups IS kept (domain-owner convention).
 *
 * Import expansion is a non-destructive clone-and-merge: the parsed source
 * tree is read-only, the returned tree is a fresh graph (via [cloneJsonValue]),
 * and import cycles / runaway chains are bounded by a visited set and a depth
 * budget (ifa-0001 default depth of 4, the spec minimum).
 */
internal object NamecoinRecord {
    const val IMPORT_MAX_DEPTH = 4

    private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")

    /** A parsed `.bit` / `d/` / `id/` lookup request. */
    internal data class Parsed(
        val namecoinName: String, // e.g. "d/example"
        val localPart: String, // "_" for the root
        val isDomain: Boolean, // false for `id/` identity names
    )

    /** Extraction outcome for a merged record tree against a parsed request. */
    internal sealed interface Extraction {
        data class Resolved(
            val pubkeyHex: String, // 64 lowercase hex
            val relays: List<String>,
            val localPart: String,
        ) : Extraction

        data object NotFound : Extraction
    }

    // ── ifa-0001 import chain ───────────────────────────────────────────

    /** One parsed import operation: a name plus an optional DNS-dotted selector. */
    private data class ImportOp(
        val name: String,
        val selector: String,
    )

    /**
     * If [root] declares any `import`, return a fresh object graph with those
     * imports expanded and merged in importer-wins order; otherwise return a
     * fresh clone of [root] minus nothing. Either way the result is a new
     * tree that shares no mutable structure with [root], and [root] itself is
     * never modified.
     *
     * [fetch] retrieves an imported name's parsed value tree, or null when the
     * name is unavailable / not found — sub-import failures collapse to an
     * empty imported object (the import contributes nothing), matching the
     * gitworkshop semantics the Odin port follows. Suspending: fetching an
     * import is a network round trip through the resolver's server fallback.
     */
    suspend fun resolveValueWithImports(
        root: JSONObject,
        fetch: suspend (String) -> JSONObject?,
        maxDepth: Int = IMPORT_MAX_DEPTH,
    ): JSONObject {
        val visited = mutableSetOf<String>()
        return expandImports(root, fetch, maxDepth, visited)
    }

    /**
     * Recursive import expansion. Non-destructive: [obj] is READ ONLY. The
     * returned JSONObject is a fresh allocation whose keys and values are
     * clones of either [obj]'s non-import entries or of the sub-object
     * selected out of an imported record.
     */
    private suspend fun expandImports(
        obj: JSONObject,
        fetch: suspend (String) -> JSONObject?,
        budget: Int,
        visited: MutableSet<String>,
    ): JSONObject {
        // Start with a clone of everything in `obj` except `import`.
        val base = cloneWithoutImport(obj)
        val importValue = obj.opt("import") ?: return base
        if (budget <= 0) return base
        val ops = parseImportItem(importValue)
        if (ops.isEmpty()) return base

        val accumulator = JSONObject()
        for (op in ops) {
            val key = "${op.name}|${op.selector}"
            if (!visited.add(key)) continue
            try {
                val importedRoot = fetch(op.name) ?: continue
                val selected = applySelector(importedRoot, op.selector) ?: continue
                // Recurse to expand nested imports; `expanded` is a fresh tree
                // independent of `importedRoot`, safe to consume here.
                val expanded = expandImports(selected, fetch, budget - 1, visited)
                mergeImporterWins(accumulator, expanded)
            } finally {
                visited.remove(key)
            }
        }
        mergeImporterWins(base, accumulator)
        return base
    }

    /** Clone an object minus its `import` key. Non-destructive; the source stays valid. */
    private fun cloneWithoutImport(obj: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in obj.keys()) {
            if (key == "import") continue
            out.put(key, cloneJsonValue(obj.get(key)))
        }
        return out
    }

    /** Deep-clone any org.json value tree (objects, arrays, primitives). */
    private fun cloneJsonValue(value: Any?): Any? =
        when (value) {
            is JSONObject -> {
                val out = JSONObject()
                for (key in value.keys()) out.put(key, cloneJsonValue(value.get(key)))
                out
            }
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until value.length()) out.put(cloneJsonValue(value.get(i)))
                out
            }
            else -> value
        }

    /**
     * Parse an `import` directive: the shorthand string form (`"d/foo"`), the
     * canonical nested form (`[[name, sel], ...]`), or the flat shorthand
     * (`[name, sel]`). Selectors ending in "." are rejected per ifa-0001.
     */
    private fun parseImportItem(item: Any): List<ImportOp> {
        if (item is String) {
            val trimmed = item.trim()
            return if (trimmed.isNotEmpty()) listOf(ImportOp(trimmed, "")) else emptyList()
        }
        if (item !is JSONArray || item.length() == 0) return emptyList()
        // Distinguish canonical [[name, sel], ...] from shorthand [name, sel].
        if (item.opt(0) is JSONArray) {
            val ops = ArrayList<ImportOp>(item.length())
            for (i in 0 until item.length()) {
                val inner = item.opt(i) as? JSONArray ?: continue
                opFromArray(inner)?.let(ops::add)
            }
            return ops
        }
        return opFromArray(item)?.let(::listOf).orEmpty()
    }

    private fun opFromArray(arr: JSONArray): ImportOp? {
        if (arr.length() == 0) return null
        val name = (arr.opt(0) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var selector = ""
        if (arr.length() >= 2) {
            selector = (arr.opt(1) as? String)?.trim() ?: return null
        }
        if (selector.endsWith(".")) return null
        return ImportOp(name, selector)
    }

    /**
     * Walk a DNS-dotted selector into `root.map`, priority exact -> `*`
     * wildcard -> `""` default. Returns a REFERENCE into [root] (callers treat
     * it as read-only); null for a selector that walks off the tree.
     */
    internal fun applySelector(
        root: JSONObject,
        selector: String,
    ): JSONObject? {
        if (selector.isEmpty()) return root
        val labels = selector.split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty()) return root
        var current = root
        for (i in labels.indices.reversed()) {
            val label = labels[i]
            val map = current.optJSONObject("map") ?: return null
            current =
                map.optJSONObject(label)
                    ?: map.optJSONObject("*")
                    ?: map.optJSONObject("")
                    ?: return null
        }
        return current
    }

    /**
     * Merge [imported] underneath [importer] with importer-wins semantics:
     * keys the importer already holds are kept, everything else is copied in.
     * Top-level only, matching `nc_merge_importer_wins`. The values are moved
     * by reference out of freshly-cloned trees only, so no source object is
     * ever shared into the result.
     */
    private fun mergeImporterWins(
        importer: JSONObject,
        imported: JSONObject,
    ) {
        for (key in imported.keys()) {
            if (importer.has(key)) continue // Importer wins; drop the imported entry.
            importer.put(key, imported.get(key))
        }
    }

    // ── nostr field extraction ──────────────────────────────────────────

    /**
     * Pull pubkey + relays out of the merged value tree for [parsed]. Fails
     * closed on every ambiguous shape — see the file header for the rules.
     */
    fun extractNostr(
        value: JSONObject,
        parsed: Parsed,
    ): Extraction {
        val field = value.opt("nostr") ?: return Extraction.NotFound

        // Simple form: "nostr": "hex-pubkey". Only meaningful for a root
        // lookup — it cannot name a specific local part.
        if (field is String) {
            if (parsed.isDomain && parsed.localPart != "_") return Extraction.NotFound
            if (!HEX_PUBKEY.matches(field)) return Extraction.NotFound
            return Extraction.Resolved(
                pubkeyHex = field.lowercase(Locale.ROOT),
                relays = emptyList(),
                localPart = "_",
            )
        }

        val nostrObj = field as? JSONObject ?: return Extraction.NotFound
        return if (parsed.isDomain) {
            extractFromDomain(nostrObj, parsed)
        } else {
            extractFromIdentity(nostrObj)
        }
    }

    /**
     * Domain-shape extraction. FAIL CLOSED: `alice@example.bit` must resolve
     * to alice's entry, or to nothing. Never fall back to `_` for a named
     * local part (that would silently substitute the domain owner's identity),
     * and never scan for a "first valid entry" when the record has no `_`
     * (that would substitute an arbitrary other user's identity for the root).
     */
    internal fun extractFromDomain(
        nostr: JSONObject,
        parsed: Parsed,
    ): Extraction {
        val names = nostr.optJSONObject("names")
        if (names == null) {
            // Some d/ records use the identity-style flat shape (bare
            // "pubkey":... at the root of the nostr object). Accept it only
            // when the caller asked for the root (`_`) — for any other local
            // part the identity-style shape does not name them.
            if (parsed.localPart != "_") return Extraction.NotFound
            return extractFromIdentity(nostr)
        }
        val entry = names.opt(parsed.localPart) as? String ?: return Extraction.NotFound
        if (!HEX_PUBKEY.matches(entry)) return Extraction.NotFound
        val pickedKey = entry.lowercase(Locale.ROOT)
        return Extraction.Resolved(
            pubkeyHex = pickedKey,
            relays = extractRelays(nostr, pickedKey),
            localPart = parsed.localPart,
        )
    }

    /**
     * Identity-shape extraction: accept the top-level `pubkey` binding only.
     * Reject any `names` sub-object; those belong to domain (`d/`) records,
     * and treating them as identity-owner keys silently substitutes somebody
     * else's identity for the requested name.
     */
    internal fun extractFromIdentity(nostr: JSONObject): Extraction {
        val pubkey = nostr.optString("pubkey", "")
        if (!HEX_PUBKEY.matches(pubkey)) return Extraction.NotFound
        val relays =
            nostr
                .optJSONArray("relays")
                ?.let { arr ->
                    buildList {
                        for (i in 0 until arr.length()) {
                            (arr.opt(i) as? String)?.let(::add)
                        }
                    }
                }.orEmpty()
        return Extraction.Resolved(
            pubkeyHex = pubkey.lowercase(Locale.ROOT),
            relays = relays,
            localPart = "_",
        )
    }

    private fun extractRelays(
        nostr: JSONObject,
        pubkey: String,
    ): List<String> {
        val raw = nostr.opt("relays") ?: return emptyList()
        // Domain shape: map keyed by pubkey.
        if (raw is JSONObject) {
            val arr =
                raw.optJSONArray(pubkey.lowercase(Locale.ROOT))
                    ?: raw.optJSONArray(pubkey)
                    ?: return emptyList()
            return stringList(arr)
        }
        // Identity shape: flat array.
        if (raw is JSONArray) return stringList(raw)
        return emptyList()
    }

    private fun stringList(arr: JSONArray): List<String> =
        buildList {
            for (i in 0 until arr.length()) {
                (arr.opt(i) as? String)?.let(::add)
            }
        }
}
