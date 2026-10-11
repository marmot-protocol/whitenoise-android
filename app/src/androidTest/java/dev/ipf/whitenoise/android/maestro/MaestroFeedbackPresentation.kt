package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.chats.ChatRelayFeedbackDialog
import dev.ipf.whitenoise.android.ui.profile.AddProfileFeedbackDialog
import dev.ipf.whitenoise.android.ui.profile.PersonProfileFeedbackDialog

/** Error and informational notices use synthetic diagnostics only. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroFeedbackPresentation(fixture: MaestroPresentationFixture) {
    val copyable = fixture.scenario.endsWith("copyable")
    val feedback =
        ToastMessage(
            title = AppText.Plain("Maestro presentation feedback"),
            detail = AppText.Plain("Synthetic account presentation detail"),
            copyable = copyable,
            diagnosticReport = "Synthetic diagnostic report",
        )
    when {
        fixture.scenario.startsWith("feedback-chat") ->
            ChatRelayFeedbackDialog(feedback, onDismiss = { fixture.finish("dismiss") })
        fixture.scenario.startsWith("feedback-add") ->
            AddProfileFeedbackDialog(feedback) { fixture.finish("dismiss") }
        else -> PersonProfileFeedbackDialog(feedback, onDismiss = { fixture.finish("dismiss") })
    }
}
