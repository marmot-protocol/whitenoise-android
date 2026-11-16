package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckOutcomeFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingDevicePackageFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayCapabilityFfi
import dev.ipf.marmotkit.OnboardingSingleDeviceNoticeFfi

/** Legacy observations stay unknown; tests must explicitly supply native evidence and per-finding grants. */
@Suppress("LongParameterList") // Explicit native evidence stays independent in test records.
internal fun setupFinding(
    issue: OnboardingIssueFfi,
    endpoint: String?,
    actions: List<OnboardingActionFfi> = emptyList(),
    context: OnboardingFindingContextFfi = OnboardingFindingContextFfi.UNKNOWN,
    purpose: OnboardingCheckPurposeFfi = OnboardingCheckPurposeFfi.UNKNOWN,
    outcome: OnboardingCheckOutcomeFfi = OnboardingCheckOutcomeFfi.UNKNOWN,
    capability: OnboardingRelayCapabilityFfi = OnboardingRelayCapabilityFfi.NONE,
    observedRevision: ULong? = null,
): OnboardingFindingFfi =
    OnboardingFindingFfi(issue, endpoint, context, purpose, outcome, capability, actions, observedRevision)

/** A completed device notice reporting [discovery], with one other package published at each of [publishedAt]. */
internal fun setupDeviceNotice(
    discovery: OnboardingDeviceDiscoveryFfi,
    publishedAt: List<ULong> = emptyList(),
): OnboardingSingleDeviceNoticeFfi =
    OnboardingSingleDeviceNoticeFfi(
        discovery = discovery,
        otherPackages =
            publishedAt.mapIndexed { index, seconds ->
                OnboardingDevicePackageFfi(
                    slotId = "slot-$index",
                    keyPackageRefHex = null,
                    eventIdHex = index.toString().padStart(64, '0'),
                    publishedAt = seconds,
                    expiresAt = null,
                    usable = true,
                )
            },
        discoveryComplete = true,
        acknowledgedAt = null,
    )
