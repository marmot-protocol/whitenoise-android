package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.REPORT_EXPLANATION_LIMIT
import dev.ipf.whitenoise.android.state.REPORT_REASONS
import dev.ipf.whitenoise.android.state.boundedExplanation
import dev.ipf.whitenoise.android.state.reportReasonLabel
import dev.ipf.whitenoise.android.ui.common.ProseTextField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetInset
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion

internal const val REPORT_SHEET_TEST_TAG = "message.report"
internal const val REPORT_BODY_TEST_TAG = "message.report.body"
internal const val REPORT_TEAM_TOGGLE_TEST_TAG = "message.report.team"
internal const val REPORT_ERROR_TEST_TAG = "message.report.error"

/** Who a report goes to, which sets the sheet's title, what it says is shared and whether the team copy can be off. */
internal enum class ReportAudience {
    /** The group's admins only, because this build cannot reach the White Noise team. */
    Group,

    /** The group's admins, plus the White Noise team unless the reader turns that copy off. */
    GroupAndTeam,

    /** Only the White Noise team, about a message's sender, as in a direct chat. */
    TeamMessage,

    /** Only the White Noise team, about the person whose profile is open. */
    TeamProfile,
}

/** One report form's sending state: [locked] keeps the reason and copy fixed once the group has the report. */
internal data class ReportFormState(
    val sending: Boolean = false,
    val locked: Boolean = false,
    @param:StringRes val error: Int? = null,
)

/**
 * Reports one message, or one person, to the [audience]. The reader picks a reason and may add an explanation. The
 * group copy is published encrypted to the group, so the copy says so, because a group report is not anonymous. The
 * White Noise team gets only the reported key, reason and explanation, and [sendToTeam] turns its copy of a group
 * report on or off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ReportMessageSheet(
    onDismissRequest: () -> Unit,
    onSubmit: (ReportReasonFfi, String, () -> Unit) -> Unit,
    sending: Boolean = false,
    ownerKey: Any? = Unit,
    audience: ReportAudience = ReportAudience.Group,
    sendToTeam: Boolean = true,
    onSendToTeamChange: (Boolean) -> Unit = {},
    form: ReportFormState = ReportFormState(sending),
) {
    var reason by remember { mutableStateOf(REPORT_REASONS.first()) }
    var explanation by remember { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheet, ownerKey)
    var closingAudience by remember(terminal) { mutableStateOf<ReportAudience?>(null) }
    val close = {
        closingAudience = audience
        terminal.close(onDismissRequest)
    }
    WhiteNoiseModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheet,
        interactionEnabled = terminal.acceptsInput,
        modifier = Modifier.testTag(REPORT_SHEET_TEST_TAG),
        dragHandle = { ReportMessageDragHandle() },
    ) {
        ReportMessageForm(
            reason,
            { reason = it },
            explanation,
            { explanation = it },
            form.sending || !terminal.acceptsInput,
            { acceptedReason, acceptedExplanation ->
                onSubmit(acceptedReason, acceptedExplanation, close)
            },
            ReportTeamChoice(closingAudience ?: audience, sendToTeam, onSendToTeamChange),
            form,
        )
    }
}

/** The White Noise team's part of a report form: who receives it, and whether a group report sends it a copy. */
internal data class ReportTeamChoice(
    val audience: ReportAudience = ReportAudience.Group,
    val sendToTeam: Boolean = true,
    val onSendToTeamChange: (Boolean) -> Unit = {},
) {
    /** Whether the White Noise team receives this report as the form stands. */
    val reachesTeam: Boolean
        get() =
            when (audience) {
                ReportAudience.Group -> false
                ReportAudience.GroupAndTeam -> sendToTeam
                ReportAudience.TeamMessage, ReportAudience.TeamProfile -> true
            }

    /** Whether the group's admins receive this report. */
    val reachesGroup: Boolean
        get() = audience == ReportAudience.Group || audience == ReportAudience.GroupAndTeam
}

/** Retains Material's drag actions while leaving a usable body when a tall keyboard crowds the window. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun ReportMessageDragHandle() {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val availableHeight =
        with(density) {
            (windowHeight - insets.getTop(this) - insets.getBottom(this)).coerceAtLeast(0).toDp()
        }
    if (availableHeight < 240.dp * density.fontScale) {
        val dismissLabel = stringResource(R.string.dismiss)
        Surface(
            modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = dismissLabel },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(Modifier.size(width = 32.dp, height = 4.dp))
        }
    } else {
        BottomSheetDefaults.DragHandle()
    }
}

/**
 * Measures the action first, then lets only the form body scroll within the sheet's inset-aware viewport. A failure
 * in [form] ends the scrolling body and is scrolled into view, so even a short sheet keeps Send's full height.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun ReportMessageForm(
    reason: ReportReasonFfi,
    onReasonChange: (ReportReasonFfi) -> Unit,
    explanation: String,
    onExplanationChange: (String) -> Unit,
    sending: Boolean,
    onSubmit: (ReportReasonFfi, String) -> Unit,
    team: ReportTeamChoice = ReportTeamChoice(),
    form: ReportFormState = ReportFormState(sending),
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxHeight < 160.dp * LocalDensity.current.fontScale
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (compact) 4.dp else WhiteNoiseSheetInset),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp),
        ) {
            ReportMessageBody(
                reason,
                onReasonChange,
                explanation,
                onExplanationChange,
                Modifier.weight(1f, fill = false),
                enabled = !sending && !form.locked,
                team = team,
                error = form.error,
            )
            WhiteNoiseButton(
                onClick = { onSubmit(reason, boundedExplanation(explanation)) },
                enabled = !sending && (team.reachesGroup || team.reachesTeam),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WhiteNoiseSheetInset)
                        .testTag("message.report.send"),
            ) {
                Text(stringResource(R.string.report_message_send))
            }
        }
    }
}

/**
 * Scroll ownership stays in the body while the outer form measures its Send action independently. A report failure
 * ends the body and is scrolled into view when it appears.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun ReportMessageBody(
    reason: ReportReasonFfi,
    onReasonChange: (ReportReasonFfi) -> Unit,
    explanation: String,
    onExplanationChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    team: ReportTeamChoice,
    @StringRes error: Int?,
) {
    val teamOnly = !team.reachesGroup
    val scroll = rememberScrollState()
    // The failure sits at the end of the scrolling body, so it never squeezes Send, and is scrolled into view.
    LaunchedEffect(error) { if (error != null) scroll.animateScrollTo(scroll.maxValue) }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(scroll).testTag(REPORT_BODY_TEST_TAG),
    ) {
        val profile = team.audience == ReportAudience.TeamProfile
        val title = if (profile) R.string.report_team_action else R.string.report_message_title
        WhiteNoiseSheetHeader(stringResource(title))
        Column(
            Modifier.padding(horizontal = WhiteNoiseSheetInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (team.reachesGroup) ReportDetail(R.string.report_message_detail)
            Column(Modifier.selectableGroup()) {
                REPORT_REASONS.forEach { candidate ->
                    ReportReasonRow(
                        label = stringResource(reportReasonLabel(candidate)),
                        selected = candidate == reason,
                        enabled = enabled,
                        onSelect = { onReasonChange(candidate) },
                    )
                }
            }
            ProseTextField(
                value = explanation,
                enabled = enabled,
                emojiMaxLength = REPORT_EXPLANATION_LIMIT,
                onValueChange = { onExplanationChange(it.take(REPORT_EXPLANATION_LIMIT)) },
                label = {
                    val hint =
                        if (teamOnly) {
                        R.string.report_team_explanation_hint
                    } else {
                        R.string.report_message_explanation_hint
                    }
                    Text(stringResource(hint))
                },
                modifier = Modifier.fillMaxWidth().testTag("message.report.explanation"),
                singleLine = false,
                minLines = 2,
            )
            ReportTeamSection(team, enabled)
            error?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(REPORT_ERROR_TEST_TAG),
                )
            }
        }
    }
}

/** A group report's "Send to White Noise" switch, then what the team receives whenever it does. */
@Suppress("FunctionNaming")
@Composable
private fun ReportTeamSection(
    team: ReportTeamChoice,
    enabled: Boolean,
) {
    if (team.audience == ReportAudience.GroupAndTeam) {
        androidx.compose.foundation.layout.Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = team.sendToTeam,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = team.onSendToTeamChange,
                    ).testTag(REPORT_TEAM_TOGGLE_TEST_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.report_team_send_toggle),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = team.sendToTeam, onCheckedChange = null, enabled = enabled)
        }
    }
    if (team.reachesTeam) {
        val profile = team.audience == ReportAudience.TeamProfile
        ReportDetail(if (profile) R.string.report_team_profile_detail else R.string.report_team_message_detail)
    }
}

/** One line of secondary copy saying who receives the report and what they learn. */
@Suppress("FunctionNaming")
@Composable
private fun ReportDetail(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One reason choice; the whole row is the target so the label is as tappable as the button. */
@Suppress("FunctionNaming")
@Composable
private fun ReportReasonRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
