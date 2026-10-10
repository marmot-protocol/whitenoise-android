package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.EditVersion
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.GroupSystemSummaryMenu
import dev.ipf.whitenoise.android.ui.conversation.composer.MentionPicker
import dev.ipf.whitenoise.android.ui.conversation.media.PendingStatusOverlay
import dev.ipf.whitenoise.android.ui.conversation.messages.EditHistoryDialog
import dev.ipf.whitenoise.android.ui.conversation.messages.MediaFooterOverlay

/** Production message chrome consumes deterministic state without publishing, deleting or transferring messages. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroMessagePresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("extra-message-history-") -> HistoryPresentation(fixture)
        fixture.scenario.startsWith("extra-message-summary-") ->
            Box(Modifier.size(48.dp)) {
                GroupSystemSummaryMenu(
                    expanded = true,
                    onDismiss = { fixture.finish("dismiss") },
                    onDelete = { fixture.finish("delete-handoff") },
                )
            }
        fixture.scenario.startsWith("extra-message-mention-") -> MentionPresentation(fixture)
        fixture.scenario.startsWith("extra-message-pending-") ->
            PendingStatusOverlay(
                failed = !fixture.scenario.endsWith("loading"),
                hasPreview = false,
                statusLabel = "Fixture attachment status",
                statusColor = MaterialTheme.colorScheme.error,
                onRetry = if (fixture.scenario.endsWith("retry")) { { fixture.finish("retry-handoff") } } else null,
            )
        else ->
            Box(Modifier.size(240.dp, 160.dp)) {
                MediaFooterOverlay(timeText = "12:34", showStatus = true, status = MessageStatus.Sent)
            }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun HistoryPresentation(fixture: MaestroPresentationFixture) {
    val empty = fixture.scenario.endsWith("empty")
    val versions =
        if (empty) emptyList() else listOf(EditVersion("fixture-revision", "Fixture revision text", 1_800_000_000u))
    EditHistoryDialog(
        original = if (empty) null else "Fixture original text",
        originalTimestamp = 1_799_999_900u,
        editState = EditState(if (empty) "" else "Fixture revision text", versions.size, versions),
        onDismissRequest = { fixture.finish("dismiss") },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun MentionPresentation(fixture: MaestroPresentationFixture) {
    val account = fixture.appState.accounts[1]
    val candidate =
        MentionComposer.Candidate(
            accountIdHex = account.accountIdHex,
            npub = fixture.appState.npubForDisplay(account.accountIdHex),
            displayName = "Maestro Bob",
        )
    MentionPicker(
        candidates = listOf(candidate),
        onPick = {
            check(it == candidate)
            fixture.finish("mention-selected")
        },
    )
}
