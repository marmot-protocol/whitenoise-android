package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupSystemEvent
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.rememberGroupSystemCopy
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) {
    val copy = rememberGroupSystemCopy()
    val event =
        remember(record.plaintext, record.direction, groupSystem) {
            GroupSystemEvents.resolve(record, groupSystem)
        }
    // Localized new-window label for the disappearing-timer "set to …" rows; null
    // when the event isn't a timer-on change (off/other rows need no duration).
    val retentionLabel = event?.newRetentionSeconds?.takeIf { it > 0uL }?.let { disappearingMessagesLabel(it.toLong()) }
    val summary =
        if (event != null) {
            run {
                val selfHex = appState.activeAccount?.accountIdHex
                val actorHex = GroupSystemEvents.actorHex(event, record.sender)
                GroupSystemEvents.summary(
                    event = event,
                    actorName =
                        GroupSystemEvents.preferredName(
                            actorHex?.let { appState.displayName(it) },
                            event.actorDisplayName,
                        ),
                    subjectName =
                        GroupSystemEvents.preferredName(
                            event.subject?.let { appState.displayName(it) },
                            event.subjectDisplayName,
                        ),
                    actorIsSelf = GroupSystemEvents.isSelf(selfHex, actorHex),
                    subjectIsSelf = GroupSystemEvents.isSelf(selfHex, event.subject),
                    retentionLabel = retentionLabel,
                    copy = copy,
                )
            }
        } else {
            copy.fallback
        }
    var actionMenuOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    // The prototype gives every event row a flat 8.dp above and below inside a
    // full-width centred box; the transcript's own 2.dp row arrangement then
    // reads as the 18.dp the prototype leaves between adjacent events.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = GroupSystemRowVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .widthIn(max = 440.dp)
                            .then(
                                if (onDeleteForMe != null && record.messageIdHex.isNotBlank()) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { actionMenuOpen = true },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                )
                DropdownMenu(
                    expanded = actionMenuOpen,
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
            val waveTarget = waveTarget(event, appState.activeAccount?.accountIdHex)
            if (waveTarget != null && onWave != null) {
                WaveHiButton(record, appState, waveAccountRef, waveTarget, onWave)
            }
        }
        // Developer-mode only: keep the one-line summary as the default and tuck
        // the MLS commit dump behind a per-row tap (#857). Saveable row-keyed UI
        // state lets an expanded row survive lazy-list disposal without leaking to others.
        if (appState.streamingDebugEnabled) {
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
}

@Composable
@Suppress("FunctionNaming")
private fun WaveHiButton(
    record: AppMessageRecordFfi,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    target: String,
    onWave: suspend (String, () -> Unit) -> Unit,
) {
    if (accountRef == null || record.messageIdHex.isBlank()) {
        return
    }
    val context = LocalContext.current.applicationContext
    val key = "${accountRef.length}:$accountRef:${record.groupIdHex}:${record.messageIdHex}"
    var preferences by remember(key) { mutableStateOf<SharedPreferences?>(null) }
    var dismissed by remember(key) { mutableStateOf(true) }
    var sending by remember(key) { mutableStateOf(false) }
    LaunchedEffect(context, key) {
        // This is a local action dismissal, like Delete for me, not message or delivery data.
        val stored =
            withContext(Dispatchers.IO) {
                context.getSharedPreferences("whitenoise.wave_dismissals", Context.MODE_PRIVATE).let {
                    it to it.getBoolean(key, false)
                }
            }
        preferences = stored.first
        dismissed = stored.second
    }
    if (dismissed) {
        return
    }
    TextButton(
        enabled = !sending,
        onClick = wave@{
            if (sending) {
                return@wave
            }
            sending = true
            appState.launchMutation {
                try {
                    onWave(target) {
                        dismissed = true
                        requireNotNull(preferences).edit().putBoolean(key, true).apply()
                    }
                } finally {
                    sending = false
                }
            }
        },
    ) {
        Text(stringResource(R.string.wave_hi))
    }
}

private fun waveTarget(
    event: GroupSystemEvent?,
    selfAccountId: String?,
): String? {
    if (event?.fromAuthenticatedStateProjection != true || event.systemType != "member_added") {
        return null
    }
    return event.subject?.takeIf { it.isNotBlank() && !GroupSystemEvents.isSelf(selfAccountId, it) }
}
