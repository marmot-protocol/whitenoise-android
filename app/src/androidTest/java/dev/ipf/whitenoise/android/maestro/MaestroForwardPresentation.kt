package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.ForwardFailureStage
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.ForwardTargetPhase
import dev.ipf.whitenoise.android.state.ForwardTargetProgress
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardOperationStatus

/** The production forwarding parent opens its details sheet with bounded failure/running state. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroForwardPresentation(fixture: MaestroPresentationFixture) {
    val running = fixture.scenario.endsWith("cancel")
    ForwardOperationStatus(
        snapshot =
            ForwardOperationSnapshot(
                phase = if (running) ForwardOperationPhase.Running else ForwardOperationPhase.Failed,
                preparedAttachments = 0,
                totalAttachments = 1,
                targets =
                    listOf(
                        ForwardTargetProgress(
                            groupIdHex = "fixture-target",
                            phase = if (running) ForwardTargetPhase.Waiting else ForwardTargetPhase.Failed,
                            totalAttachments = 1,
                            totalMessages = 1,
                            failureStage = if (running) null else ForwardFailureStage.Materialize,
                        ),
                    ),
            ),
        targetTitles = mapOf("fixture-target" to "Fixture forwarding target"),
        onCancel = { fixture.finish("cancel-handoff") },
        onRetry = { fixture.finish("retry-handoff") },
        onDismiss = { fixture.finish("dismiss-handoff") },
    )
}
