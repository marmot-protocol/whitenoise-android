package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingCheckOutcomeFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingSingleDeviceNoticeFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R

/** What MDK's device check found, which the "Chats on this device" step states before explaining what it means. */
internal sealed interface SetupDeviceEvidence {
    /** No messaging keys from another app or device were found, which does not prove there is none. */
    data object NoneFound : SetupDeviceEvidence

    /** The check could not tell whether this account uses another app or device for messaging. */
    data object Unchecked : SetupDeviceEvidence

    /** A record associated with the account was published outside this installation. */
    data object OtherApps : SetupDeviceEvidence
}

/** Classifies the sign-in evidence without treating a record’s publication time as the time of a sign-in. */
internal fun setupDeviceEvidence(notice: OnboardingSingleDeviceNoticeFfi?): SetupDeviceEvidence? =
    when (notice?.discovery) {
        null -> null
        OnboardingDeviceDiscoveryFfi.NONE_FOUND -> SetupDeviceEvidence.NoneFound
        OnboardingDeviceDiscoveryFfi.UNKNOWN -> SetupDeviceEvidence.Unchecked
        OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE -> SetupDeviceEvidence.OtherApps
    }

/** The sign-in check offers linking when it found another installation. */
internal fun setupDeviceExplanation(evidence: SetupDeviceEvidence?): List<Int> =
    when (evidence) {
        SetupDeviceEvidence.NoneFound -> emptyList()
        SetupDeviceEvidence.OtherApps -> listOf(R.string.setup_device_link_next)
        SetupDeviceEvidence.Unchecked, null -> listOf(R.string.setup_device_separate_chats_if_other_app)
    }

/** The sign-in finding shown for this discovery. */
@Composable
internal fun setupDeviceFinding(evidence: SetupDeviceEvidence): String =
    when (evidence) {
        SetupDeviceEvidence.NoneFound -> stringResource(R.string.setup_device_none_found)
        SetupDeviceEvidence.Unchecked -> stringResource(R.string.setup_device_unchecked)
        SetupDeviceEvidence.OtherApps -> stringResource(R.string.setup_device_found)
    }

/** Keep a record’s endpoint and diagnostic evidence when the device notice already states its finding. */
internal fun OnboardingSnapshotFfi.deviceNoticeCovers(
    step: OnboardingStepFfi?,
    finding: OnboardingFindingFfi,
): Boolean =
    step == OnboardingStepFfi.SINGLE_DEVICE &&
        finding.issue == OnboardingIssueFfi.OTHER_INSTALLATION_POSSIBLE &&
        setupDeviceEvidence(singleDeviceNotice) == SetupDeviceEvidence.OtherApps

/** A finding can carry provenance even when it has no endpoint or separate title. */
internal fun OnboardingFindingFfi.hasDiagnosticEvidence(): Boolean =
    context != OnboardingFindingContextFfi.UNKNOWN ||
        purpose != OnboardingCheckPurposeFfi.UNKNOWN ||
        outcome != OnboardingCheckOutcomeFfi.UNKNOWN
