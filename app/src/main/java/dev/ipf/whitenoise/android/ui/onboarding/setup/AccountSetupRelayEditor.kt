package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingRelayRepairFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.marmotkit.OnboardingStepFfi

internal val relaySetupSteps = setOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS)
internal val relaySetupActions = setOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS, OnboardingActionFfi.EDIT_RELAYS)

/** Projects exact native endpoint strings; no endpoint classification or route selection happens here. */
internal fun SetupEditor.withRelayDeclaration(repair: OnboardingRelayRepairFfi): SetupEditor {
    val reads =
        repair.beforeTags
            .filter { it.role in relayReadRoles }
            .mapNotNull { it.endpoint }
            .joinToString("\n")
    val writes =
        repair.beforeTags
            .filter { it.role in relayWriteRoles }
            .mapNotNull { it.endpoint }
            .joinToString("\n")
    return copy(
        reads = reads,
        writes = writes,
        originalReads = reads,
        originalWrites = writes,
        relayDeclaration = repair,
    )
}

private val relayReadRoles =
    setOf(OnboardingRelayTagRoleFfi.READ, OnboardingRelayTagRoleFfi.UNMARKED, OnboardingRelayTagRoleFfi.INBOX)
private val relayWriteRoles = setOf(OnboardingRelayTagRoleFfi.WRITE, OnboardingRelayTagRoleFfi.UNMARKED)

/** MDK preserves raw tags; only endpoint text that cannot round-trip through line fields is read-only. */
internal val SetupEditor.canEditRelayDeclaration: Boolean
    get() {
        val tags = relayDeclaration?.beforeTags ?: return false
        return tags.filter { it.role != OnboardingRelayTagRoleFfi.OTHER }.all { tag ->
            val endpoint = tag.endpoint ?: return@all false
            endpoint.isNotBlank() && endpoint == endpoint.trim() && '\n' !in endpoint && '\r' !in endpoint
        }
    }

/** Compares submitted lines so whitespace-only edits cannot create a no-op preview; MDK validates endpoints. */
internal val SetupEditor.canReviewRelayEdit: Boolean
    get() {
        if (!canEditRelayDeclaration) return false
        val edited = request()
        val original = copy(reads = originalReads, writes = originalWrites).request()
        val requiredRelays = if (step == OnboardingStepFfi.INBOX_RELAYS) edited.readRelays else edited.writeRelays
        return requiredRelays.isNotEmpty() &&
            (edited.readRelays != original.readRelays || edited.writeRelays != original.writeRelays)
    }
