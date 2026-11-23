package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The device step's evidence and explanation follow MDK's discovery. */
class SetupDeviceEvidenceTest {
    /** Each discovery maps to its evidence, and no notice means MDK has not checked yet. */
    @Test
    fun eachDiscoveryMapsToItsEvidence() {
        assertNull(setupDeviceEvidence(null))
        assertEquals(SetupDeviceEvidence.NoneFound, evidence(OnboardingDeviceDiscoveryFfi.NONE_FOUND))
        assertEquals(SetupDeviceEvidence.Unchecked, evidence(OnboardingDeviceDiscoveryFfi.UNKNOWN))
        assertEquals(
            SetupDeviceEvidence.OtherApps,
            evidence(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE),
        )
    }

    /** Publication timestamps of any age describe the record, so the sign-in finding stays the same. */
    @Test
    fun publicationTimeDoesNotChangeTheFinding() {
        for (published in listOf(0uL, 1_700_000_000uL, ULong.MAX_VALUE)) {
            val finding = evidence(OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE, published)
            assertEquals(SetupDeviceEvidence.OtherApps, finding)
        }
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
            setupDeviceExplanation(SetupDeviceEvidence.OtherApps),
        )
    }

    /** The evidence for a completed notice reporting [discovery] with [publishedAt] package dates. */
    private fun evidence(
        discovery: OnboardingDeviceDiscoveryFfi,
        vararg publishedAt: ULong,
    ) = setupDeviceEvidence(setupDeviceNotice(discovery, publishedAt.toList()))
}
