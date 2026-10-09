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

/** The legacy list editor cannot round-trip mixed/raw tags. Keep those declarations read-only. */
internal val SetupEditor.preservesRelayDeclaration: Boolean
    get() {
        val tags = relayDeclaration?.beforeTags ?: return false
        if (tags.isEmpty()) return true
        val role = tags.first().role
        if (role == OnboardingRelayTagRoleFfi.OTHER || tags.any { it.role != role }) return false
        if (tags.map { it.endpoint }.distinct().size != tags.size) return false
        return tags.all { tag ->
            val endpoint = tag.endpoint ?: return@all false
            val expected =
                when (role) {
                    OnboardingRelayTagRoleFfi.INBOX -> listOf("relay", endpoint)
                    OnboardingRelayTagRoleFfi.UNMARKED -> listOf("r", endpoint)
                    OnboardingRelayTagRoleFfi.READ -> listOf("r", endpoint, "read")
                    OnboardingRelayTagRoleFfi.WRITE -> listOf("r", endpoint, "write")
                    OnboardingRelayTagRoleFfi.OTHER -> emptyList()
                }
            tag.fields == expected && endpoint == endpoint.trim() && '\n' !in endpoint && '\r' !in endpoint
        }
    }

/** Prevents accidental empty or unchanged replacement; MDK remains the endpoint-validation authority. */
internal val SetupEditor.canReviewRelayEdit: Boolean
    get() =
        preservesRelayDeclaration &&
            (reads != originalReads || writes != originalWrites) &&
            (if (step == OnboardingStepFfi.INBOX_RELAYS) reads.isNotBlank() else writes.isNotBlank())
