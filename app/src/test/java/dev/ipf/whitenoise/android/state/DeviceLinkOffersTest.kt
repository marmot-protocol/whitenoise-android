package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupDeviceNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The offer to link a newly signed-in device follows what sign-in found and the user's answers, per account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceLinkOffersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("device-link-offers-test", Context.MODE_PRIVATE)

    /** Only MDK finding another installation counts; nothing found, an unchecked result and no notice do not. */
    @Test
    fun onlyAnotherInstallationIsFound() {
        assertTrue(foundOtherInstallation(setupDeviceNotice(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE)))
        assertFalse(foundOtherInstallation(setupDeviceNotice(OnboardingDeviceDiscoveryFfi.NONE_FOUND)))
        assertFalse(foundOtherInstallation(setupDeviceNotice(OnboardingDeviceDiscoveryFfi.UNKNOWN)))
        assertFalse(foundOtherInstallation(null))
    }

    /** A sign-in that found another installation offers linking; one that found none offers nothing. */
    @Test
    fun signInOffersOnlyWhenAnotherInstallationWasFound() {
        val offers = DeviceLinkOffers(preferences)

        offers.recordSignIn(ACCOUNT, otherInstallationFound = true)
        offers.recordSignIn(OTHER, otherInstallationFound = false)

        assertEquals(DeviceLinkOfferStage.Offer, offers.stage(ACCOUNT))
        assertNull(offers.stage(OTHER))
        assertNull(offers.stage(null))
    }

    /** Not now turns the offer into the reminder, which a new store still reads, whatever the id's letter case. */
    @Test
    fun notNowKeepsAReminderAcrossRestarts() {
        DeviceLinkOffers(preferences).apply {
            recordSignIn(ACCOUNT, otherInstallationFound = true)
            postpone(ACCOUNT)
        }

        assertEquals(DeviceLinkOfferStage.Reminder, DeviceLinkOffers(preferences).stage(ACCOUNT.uppercase()))
    }

    /** Clearing ends the offer for that account only. */
    @Test
    fun clearingEndsOneAccountsOffer() {
        val offers = DeviceLinkOffers(preferences)
        offers.recordSignIn(ACCOUNT, otherInstallationFound = true)
        offers.recordSignIn(OTHER, otherInstallationFound = true)

        offers.clear(ACCOUNT)

        assertNull(offers.stage(ACCOUNT))
        assertEquals(DeviceLinkOfferStage.Offer, offers.stage(OTHER))
    }

    /** A later sign-in that finds nothing withdraws an earlier reminder. */
    @Test
    fun aLaterSignInThatFindsNothingWithdrawsTheReminder() {
        val offers = DeviceLinkOffers(preferences)
        offers.recordSignIn(ACCOUNT, otherInstallationFound = true)
        offers.postpone(ACCOUNT)

        offers.recordSignIn(ACCOUNT, otherInstallationFound = false)

        assertNull(offers.stage(ACCOUNT))
    }

    /** Opening and closing the linking steps changes no recorded stage. */
    @Test
    fun openingTheStepsKeepsTheStage() {
        val offers = DeviceLinkOffers(preferences)
        offers.recordSignIn(ACCOUNT, otherInstallationFound = true)

        offers.openLinking(ACCOUNT)
        assertEquals(ACCOUNT, offers.linkingFor)
        offers.closeLinking()

        assertNull(offers.linkingFor)
        assertEquals(DeviceLinkOfferStage.Offer, offers.stage(ACCOUNT))
    }

    private companion object {
        const val ACCOUNT = "aa11"
        const val OTHER = "bb22"
    }
}
