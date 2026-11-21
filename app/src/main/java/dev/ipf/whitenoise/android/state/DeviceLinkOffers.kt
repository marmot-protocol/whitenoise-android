package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingSingleDeviceNoticeFfi
import java.util.Locale

/** How far the offer to link a newly signed-in device has got for one account. */
internal enum class DeviceLinkOfferStage {
    /** Sign-in found the account on another installation and the offer has not been answered. */
    Offer,

    /** The user chose Not now, so the chat list keeps a reminder until it is dismissed or the device links. */
    Reminder,
}

/** Whether MDK's sign-in device check found another installation of the account, which is what linking joins. */
internal fun foundOtherInstallation(notice: OnboardingSingleDeviceNoticeFfi?): Boolean =
    notice?.discovery == OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE

/**
 * Remembers, per account, whether this device still offers to link itself after sign-in. It is presentation state
 * only: it never starts, answers or cancels a link, and MDK's roster overrides it once the device is linked.
 */
@Stable
internal class DeviceLinkOffers(
    private val preferences: SharedPreferences,
) {
    // Bumped on every write so composables that read a stage are redrawn.
    private var revision by mutableIntStateOf(0)

    /** The account whose linking steps are open over the app, or null. Not kept across process death. */
    var linkingFor by mutableStateOf<String?>(null)
        private set

    // Set by the tap that opens the steps and taken once, so only that tap can start a request.
    private var startRequested = false

    /** The durable repair receipt for this account. Use under account lifetime on the native IO dispatcher. */
    fun repair(accountIdHex: String) = DeviceLinkRepair(preferences, accountIdHex)

    /** Opens the linking steps for [accountIdHex] because the user asked to link this device. */
    fun openLinking(accountIdHex: String) {
        if (stage(accountIdHex) == DeviceLinkOfferStage.Offer) postpone(accountIdHex)
        linkingFor = accountIdHex
        startRequested = true
    }

    /**
     * Whether the tap that opened the steps still has to start a request. True once per tap: steps redrawn after a
     * lock, a rotation or a restart never start another request by themselves.
     */
    fun takeStartRequest(): Boolean {
        val requested = startRequested
        startRequested = false
        return requested
    }

    /** Closes the linking steps. The link itself carries on in MDK. */
    fun closeLinking() {
        linkingFor = null
        startRequested = false
    }

    /** The stage recorded for [accountIdHex], or null when nothing is offered. */
    fun stage(accountIdHex: String?): DeviceLinkOfferStage? {
        revision
        val stored = accountIdHex?.let { preferences.getString(key(it), null) }
        return DeviceLinkOfferStage.entries.firstOrNull { it.name == stored }
    }

    /** Records what sign-in found: an offer when another installation exists, and nothing otherwise. */
    fun recordSignIn(
        accountIdHex: String,
        otherInstallationFound: Boolean,
    ) = write(accountIdHex, DeviceLinkOfferStage.Offer.takeIf { otherInstallationFound })

    /** Not now: the offer becomes the chat list's reminder. */
    fun postpone(accountIdHex: String) = write(accountIdHex, DeviceLinkOfferStage.Reminder)

    /** Forgets every stage of an account not in [accountIdsHex], so nothing outlives an account removed here. */
    fun retainAccounts(accountIdsHex: Collection<String>) {
        val kept = accountIdsHex.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val stale =
            preferences.all.keys.filter { entry ->
                when {
                    entry.startsWith(STAGE_PREFIX) -> entry.removePrefix(STAGE_PREFIX) !in kept
                    entry.startsWith(REPAIR_PREFIX) -> entry.removePrefix(REPAIR_PREFIX) !in kept
                    else -> false
                }
            }
        if (stale.isEmpty()) return
        val editor = preferences.edit()
        stale.forEach(editor::remove)
        editor.apply()
        revision++
    }

    /** Ends the offer, because the user dismissed the reminder or the device is linked. */
    fun clear(accountIdHex: String) = write(accountIdHex, null)

    private fun write(
        accountIdHex: String,
        stage: DeviceLinkOfferStage?,
    ) {
        val editor = preferences.edit()
        if (stage == null) editor.remove(key(accountIdHex)) else editor.putString(key(accountIdHex), stage.name)
        editor.apply()
        revision++
    }

    private fun key(accountIdHex: String) = STAGE_PREFIX + accountIdHex.trim().lowercase(Locale.ROOT)

    internal companion object {
        const val PREFERENCES_NAME = "whitenoise.device_link_offers"
        private const val STAGE_PREFIX = "stage."
        internal const val REPAIR_PREFIX = "repair.rotation."

        @Volatile
        private var shared: DeviceLinkOffers? = null

        /** The one store for the process, so the sign-in flow, the offer and the reminder see the same stage. */
        fun shared(context: Context): DeviceLinkOffers =
            shared ?: synchronized(this) {
                shared ?: DeviceLinkOffers(
                    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
                ).also { shared = it }
            }
    }
}
