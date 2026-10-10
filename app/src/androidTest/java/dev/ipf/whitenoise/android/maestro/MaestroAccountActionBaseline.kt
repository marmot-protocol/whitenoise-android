package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi

/** Native state before a real generated-account sign-out/wipe, never a fabricated mutation result. */
internal data class MaestroAccountActionBaseline(
    val accounts: List<AccountSummaryFfi>,
    val owner: String,
    val group: String,
    val messages: Map<String, List<MaestroSharedMessageSnapshot>>,
    val drafts: Map<String, MessageDraftFfi?>,
    val profiles: Map<String, UserProfileMetadataFfi?>,
)
