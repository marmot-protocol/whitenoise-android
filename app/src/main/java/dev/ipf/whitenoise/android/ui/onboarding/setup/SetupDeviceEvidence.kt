package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingSingleDeviceNoticeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import java.time.format.FormatStyle

/** What MDK's device check found, which the "Chats on this device" step states before explaining what it means. */
internal sealed interface SetupDeviceEvidence {
    /** No messaging keys from another app or device were found, which does not prove there is none. */
    data object NoneFound : SetupDeviceEvidence

    /** The check could not tell whether this account uses another app or device for messaging. */
    data object Unchecked : SetupDeviceEvidence

    /** Another installation published messaging keys, last at [latestPublishedAtSeconds] when that is known. */
    data class OtherApps(
        val latestPublishedAtSeconds: Long?,
    ) : SetupDeviceEvidence
}

/**
 * The evidence in MDK's device [notice], or null before MDK has checked. Relays supply the publication dates, so a
 * missing (zero) date and one after [nowSeconds] are skipped rather than shown.
 */
internal fun setupDeviceEvidence(
    notice: OnboardingSingleDeviceNoticeFfi?,
    nowSeconds: Long,
): SetupDeviceEvidence? =
    when (notice?.discovery) {
        null -> null
        OnboardingDeviceDiscoveryFfi.NONE_FOUND -> SetupDeviceEvidence.NoneFound
        OnboardingDeviceDiscoveryFfi.UNKNOWN -> SetupDeviceEvidence.Unchecked
        OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE -> {
            val published = notice.otherPackages.map { it.publishedAt }.filter { it in 1uL..nowSeconds.toULong() }
            SetupDeviceEvidence.OtherApps(published.maxOrNull()?.toLong())
        }
    }

/** Whether the evidence names the day another installation last published, which MDK's own finding never does. */
internal fun SetupDeviceEvidence?.namesPublicationDate(): Boolean {
    val otherApps = this as? SetupDeviceEvidence.OtherApps
    return otherApps?.latestPublishedAtSeconds != null
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
        is SetupDeviceEvidence.OtherApps ->
            if (linkedDevices) {
                listOf(R.string.setup_device_link_next)
            } else {
                listOf(R.string.setup_device_separate_chats, R.string.setup_device_new_chats_either_app)
            }
        SetupDeviceEvidence.Unchecked, null -> listOf(R.string.setup_device_separate_chats_if_other_app)
    }

/** The sentence stating [evidence], naming the latest publication as a long local date when one is known. */
@Composable
internal fun setupDeviceFinding(evidence: SetupDeviceEvidence): String =
    when (evidence) {
        SetupDeviceEvidence.NoneFound -> stringResource(R.string.setup_device_none_found)
        SetupDeviceEvidence.Unchecked -> stringResource(R.string.setup_device_unchecked)
        is SetupDeviceEvidence.OtherApps ->
            evidence.latestPublishedAtSeconds?.let {
                stringResource(R.string.setup_device_found_on_date, longLocalDate(it))
            } ?: stringResource(R.string.setup_device_found)
    }

/** [epochSeconds] as the long date in the device's time zone and locale. */
private fun longLocalDate(epochSeconds: Long): String {
    val stamp = epochSeconds.toULong()
    return IdentityFormatter.dateTime(stamp, dateStyle = FormatStyle.LONG, timeStyle = null)
}
