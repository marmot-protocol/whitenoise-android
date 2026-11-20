package dev.ipf.whitenoise.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.ipf.marmotkit.AttachmentAutomaticPermissionFfi
import dev.ipf.whitenoise.android.state.ActiveDefaultNetworkTracker
import dev.ipf.whitenoise.android.state.NativeAttachmentPermissions
import dev.ipf.whitenoise.android.state.ValidatedInternetNetworkTracker
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Reads the real coordinator used by the fixture; it does not create or refresh an approval. */
internal fun platformResumePermissions(state: WhiteNoiseAppState): NativeAttachmentPermissions =
    readResumeField(state, "nativeAttachmentPermissions") as NativeAttachmentPermissions

/** Retains identities only in memory to report changes, never the handles or account reference themselves. */
internal class PlatformResumeHostDiagnostics(
    private val state: WhiteNoiseAppState,
    private val account: String,
) {
    /** The identity tuple is never passed to a JSON encoder. */
    private data class Inputs(
        val defaultNetwork: Long?,
        val validatedNetworks: Set<Long>,
        val networkTypes: Set<String>,
        val accountListGeneration: Long,
    )

    private var previous: Inputs? = null
    private var previousRevision: Long? = null
    private var comparison: Inputs? = null
    private var comparisonRevision: Long? = null

    /** Adds passive, closed facts to an existing observation; a diagnostic failure cannot fail the probe. */
    fun snapshot(): JSONObject =
        try {
            capture()
        } catch (_: ReflectiveOperationException) {
            JSONObject().put("available", false)
        } catch (_: RuntimeException) {
            JSONObject().put("available", false)
        }

    /** Captures mirrors and coordinator state without Binder calls, new subscriptions, or native mutations. */
    private fun capture(): JSONObject {
        val defaults = readResumeField(state, "activeDefaultNetwork") as ActiveDefaultNetworkTracker
        val validated = readResumeField(state, "validatedInternetNetworks") as ValidatedInternetNetworkTracker
        val current =
            Inputs(
                defaults.identitySnapshot(),
                validated.identitySnapshot(),
                state.activeNetworkTypes().map { it.name }.toSet(),
                state.accountListLifetime.capture(),
            )
        val result = permissionSnapshot()
        val revision = result.getLong("observed_permission_revision")
        if (previousRevision != revision) {
            comparison = previous
            comparisonRevision = previousRevision
            previous = current
            previousRevision = revision
        }
        val old = comparison
        val validatedChanged = old?.let { it.validatedNetworks != current.validatedNetworks }
        result
            .put("compared_permission_revision", comparisonRevision ?: JSONObject.NULL)
            .put("inputs_changed_during_revision", current != previous)
            .put("default_network_present", current.defaultNetwork != null)
            .put("default_network_changed", old?.let { it.defaultNetwork != current.defaultNetwork } ?: JSONObject.NULL)
            .put("validated_set_changed", validatedChanged ?: JSONObject.NULL)
            .put("network_types_changed", old?.let { it.networkTypes != current.networkTypes } ?: JSONObject.NULL)
            .put("network_types", JSONArray(current.networkTypes.sorted()))
            .put("has_active_network", state.hasActiveNetwork())
            .put("has_validated_internet", state.hasValidatedInternet())
            .put(
                "account_list_generation_changed",
                old?.let { it.accountListGeneration != current.accountListGeneration }
                    ?: JSONObject.NULL,
            ).put("bootstrap_completed", readResumeField(state, "bootstrapCompleted") as Boolean)
            .put("preference_commit_locked", (readResumeField(state, "hostPreferenceCommitMutex") as Mutex).isLocked)
        return result
    }

    /** Reads one coordinator generation under its monitor without changing or granting an approval. */
    private fun permissionSnapshot(): JSONObject {
        val permissions = platformResumePermissions(state)
        return synchronized(permissions) {
            val revision = (readResumeField(permissions, "revision") as AtomicLong).get()
            val networkKey = readResumeField(permissions, "networkRefresh")
            val keyRevision = networkKey?.let { readResumeField(it, "revision") as Long }
            val applied = (readResumeField(permissions, "applied") as StateFlow<*>).value
            val accounts = applied?.let { readResumeField(it, "accounts") as Map<*, *> }
            val allowed = accounts?.get(account) as? AttachmentAutomaticPermissionFfi
            val appliedRevision =
                if (allowed == null) null else readResumeField(requireNotNull(applied), "revision") as Long
            JSONObject()
                .put("available", true)
                .put("observed_permission_revision", revision)
                // Absence is unclassified: changed network evaluations also clear the coalescing key.
                .put("network_key_matches_revision", keyRevision?.let { it == revision } ?: JSONObject.NULL)
                .put("applied_revision", appliedRevision ?: JSONObject.NULL)
                .put("documents_allowed", allowed?.files ?: JSONObject.NULL)
        }
    }
}

/** Reports actual platform connectivity only on failure, without handles, addresses, or raw service dumps. */
internal fun platformResumeNetworkFailureSnapshot(context: Context): JSONObject =
    try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val caps = manager?.getNetworkCapabilities(network)
        JSONObject()
            .put("available", manager != null)
            .put("default_present", network != null)
            .put("default_stable_during_read", network == manager?.activeNetwork)
            .put("wifi", caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            .put("cellular", caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            .put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
    } catch (_: RuntimeException) {
        JSONObject().put("available", false)
    }

/** Reflection stays inside the Lab fixture and never serializes the reflected objects. */
private fun readResumeField(
    owner: Any,
    name: String,
): Any? =
    owner.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .get(owner)
