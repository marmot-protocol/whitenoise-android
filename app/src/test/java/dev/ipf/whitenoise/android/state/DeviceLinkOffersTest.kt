package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.whitenoise.android.ui.devices.RuntimeDeviceLink
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupDeviceNotice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

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

    /** Only the tap that opens the steps may start a request, and only once. */
    @Test
    fun onlyTheOpeningTapStartsARequest() {
        val offers = DeviceLinkOffers(preferences)

        assertFalse(offers.takeStartRequest())
        offers.openLinking(ACCOUNT)

        assertTrue(offers.takeStartRequest())
        assertFalse(offers.takeStartRequest())
    }

    /** An account removed from the device leaves no stage behind; the others keep theirs. */
    @Test
    fun aRemovedAccountLeavesNoStage() {
        val offers = DeviceLinkOffers(preferences)
        offers.recordSignIn(ACCOUNT, otherInstallationFound = true)
        offers.recordSignIn(OTHER, otherInstallationFound = true)

        offers.retainAccounts(listOf(OTHER.uppercase()))

        assertNull(offers.stage(ACCOUNT))
        assertEquals(DeviceLinkOfferStage.Offer, offers.stage(OTHER))
    }

    /** Closing the steps after accepting the offer leaves the reminder instead of reopening the question. */
    @Test
    fun closingTheStepsKeepsOnlyTheReminder() {
        val offers = DeviceLinkOffers(preferences)
        offers.recordSignIn(ACCOUNT, otherInstallationFound = true)

        offers.openLinking(ACCOUNT)
        assertEquals(ACCOUNT, offers.linkingFor)
        offers.closeLinking()

        assertNull(offers.linkingFor)
        assertFalse(offers.takeStartRequest())
        assertEquals(DeviceLinkOfferStage.Reminder, DeviceLinkOffers(preferences).stage(ACCOUNT))
    }

    /** A plain switch issues no link command. Switches cancel an unconfirmed start but preserve submitted consent. */
    @Test
    fun aSwitchCancelsOnlyALocallyStartedUnconfirmedLink() =
        runTest {
            val calls = mutableListOf<String>()
            var loseConfirmationReply = false
            val api = MarmotInterface::class.java
            val native =
                Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { proxy, method, args ->
                    when (method.name) {
                        "toString" -> "DeviceLinkCancelFixture"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "startDeviceLink" -> snapshot(NewDeviceLinkStateFfi.WaitingForAnswer)
                        "confirmDeviceLinkMatch" ->
                            if (loseConfirmationReply) {
                                failSuspendedNativeCall(args, MarmotKitException.TransportClosed())
                            } else {
                                snapshot(NewDeviceLinkStateFfi.WaitingForOtherDevice)
                            }
                        "cancelDeviceLink" -> {
                            calls += "cancel:${args?.firstOrNull()}"
                            failSuspendedNativeCall(args, MarmotKitException.TransportClosed())
                        }
                        "deviceLinkSnapshot" -> error("Reading can advance the pending Welcome")
                        else -> null
                    }
                } as MarmotInterface
            val state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { ACCOUNT },
                    accounts = listOf(AccountSummaryFfi("old", ACCOUNT, true, false, false, true, false)),
                    activeAccountRef = "old",
                    initialMarmotRuntime = AppMarmotRuntime("test", native),
                )
            DeviceLinkOffers.shared(context).closeLinking()
            val binding = RuntimeDeviceLink(state)
            state.cancelLinkAfterProfileSwitch("old")
            assertTrue(calls.isEmpty())
            binding.startDeviceLink("old")
            state.cancelLinkAfterProfileSwitch("old")
            binding.startDeviceLink("old")
            binding.confirmDeviceLinkMatch("old")
            state.cancelLinkAfterProfileSwitch("old")
            binding.startDeviceLink("old")
            loseConfirmationReply = true
            assertTrue(runCatching { binding.confirmDeviceLinkMatch("old") }.isFailure)
            state.cancelLinkAfterProfileSwitch("old")
            assertEquals(listOf("cancel:old"), calls)
            assertNull(state.toast)
        }

    private companion object {
        fun snapshot(state: NewDeviceLinkStateFfi) = DeviceLinkSnapshotFfi(state, emptyList(), emptyList(), false)

        const val ACCOUNT = "aa11"
        const val OTHER = "bb22"
    }
}
