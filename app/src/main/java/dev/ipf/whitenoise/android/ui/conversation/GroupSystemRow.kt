package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupSystemEvent
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.GroupSystemLinkedSummary
import dev.ipf.whitenoise.android.core.GroupSystemSubjectLink
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.rememberGroupSystemCopy
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/** Flat inset the prototype gives a system-event row on both sides. */
private val GroupSystemRowVerticalPadding = 8.dp

/**
 * Centered one-line row for a kind-1210 group system event ("%s changed the
 * group avatar", membership changes, renames). Rendered from `system_type` +
 * `data` with display names resolved live — [WhiteNoiseAppState.displayName]
 * reads the profile revision, so the row re-renders when a name loads. An
 * unparseable payload renders the generic fallback, never the raw content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupSystemRow(
    record: AppMessageRecordFfi,
    appState: WhiteNoiseAppState,
    groupSystem: GroupSystemEventFfi? = null,
    onDeleteForMe: (() -> Unit)? = null,
    onWave: (suspend (String, () -> Unit) -> Unit)? = null,
    waveAccountRef: String? = appState.activeAccountRef,
    onOpenProfile: ((accountIdHex: String) -> Unit)? = null,
    onOpenActions: ((String, IntRect?) -> Unit)? = null,
    reactionContent: @Composable () -> Unit = {},
    loadWaveDismissal: (suspend (String) -> WaveHiDismissal)? = null,
) {
    val copy = rememberGroupSystemCopy()
    val event =
        remember(record.plaintext, record.direction, groupSystem) {
            GroupSystemEvents.resolve(record, groupSystem)
        }
    // Localized new-window label for the disappearing-timer "set to …" rows; null
    // when the event isn't a timer-on change (off/other rows need no duration).
    val retentionLabel = event?.newRetentionSeconds?.takeIf { it > 0uL }?.let { disappearingMessagesLabel(it.toLong()) }
    val selfHex = appState.activeAccount?.accountIdHex
    val target = waveTarget(event, selfHex)
    val context = LocalContext.current.applicationContext
    val waveKey =
        waveAccountRef?.takeIf { record.messageIdHex.isNotBlank() }?.let { account ->
            "${account.length}:$account:${record.groupIdHex}:${record.messageIdHex}"
        }
    val waveState =
        if (target != null && onWave != null && waveKey != null) {
            rememberWaveHiPreparation("${appState.runtimeGeneration}:$waveKey") {
                loadWaveDismissal?.invoke(waveKey) ?: loadWaveHiDismissal(context, waveKey)
            }
        } else {
            null
        }
    // Measure the complete eligible row while preparing, but expose neither text nor actions.
    // Its first visible frame has the same geometry; a local failure offers a bounded retry.
    val preparing = waveState != null && waveState.dismissal == null
    val summary =
        if (event != null) {
            val actorHex = GroupSystemEvents.actorHex(event, record.sender)
            val subjectName =
                GroupSystemEvents.preferredName(
                    event.subject?.let { appState.displayName(it) },
                    event.subjectDisplayName,
                )
            GroupSystemSubjectLink.summary(event, selfHex, subjectName) { shownSubject ->
                GroupSystemEvents.summary(
                    event = event,
                    actorName =
                        GroupSystemEvents.preferredName(
                            actorHex?.let { appState.displayName(it) },
                            event.actorDisplayName,
                        ),
                    subjectName = shownSubject,
                    actorIsSelf = GroupSystemEvents.isSelf(selfHex, actorHex),
                    subjectIsSelf = GroupSystemEvents.isSelf(selfHex, event.subject),
                    retentionLabel = retentionLabel,
                    copy = copy,
                )
            }
        } else {
            GroupSystemLinkedSummary(copy.fallback)
        }
    val summaryText =
        if (onOpenProfile == null || preparing) {
            AnnotatedString(summary.text)
        } else {
            groupSystemSummaryText(summary, MaterialTheme.colorScheme.primary) { subject ->
                // Navigation only, and only under the account this row was rendered for (#2957).
                GroupSystemSubjectLink
                    .openTarget(subject, waveAccountRef, appState.activeAccountRef)
                    ?.let(onOpenProfile)
            }
        }
    var actionMenuOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var summaryBounds by remember(record.messageIdHex) { mutableStateOf<IntRect?>(null) }
    val actionLabel = stringResource(R.string.message_actions)
    // The prototype gives every event row a flat 8.dp above and below inside a
    // full-width centred box; the transcript's own 2.dp row arrangement then
    // reads as the 18.dp the prototype leaves between adjacent events.
    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = GroupSystemRowVerticalPadding)
                    .then(if (preparing) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .widthIn(max = 440.dp)
                                .onGloballyPositioned { summaryBounds = it.boundsInWindow().roundToIntRect() }
                                .then(
                                    groupSystemActionsModifier(
                                        record.messageIdHex,
                                        !preparing && (onOpenActions != null || onDeleteForMe != null),
                                        actionLabel,
                                    ) {
                                        if (onOpenActions != null) {
                                            onOpenActions(summary.text, summaryBounds)
                                        } else {
                                            actionMenuOpen = true
                                        }
                                    },
                                ),
                    )
                    val menuScrollState = rememberScrollState()
                    DropdownMenu(
                        scrollState = menuScrollState,
                        modifier = Modifier.scrollEdgeFade(menuScrollState),
                        expanded = actionMenuOpen && !preparing,
                        onDismissRequest = { actionMenuOpen = false },
                        shape = MenuDefaults.shape,
                        border = amoledSurfaceBorderStroke(),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_for_me)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                actionMenuOpen = false
                                onDeleteForMe?.invoke()
                            },
                        )
                    }
                }
                if (target != null && onWave != null && waveState != null) {
                    WaveHiButton(appState, waveAccountRef, target, waveState, onWave)
                }
            }
            if (!preparing) reactionContent()
            // Developer-mode only: keep the one-line summary as the default and tuck
            // the MLS commit dump behind a per-row tap (#857). Saveable row-keyed UI
            // state lets an expanded row survive lazy-list disposal without leaking to others.
            if (!preparing && appState.streamingDebugEnabled) {
                var detailsExpanded by rememberSaveable(record.messageIdHex) { mutableStateOf(false) }
                val debugStyle = remember(record) { MessageDebugClassifier.debugStyle(record) }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { detailsExpanded = !detailsExpanded }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            stringResource(
                                if (detailsExpanded) {
                                    R.string.group_system_hide_details
                                } else {
                                    R.string.group_system_show_details
                                },
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (detailsExpanded) {
                    Spacer(Modifier.height(4.dp))
                    MessageDebugRow(style = debugStyle, record = record)
                }
            }
        }
        if (preparing) WaveHiPreparingRow(requireNotNull(waveState), Modifier.matchParentSize())
    }
}

@Composable
@Suppress("FunctionNaming")
private fun WaveHiPreparingRow(
    state: WaveHiPreparation,
    modifier: Modifier,
) {
    Box(modifier, Alignment.Center) {
        if (state.failed) {
            TextButton(onClick = { state.retry += 1 }) { Text(stringResource(R.string.retry)) }
        } else {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun WaveHiButton(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    target: String,
    state: WaveHiPreparation,
    onWave: suspend (String, () -> Unit) -> Unit,
) {
    val dismissal = state.dismissal
    if (dismissal == null) {
        // Disabled, transparent measurement only; no speculative greeting or tappable action.
        TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.wave_hi)) }
        return
    }
    if (dismissal.dismissed || state.accepted) return
    val runtime = appState.runtimeGeneration
    TextButton(
        enabled = !state.sending,
        onClick = wave@{
            if (state.sending || appState.activeAccountRef != accountRef || appState.runtimeGeneration != runtime) {
                return@wave
            }
            state.sending = true
            appState.launchMutation {
                try {
                    onWave(target) {
                        state.accepted = true
                        dismissal.persistDismissal()
                    }
                } finally {
                    state.sending = false
                }
            }
        },
    ) {
        Text(stringResource(R.string.wave_hi))
    }
}

/**
 * The summary as styled text: the affected member's name, when [GroupSystemSubjectLink] located it, is a
 * link that TalkBack exposes as one and that hands its authenticated account id to [onOpen].
 */
internal fun groupSystemSummaryText(
    summary: GroupSystemLinkedSummary,
    linkColor: Color,
    onOpen: (accountIdHex: String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        append(summary.text)
        val range = summary.subjectRange
        val subject = summary.subjectAccountIdHex
        if (range != null && subject != null) {
            addLink(
                LinkAnnotation.Clickable(
                    tag = GROUP_SYSTEM_SUBJECT_LINK_TAG,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)),
                ) { onOpen(subject) },
                start = range.first,
                end = range.last + 1,
            )
        }
    }

/** Annotation tag of the affected member's profile link inside a group system row. */
internal const val GROUP_SYSTEM_SUBJECT_LINK_TAG = "group-system-subject"

/** The member a "Wave hi" greets: an authenticated addition of someone other than the reader. */
private fun waveTarget(
    event: GroupSystemEvent?,
    selfAccountId: String?,
): String? {
    if (event?.fromAuthenticatedStateProjection != true || event.systemType != "member_added") {
        return null
    }
    return event.subject?.takeIf { it.isNotBlank() && !GroupSystemEvents.isSelf(selfAccountId, it) }
}

/** Gives touch, TalkBack and keyboard a single named long-press action without affecting profile links. */
private fun groupSystemActionsModifier(
    messageId: String,
    actionable: Boolean,
    label: String,
    onOpen: () -> Unit,
): Modifier =
    if (actionable && messageId.isNotBlank()) {
        Modifier.combinedClickable(onClick = {}, onLongClickLabel = label, onLongClick = onOpen)
    } else {
        Modifier
    }
