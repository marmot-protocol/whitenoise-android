package dev.ipf.whitenoise.android.amber

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

private const val AMBER_GROUPED_APPROVALS_MIN_MAJOR = 6
private const val AMBER_GROUPED_APPROVALS_MIN_MINOR = 3

// NIP-55 external-signer (Amber) protocol layer.
//
// Pure protocol knowledge — constants, availability, saved-package prefs,
// ContentResolver operations, and Intent builders — with the result
// interpretation factored into side-effect-free helpers in Nip55SignerPure.kt
// so the wire contract can be unit-tested without an Android runtime. Ported
// from the reference Flutter plugin
// (AndroidSignerPlugin.kt / android_signer_service.dart).

object Nip55 {
    const val SCHEME = Nip55Pure.SCHEME

    internal const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"
    internal const val AMBER_DEBUG_PACKAGE = "$AMBER_PACKAGE.debug"
    const val MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES = Nip55Pure.MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES

    const val PREFS_FILE = "amber_signer_prefs"
    const val PREFS_KEY_PACKAGE = "signer_package_name"

    const val EXTRA_TYPE = "type"
    const val EXTRA_PERMISSIONS = "permissions"
    const val EXTRA_ID = "id"
    const val EXTRA_CURRENT_USER = "current_user"
    const val EXTRA_PUBKEY = "pubkey"
    const val EXTRA_RESULT = "result"
    const val EXTRA_RESULTS = "results"
    const val EXTRA_EVENT = "event"
    const val EXTRA_SIGNATURE = "signature"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_AGGREGATE_RESULT = "dev.ipf.whitenoise.android.amber.AGGREGATE_RESULT"

    /** Amber/Android Binder safety bounds for one foreground approval session. */
    const val MAX_GROUPED_APPROVALS = 16
    const val MAX_AGGREGATE_RESULTS_UTF8_BYTES = 512 * 1024
    const val MAX_REQUEST_ID_CHARS = 128

    const val COLUMN_REJECTED = Nip55Pure.COLUMN_REJECTED
    const val EXTRA_REJECTED = COLUMN_REJECTED
    const val COLUMN_RESULT = Nip55Pure.COLUMN_RESULT
    const val COLUMN_EVENT = Nip55Pure.COLUMN_EVENT

    // The one canonical login grant set. Pre-approving these lets Amber answer
    // matching ContentResolver calls without a foreground prompt.
    internal val LOGIN_PERMISSIONS =
        listOf(
            SignerPermission(SignerOp.SignEvent, 450), // identity proof
            SignerPermission(SignerOp.SignEvent, 30443), // mlsKeyPackage
            SignerPermission(SignerOp.SignEvent, 443), // mlsKeyPackageLegacy
            SignerPermission(SignerOp.SignEvent, 444), // mlsWelcome
            SignerPermission(SignerOp.SignEvent, 445), // mlsGroupMessage
            SignerPermission(SignerOp.SignEvent, 1059), // giftWrap
            SignerPermission(SignerOp.SignEvent, 10002), // relayListMetadata
            SignerPermission(SignerOp.SignEvent, 10050), // inboxRelays
            SignerPermission(SignerOp.SignEvent, 10051), // mlsKeyPackageRelays
            SignerPermission(SignerOp.Nip44Encrypt),
            SignerPermission(SignerOp.Nip44Decrypt),
        )

    /** True when at least one app can handle the `nostrsigner:` scheme. */
    fun isExternalSignerInstalled(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:"))
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    /** True when [packageName] still exposes a handler for the NIP-55 scheme. */
    fun isSignerPackageAvailable(
        context: Context,
        packageName: String,
    ): Boolean {
        if (packageName.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")).setPackage(packageName)
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    /**
     * Amber 6.3 introduced local-intent grouping. Keep every older or unknown
     * signer on the established one-prompt relay path instead of assuming that
     * repeated launches are safe merely because it implements NIP-55.
     */
    fun supportsGroupedApprovals(
        context: Context,
        packageName: String,
    ): Boolean {
        val versionName =
            runCatching {
                val packageManager = context.packageManager
                val packageInfo =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(packageName, 0)
                    }
                packageInfo.versionName
            }.getOrNull()
        return amberVersionSupportsGroupedApprovals(packageName, versionName)
    }

    /**
     * Returns the sole installed signer package only when it is a recognized
     * grouped-capable Amber release. Ambiguous handler sets deliberately return
     * null so login retains the existing user-owned chooser path.
     */
    internal fun soleGroupedLoginSignerPackage(context: Context): String? {
        val handlers =
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")), 0)
                .mapNotNull { resolved ->
                    val info = resolved.activityInfo ?: return@mapNotNull null
                    val packageName = info.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val className = info.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    packageName to className
                }.distinct()
        val packageName = handlers.singleOrNull()?.first ?: return null
        return packageName.takeIf { supportsGroupedApprovals(context, it) }
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

    /** Canonical byte-stable NIP-55 grant payload attached to login. */
    internal fun loginPermissionsJson(): String {
        val permissions = JSONArray()
        LOGIN_PERMISSIONS.forEach { permission ->
            val entry = JSONObject().put("type", permission.operation.intentType)
            permission.kind?.let { entry.put("kind", it) }
            permissions.put(entry)
        }
        return permissions.toString()
    }

    /** True when [content] is small enough to embed in a foreground Intent data URI. */
    fun contentFitsIntentFallbackBudget(content: String): Boolean = Nip55Pure.contentFitsIntentFallbackBudget(content)

    /** Build the only supported login request, including the complete typed grant set. */
    fun buildGetPublicKeyIntent(id: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME:")).apply {
            require(id.isNotBlank()) { "NIP-55 login request id must not be blank" }
            putExtra(EXTRA_TYPE, SignerOp.GetPublicKey.intentType)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_PERMISSIONS, loginPermissionsJson())
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
     * when the signer answered in the background, [ContentRowOutcome.Rejected]
     * for a remembered rejection, or [ContentRowOutcome.Unavailable] when the
     * caller may fall back to the Intent prompt.
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
                        rejected = cursor.getColumnIndex(COLUMN_REJECTED) >= 0,
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

    private fun Cursor.stringColumnOrNull(name: String): String? {
        val index = getColumnIndex(name)
        return if (index >= 0) getString(index) else null
    }
}

internal fun readRejectedIntentExtra(data: Intent?): Boolean = data?.getBooleanExtra(Nip55.EXTRA_REJECTED, false) == true

/** Rebuild the signed event JSON from Amber's grouped signature-only result. */
internal fun signedEventFromAggregate(
    unsignedEventJson: String,
    signature: String?,
): String? {
    val normalizedSignature = signature?.takeIf { it.matches(Regex("[0-9a-fA-F]{128}")) } ?: return null
    return runCatching {
        JSONObject(unsignedEventJson)
            .put("sig", normalizedSignature.lowercase())
            .toString()
    }.getOrNull()
}

internal fun amberVersionSupportsGroupedApprovals(
    packageName: String,
    versionName: String?,
): Boolean =
    if (packageName != Nip55.AMBER_PACKAGE && packageName != Nip55.AMBER_DEBUG_PACKAGE) {
        false
    } else {
        val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(versionName.orEmpty())
        val version = match?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: 0 }
        version != null &&
            (
                version[0] > AMBER_GROUPED_APPROVALS_MIN_MAJOR ||
                    (
                        version[0] == AMBER_GROUPED_APPROVALS_MIN_MAJOR &&
                            version[1] >= AMBER_GROUPED_APPROVALS_MIN_MINOR
                    )
            )
    }
