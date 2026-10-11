package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.conversation.GroupRecoveryCard
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateCustomStage
import dev.ipf.whitenoise.android.ui.search.GlobalSearchDateFilterDialog
import java.time.LocalDate
import java.time.ZoneOffset

/** Reviewed native-shaped state is injected only at the production presentation boundary. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroRecoveryPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("surface-rejoin-")) {
        val invitation =
            GroupRejoinInvitationFfi(
                welcomeIdHex = "fixture-welcome",
                welcomerAccountIdHex = "fixture-inviter",
                epoch = 9u,
                localStateToken = "fixture-local-token",
            )
        GroupRecoveryCard(
            status =
                GroupRecoveryStatusFfi(
                    groupIdHex = "fixture-group",
                    automaticRecoveryFailed = false,
                    pendingReinvites = 0u,
                    failedReinvites = 0u,
                    rejoinInvitations = listOf(invitation),
                ),
            busy = false,
            inviterName = { "Fixture inviter" },
            inviterIdentity = { "Fixture inviter identity" },
            onConfirm = {
                check(it == invitation)
                fixture.finish("rejoin-confirmed")
            },
            onDecline = {
                check(it == invitation)
                fixture.finish("rejoin-declined")
            },
        )
    } else {
        val from = LocalDate.of(2027, 1, 2)
        val to = if (fixture.scenario.endsWith("reversed")) from.minusDays(1) else from.plusDays(2)
        GlobalSearchDateFilterDialog(
            selection = GlobalSearchDateFilterSelection.AnyTime,
            customStage = GlobalSearchDateCustomStage.Review(from, to, ZoneOffset.UTC),
            onDismiss = { fixture.finish("dismiss") },
            onCustomStageChange = {
                check(it == null)
                fixture.finish("dismiss")
            },
            onApply = {
                check(it == GlobalSearchDateFilterSelection.Custom(from, to, ZoneOffset.UTC) && to >= from)
                fixture.finish("date-applied")
            },
        )
    }
}
