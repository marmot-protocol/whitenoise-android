package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.SetupRequest

/** Complete synthetic metadata makes loss of fields outside the editor observable. */
private val setupProfileMetadata =
    UserProfileMetadataFfi(
        "Maestro",
        "Maestro setup",
        "Setup detail",
        "https://fixture.example.invalid/site",
        "https://fixture.example.invalid/avatar.png",
        "fixture@example.invalid",
        "fixture@example.invalid",
    )

/** The actual controller loads existing metadata or retains its load-error state. */
internal fun maestroSetupProfile(fixture: MaestroPresentationFixture): UserProfileMetadataFfi {
    fixture.record("profile-loaded")
    if (fixture.scenario == "setup-profile-edit-failure") error("Synthetic profile load failure")
    return setupProfileMetadata
}

/** Checks the request emitted by production editors; no native profile or relay publication is performed. */
internal fun maestroSetupEditorRequest(
    fixture: MaestroPresentationFixture,
    request: SetupRequest,
): Boolean =
    when (request.action) {
        OnboardingActionFfi.EDIT_PROFILE -> {
            check(request.revision == 3uL && request.step == OnboardingStepFfi.PROFILE)
            val expected =
                if (fixture.scenario.endsWith("suggest")) {
                    setupProfileMetadata.copy(displayName = "Maestro setup name")
                } else {
                    setupProfileMetadata
                }
            check(request.profile == expected && request.readRelays.isEmpty() && request.writeRelays.isEmpty())
            fixture.finish("profile-review-requested")
            true
        }
        OnboardingActionFfi.EDIT_DISCOVERY_RELAYS -> {
            check(request.revision == 3uL && request.step == OnboardingStepFfi.INBOX_RELAYS)
            check(request.writeRelays.isEmpty())
            if (fixture.scenario.endsWith("failure")) {
                check(request.readRelays.isEmpty())
                fixture.record("discovery-request-refused")
                error("Synthetic discovery check failure")
            }
            check(request.readRelays == listOf("wss://discovery.example.invalid/"))
            fixture.finish("discovery-check-requested")
            true
        }
        else -> false
    }
