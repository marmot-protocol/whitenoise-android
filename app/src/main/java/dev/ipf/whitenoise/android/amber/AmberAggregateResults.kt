package dev.ipf.whitenoise.android.amber

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** One ID-addressed result from Amber's grouped foreground approval envelope. */
internal data class AmberAggregateResult(
    val id: String,
    val result: String?,
    val event: String?,
    val signature: String?,
    val packageName: String?,
    val rejected: Boolean,
) {
    fun toIntent(handledSignerPackage: String): Intent =
        Intent().apply {
            putExtra(Nip55.EXTRA_ID, id)
            result?.let { putExtra(Nip55.EXTRA_RESULT, it) }
            event?.let { putExtra(Nip55.EXTRA_EVENT, it) }
            signature?.let { putExtra(Nip55.EXTRA_SIGNATURE, it) }
            packageName?.let { putExtra(Nip55.EXTRA_PACKAGE, it) }
            if (rejected) putExtra(Nip55.EXTRA_REJECTED, true)
            putExtra(Nip55.EXTRA_AGGREGATE_RESULT, true)
            putExtra(AmberSignerRelay.EXTRA_HANDLED_SIGNER_PACKAGE, handledSignerPackage)
        }
}

internal sealed interface AmberAggregateParseOutcome {
    data class Parsed(
        /** Unique, structurally valid entries. Duplicate IDs are excluded. */
        val entries: List<AmberAggregateResult>,
        val duplicateIds: Set<String>,
        val malformedEntryCount: Int,
    ) : AmberAggregateParseOutcome

    /** The envelope itself could not be trusted or was outside the wire bounds. */
    data object Malformed : AmberAggregateParseOutcome
}

/**
 * Strict, bounded parser for Amber's `results` JSON array.
 *
 * Unknown fields remain forward-compatible. Entries with missing or malformed
 * IDs are ignored, duplicate IDs are removed from the dispatchable set, and an
 * invalid envelope dispatches nothing. The waiting request therefore either
 * receives its own unique entry or follows the existing timeout/rejection path;
 * malformed signer data can never complete another request.
 */
@Suppress("ReturnCount") // Envelope and entry guards deliberately fail closed at the untrusted signer boundary.
internal fun parseAmberAggregateResults(json: String): AmberAggregateParseOutcome {
    if (json.toByteArray(Charsets.UTF_8).size > Nip55.MAX_AGGREGATE_RESULTS_UTF8_BYTES) {
        return AmberAggregateParseOutcome.Malformed
    }
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return AmberAggregateParseOutcome.Malformed
    if (array.length() > Nip55.MAX_GROUPED_APPROVALS) return AmberAggregateParseOutcome.Malformed

    val unique = linkedMapOf<String, AmberAggregateResult>()
    val seenIds = linkedSetOf<String>()
    val duplicates = linkedSetOf<String>()
    var malformedEntries = 0
    repeat(array.length()) { index ->
        val entry = array.optJSONObject(index)
        if (entry == null) {
            malformedEntries += 1
            return@repeat
        }
        val id =
            entry
                .strictString(Nip55.EXTRA_ID)
                ?.takeIf { it.isNotBlank() && it.length <= Nip55.MAX_REQUEST_ID_CHARS }
        val result = entry.strictNullableString(Nip55.EXTRA_RESULT)
        val event = entry.strictNullableString(Nip55.EXTRA_EVENT)
        val signature = entry.strictNullableString(Nip55.EXTRA_SIGNATURE)
        val packageName = entry.strictNullableString(Nip55.EXTRA_PACKAGE)
        val rejected = entry.strictNullableBoolean(Nip55.EXTRA_REJECTED)
        if (id == null) {
            malformedEntries += 1
            return@repeat
        }
        if (!seenIds.add(id)) {
            unique.remove(id)
            duplicates.add(id)
            return@repeat
        }
        val hasMalformedValue =
            listOf(result, event, signature, packageName).any { it === InvalidString } ||
                rejected === InvalidBoolean
        if (hasMalformedValue) {
            malformedEntries += 1
            return@repeat
        }

        unique[id] =
            AmberAggregateResult(
                id = id,
                result = result as String?,
                event = event as String?,
                signature = signature as String?,
                packageName = packageName as String?,
                rejected = rejected as Boolean? ?: false,
            )
    }
    return AmberAggregateParseOutcome.Parsed(
        entries = unique.values.toList(),
        duplicateIds = duplicates,
        malformedEntryCount = malformedEntries,
    )
}

private object InvalidString

private object InvalidBoolean

private fun JSONObject.strictString(name: String): String? = opt(name) as? String

private fun JSONObject.strictNullableString(name: String): Any? {
    if (!has(name) || isNull(name)) return null
    return opt(name).takeIf { it is String } ?: InvalidString
}

private fun JSONObject.strictNullableBoolean(name: String): Any? {
    if (!has(name) || isNull(name)) return null
    return opt(name).takeIf { it is Boolean } ?: InvalidBoolean
}
