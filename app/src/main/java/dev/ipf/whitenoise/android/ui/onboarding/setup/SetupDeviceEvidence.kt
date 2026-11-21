package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingSingleDeviceNoticeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable

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

/**
 * What this sign-in means for the user's chats. Each sign-in is a separate installation with its own chats, so the
 * copy never says this device is added, linked or synced. A check that found nothing needs no explanation, and one
 * that failed or has not run makes no claim about other apps either way. A build with linked devices says instead
 * that this device can be linked after sign-in, since its chats need not stay separate there.
 */
internal fun setupDeviceExplanation(
    evidence: SetupDeviceEvidence?,
    linkedDevices: Boolean = linkedDevicesAvailable(),
): List<Int> =
    when (evidence) {
        SetupDeviceEvidence.NoneFound -> emptyList()
        SetupDeviceEvidence.OtherApps ->
            if (linkedDevices) {
                listOf(R.string.setup_device_link_next)
            } else {
                listOf(R.string.setup_device_separate_chats, R.string.setup_device_new_chats_either_app)
            }
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
