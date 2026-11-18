package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The device step's evidence and explanation follow MDK's discovery, and only a past publication date is named. */
class SetupDeviceEvidenceTest {
    /** Each discovery maps to its evidence, and no notice means MDK has not checked yet. */
    @Test
    fun eachDiscoveryMapsToItsEvidence() {
        assertNull(setupDeviceEvidence(null, NOW))
        assertEquals(SetupDeviceEvidence.NoneFound, evidence(OnboardingDeviceDiscoveryFfi.NONE_FOUND))
        assertEquals(SetupDeviceEvidence.Unchecked, evidence(OnboardingDeviceDiscoveryFfi.UNKNOWN))
        assertEquals(
            SetupDeviceEvidence.OtherApps(null),
            evidence(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE),
        )
    }

    /** The latest past publication is named, and missing, future and out-of-range dates are skipped. */
    @Test
    fun otherAppsNameTheLatestPastPublication() {
        val older = 1_700_000_000uL
        val latest = 1_900_000_000uL
        val future = 2_100_000_000uL

        assertEquals(latest.toLong(), latestPublication(older, latest))
        assertEquals(latest.toLong(), latestPublication(latest, future))
        assertNull(latestPublication(future))
        assertNull(latestPublication(ULong.MAX_VALUE))
        assertNull(latestPublication(0uL))
    }

    /** A possible other installation is explained as one this device can link to, finding none needs no explanation. */
    @Test
    fun explanationDescribesASeparateInstallationOnlyWhenAnotherAppMayExist() {
        val unchecked = listOf(R.string.setup_device_separate_chats_if_other_app)

        assertEquals(emptyList<Int>(), setupDeviceExplanation(SetupDeviceEvidence.NoneFound))
        assertEquals(unchecked, setupDeviceExplanation(SetupDeviceEvidence.Unchecked))
        assertEquals(unchecked, setupDeviceExplanation(null))
        assertEquals(
            listOf(R.string.setup_device_link_next),
            setupDeviceExplanation(SetupDeviceEvidence.OtherApps(null)),
        )
        assertEquals(
            listOf(R.string.setup_device_separate_chats, R.string.setup_device_new_chats_either_app),
            setupDeviceExplanation(SetupDeviceEvidence.OtherApps(null), linkedDevices = false),
        )
    }

    /** The evidence for a completed notice reporting [discovery] with [publishedAt] package dates. */
    private fun evidence(
        discovery: OnboardingDeviceDiscoveryFfi,
        vararg publishedAt: ULong,
    ) = setupDeviceEvidence(setupDeviceNotice(discovery, publishedAt.toList()), NOW)

    /** The publication date a possible other installation names for [publishedAt] package dates. */
    private fun latestPublication(vararg publishedAt: ULong): Long? {
        val found = evidence(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE, *publishedAt)
        return (found as SetupDeviceEvidence.OtherApps).latestPublishedAtSeconds
    }

    private companion object {
        const val NOW = 2_000_000_000L
    }
}
