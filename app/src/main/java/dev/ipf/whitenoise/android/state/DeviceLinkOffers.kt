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

    /** Opens the linking steps for [accountIdHex]. */
    fun openLinking(accountIdHex: String) {
        linkingFor = accountIdHex
    }

    /** Closes the linking steps. The link itself carries on in MDK. */
    fun closeLinking() {
        linkingFor = null
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

    private fun key(accountIdHex: String) = "stage." + accountIdHex.trim().lowercase(Locale.ROOT)

    internal companion object {
        const val PREFERENCES_NAME = "whitenoise.device_link_offers"

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
