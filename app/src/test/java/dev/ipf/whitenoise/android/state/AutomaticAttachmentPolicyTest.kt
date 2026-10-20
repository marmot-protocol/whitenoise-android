package dev.ipf.whitenoise.android.state

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The automatic-download decision shared by receipt scheduling and durable work (#3308). */
class AutomaticAttachmentPolicyTest {
    /** Every hold is reported for its own reason, account-wide holds first. */
    @Test
    fun holdsAreReportedForTheirOwnReason() {
        val wifi = policy()

        assertNull(wifi.hold(MediaAutoDownloadType.Image))
        assertEquals(AutomaticAttachmentHold.Disabled, wifi.hold(MediaAutoDownloadType.Document))
        assertEquals(
            AutomaticAttachmentHold.Network,
            wifi.copy(networks = setOf(MediaAutoDownloadNetwork.Mobile)).hold(MediaAutoDownloadType.Video),
        )
        assertEquals(
            AutomaticAttachmentHold.Network,
            wifi.copy(validatedInternet = false).hold(MediaAutoDownloadType.Image),
        )
        assertEquals(AutomaticAttachmentHold.Paused, wifi.copy(paused = true).hold(MediaAutoDownloadType.Image))
        assertEquals(
            AutomaticAttachmentHold.SignedOut,
            wifi.copy(account = AutomaticPolicyAccount.SignedOut, paused = true).hold(MediaAutoDownloadType.Image),
        )
        assertEquals(
            AutomaticAttachmentHold.UnknownAccount,
            wifi.copy(account = AutomaticPolicyAccount.Unknown).hold(MediaAutoDownloadType.Image),
        )
        assertEquals(
            AutomaticAttachmentHold.Disabled,
            wifi.copy(matrix = MediaAutoDownloadMatrix(emptySet())).accountHold(),
        )
        assertNull(wifi.accountHold())
    }

    /** What is allowed now matches the native permission, and nothing is allowed for an unloaded account. */
    @Test
    fun allowedNowMatchesTheNativePermission() {
        val wifi = policy()

        assertEquals(
            setOf(MediaAutoDownloadType.Image, MediaAutoDownloadType.Audio, MediaAutoDownloadType.Video),
            wifi.allowedNow(),
        )
        val unloaded = wifi.copy(account = AutomaticPolicyAccount.Unknown)
        assertEquals(emptySet<MediaAutoDownloadType>(), unloaded.allowedNow())
        assertEquals(emptySet<MediaAutoDownloadType>(), wifi.copy(networks = emptySet()).allowedNow())
    }

    /** A receipt queues every type enabled anywhere, every type for an unloaded account, and nothing when held. */
    @Test
    fun receiptTypesFollowTheAccountAndEveryNetworkRow() {
        val mobile = policy().copy(networks = setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Metered))

        assertEquals(
            setOf(MediaAutoDownloadType.Image, MediaAutoDownloadType.Audio, MediaAutoDownloadType.Video),
            mobile.receiptTypes(),
        )
        assertEquals(
            MediaAutoDownloadType.entries.toSet(),
            mobile.copy(account = AutomaticPolicyAccount.Unknown).receiptTypes(),
        )
        assertTrue(mobile.copy(paused = true).receiptTypes().isEmpty())
        assertTrue(mobile.copy(account = AutomaticPolicyAccount.SignedOut).receiptTypes().isEmpty())
        val signedOut = mobile.copy(account = AutomaticPolicyAccount.SignedOut)
        assertEquals(AutomaticAttachmentHold.SignedOut, signedOut.receiptSkipReason())
        assertEquals(AutomaticAttachmentHold.Paused, mobile.copy(paused = true).receiptSkipReason())
    }

    /** Work waits for the narrowest network its type could be allowed on, or any network for an unloaded account. */
    @Test
    fun networkTypeIsTheNarrowestTheMatrixAllows() {
        val roamingOff =
            MediaAutoDownloadMatrix.DEFAULT.withToggle(
                MediaAutoDownloadType.Image,
                MediaAutoDownloadNetwork.Metered,
                on = true,
            )
        val everywhere =
            MediaAutoDownloadNetwork.entries.fold(roamingOff) { matrix, network ->
                matrix.withToggle(MediaAutoDownloadType.Audio, network, on = true)
            }
        val policy = policy().copy(matrix = everywhere)

        assertEquals(NetworkType.UNMETERED, policy.networkType(MediaAutoDownloadType.Video))
        assertEquals(NetworkType.NOT_ROAMING, policy.networkType(MediaAutoDownloadType.Image))
        assertEquals(NetworkType.CONNECTED, policy.networkType(MediaAutoDownloadType.Audio))
        assertEquals(
            NetworkType.CONNECTED,
            policy.copy(account = AutomaticPolicyAccount.Unknown).networkType(MediaAutoDownloadType.Video),
        )
    }

    /** A signed-in account on validated Wi-Fi with the default matrix. */
    private fun policy() =
        AutomaticAttachmentPolicy(
            account = AutomaticPolicyAccount.SignedIn,
            paused = false,
            matrix = MediaAutoDownloadMatrix.DEFAULT,
            networks = setOf(MediaAutoDownloadNetwork.WiFi),
            validatedInternet = true,
        )
}
