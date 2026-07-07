package dev.ipf.whitenoise.android.amber

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

// NIP-55 external-signer (Amber) protocol layer.
//
// Pure protocol knowledge — constants, availability, saved-package prefs,
// ContentResolver operations, and Intent builders — with the result
// interpretation factored into side-effect-free functions (parseContentRow,
// parseActivityResult) so the wire contract can be unit-tested without an
// Android runtime. Ported from the reference Flutter plugin
// (AndroidSignerPlugin.kt / android_signer_service.dart).

/** A NIP-55 operation and its wire encoding (intent `type` + ContentResolver authority suffix). */
enum class SignerOp(
    val intentType: String,
    val contentAuthoritySuffix: String,
) {
    GetPublicKey("get_public_key", ""),
    SignEvent("sign_event", "SIGN_EVENT"),
    Nip04Encrypt("nip04_encrypt", "NIP04_ENCRYPT"),
    Nip04Decrypt("nip04_decrypt", "NIP04_DECRYPT"),
    Nip44Encrypt("nip44_encrypt", "NIP44_ENCRYPT"),
    Nip44Decrypt("nip44_decrypt", "NIP44_DECRYPT"),
}

/** Interpretation of a NIP-55 ContentResolver row. */
sealed interface ContentRowOutcome {
    data class Value(
        val value: String,
    ) : ContentRowOutcome

    /**
     * The signer can't answer in the background (no cursor, a `rejected` flag,
     * or a missing value column). Mirrors the reference plugin: the caller must
     * fall back to the foreground Intent prompt rather than treating this as a
     * terminal rejection — a not-yet-"remembered" operation reports `rejected`.
     */
    data object Unavailable : ContentRowOutcome
}

/** Interpretation of an `onActivityResult` from the signer app. */
sealed interface ActivityResultOutcome {
    data class PublicKey(
        val pubkey: String,
        val packageName: String?,
    ) : ActivityResultOutcome

    data class Value(
        val value: String,
    ) : ActivityResultOutcome

    /** RESULT != OK / cancelled — the user declined the prompt. */
    data object Rejected : ActivityResultOutcome

    /** RESULT OK but the expected extra was missing/blank. */
    data class Malformed(
        val reason: String,
    ) : ActivityResultOutcome
}

object Nip55 {
    const val SCHEME = "nostrsigner"

    const val PREFS_FILE = "amber_signer_prefs"
    const val PREFS_KEY_PACKAGE = "signer_package_name"

    const val EXTRA_TYPE = "type"
    const val EXTRA_PERMISSIONS = "permissions"
    const val EXTRA_ID = "id"
    const val EXTRA_CURRENT_USER = "current_user"
    const val EXTRA_PUBKEY = "pubkey"
    const val EXTRA_RESULT = "result"
    const val EXTRA_EVENT = "event"
    const val EXTRA_PACKAGE = "package"

    const val COLUMN_REJECTED = "rejected"
    const val COLUMN_RESULT = "result"
    const val COLUMN_EVENT = "event"

    // Kinds mirrored from the Flutter default-permissions list
    // (android_signer_service.dart / nostr_event_kinds.dart): the MLS surfaces
    // plus relay lists and gift wrap. Pre-approving these lets Amber answer the
    // matching sign_event calls via ContentResolver without a per-call prompt.
    private val SIGN_EVENT_PERMISSION_KINDS =
        listOf(
            30443, // mlsKeyPackage
            443, // mlsKeyPackageLegacy
            444, // mlsWelcome
            445, // mlsGroupMessage
            1059, // giftWrap
            10002, // relayListMetadata
            10050, // inboxRelays
            10051, // mlsKeyPackageRelays
        )

    /** True when at least one app can handle the `nostrsigner:` scheme. */
    fun isExternalSignerInstalled(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:"))
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    fun savedSignerPackage(context: Context): String? = prefs(context).getString(PREFS_KEY_PACKAGE, null)?.takeIf { it.isNotEmpty() }

    fun saveSignerPackage(
        context: Context,
        packageName: String,
    ) {
        prefs(context).edit().putString(PREFS_KEY_PACKAGE, packageName).apply()
    }

    fun clearSignerPackage(context: Context) {
        prefs(context).edit().putString(PREFS_KEY_PACKAGE, "").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * The permissions JSON sent with `get_public_key`, mirroring the reference
     * Flutter service: one `sign_event` entry per relevant kind plus
     * `nip44_encrypt` / `nip44_decrypt`. Keyed by field, so object key order is
     * irrelevant to the signer.
     */
    fun defaultPermissionsJson(): String {
        val permissions = JSONArray()
        SIGN_EVENT_PERMISSION_KINDS.forEach { kind ->
            permissions.put(JSONObject().put("type", "sign_event").put("kind", kind))
        }
        permissions.put(JSONObject().put("type", "nip44_encrypt"))
        permissions.put(JSONObject().put("type", "nip44_decrypt"))
        return permissions.toString()
    }

    fun buildGetPublicKeyIntent(permissionsJson: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")).apply {
            putExtra(EXTRA_TYPE, SignerOp.GetPublicKey.intentType)
            if (permissionsJson.isNotEmpty()) putExtra(EXTRA_PERMISSIONS, permissionsJson)
        }

    fun buildSignEventIntent(
        packageName: String,
        eventJson: String,
        id: String,
        currentUser: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:$eventJson")).apply {
            `package` = packageName
            putExtra(EXTRA_TYPE, SignerOp.SignEvent.intentType)
            putExtra(EXTRA_ID, id)
            if (currentUser.isNotEmpty()) putExtra(EXTRA_CURRENT_USER, currentUser)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    fun buildCryptoIntent(
        op: SignerOp,
        packageName: String,
        content: String,
        counterparty: String,
        currentUser: String,
        id: String,
    ): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:$content")).apply {
            `package` = packageName
            putExtra(EXTRA_TYPE, op.intentType)
            putExtra(EXTRA_PUBKEY, counterparty)
            if (currentUser.isNotEmpty()) putExtra(EXTRA_CURRENT_USER, currentUser)
            putExtra(EXTRA_ID, id)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /**
     * Query the signer's ContentResolver interface. Returns [ContentRowOutcome.Value]
     * only when the signer answered in the background; any other case (no
     * provider, exception, `rejected`, missing column) is [ContentRowOutcome.Unavailable]
     * so the caller falls back to the Intent prompt.
     *
     * `args` follow the NIP-55 convention: sign_event is `[eventJson, "", currentUser]`;
     * encrypt/decrypt is `[content, counterparty, currentUser]`.
     */
    fun queryViaContentResolver(
        context: Context,
        op: SignerOp,
        packageName: String,
        args: Array<String>,
    ): ContentRowOutcome =
        try {
            val uri = Uri.parse("content://$packageName.${op.contentAuthoritySuffix}")
            context.contentResolver.query(uri, args, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    parseContentRow(
                        op,
                        rejected = readRejectedFlag(cursor),
                        resultColumn = cursor.stringColumnOrNull(COLUMN_RESULT),
                        eventColumn = cursor.stringColumnOrNull(COLUMN_EVENT),
                    )
                } else {
                    ContentRowOutcome.Unavailable
                }
            } ?: ContentRowOutcome.Unavailable
        } catch (_: Exception) {
            // Provider absent or threw — indistinguishable from "not granted",
            // so fall back to the Intent prompt.
            ContentRowOutcome.Unavailable
        }

    private fun readRejectedFlag(cursor: Cursor): Boolean {
        val index = cursor.getColumnIndex(COLUMN_REJECTED)
        if (index < 0) return false
        return if (cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) {
            cursor.getInt(index) != 0
        } else {
            cursor.getString(index)?.toBoolean() == true
        }
    }

    private fun Cursor.stringColumnOrNull(name: String): String? {
        val index = getColumnIndex(name)
        return if (index >= 0) getString(index) else null
    }
}

/**
 * Pure interpretation of a ContentResolver row. `sign_event` yields the SIGNED
 * event JSON from the `event` column; everything else yields the `result`
 * column. A `rejected` flag or an absent/blank value means "can't answer in the
 * background" — the caller falls back to the Intent prompt.
 */
fun parseContentRow(
    op: SignerOp,
    rejected: Boolean,
    resultColumn: String?,
    eventColumn: String?,
): ContentRowOutcome {
    if (rejected) return ContentRowOutcome.Unavailable
    val value =
        when (op) {
            SignerOp.SignEvent -> eventColumn
            else -> resultColumn
        }
    return value?.takeIf { it.isNotBlank() }?.let { ContentRowOutcome.Value(it) }
        ?: ContentRowOutcome.Unavailable
}

/**
 * Pure interpretation of a signer `onActivityResult`. `get_public_key` reads the
 * npub/hex from `result` (plus the chosen `package`); `sign_event` reads the
 * SIGNED event JSON from `event`; encrypt/decrypt reads `result`. A non-OK
 * result is a [ActivityResultOutcome.Rejected]; an OK result with the expected
 * extra missing is [ActivityResultOutcome.Malformed].
 */
fun parseActivityResult(
    op: SignerOp,
    resultOk: Boolean,
    resultExtra: String?,
    eventExtra: String?,
    packageExtra: String?,
): ActivityResultOutcome {
    if (!resultOk) return ActivityResultOutcome.Rejected
    return when (op) {
        SignerOp.GetPublicKey -> {
            val pubkey =
                resultExtra?.takeIf { it.isNotBlank() }
                    ?: return ActivityResultOutcome.Malformed("missing public key")
            ActivityResultOutcome.PublicKey(pubkey, packageExtra?.takeIf { it.isNotBlank() })
        }
        SignerOp.SignEvent -> {
            val event =
                eventExtra?.takeIf { it.isNotBlank() }
                    ?: return ActivityResultOutcome.Malformed("missing signed event")
            ActivityResultOutcome.Value(event)
        }
        else -> {
            val value =
                resultExtra?.takeIf { it.isNotBlank() }
                    ?: return ActivityResultOutcome.Malformed("missing result")
            ActivityResultOutcome.Value(value)
        }
    }
}
