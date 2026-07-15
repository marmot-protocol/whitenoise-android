package dev.ipf.whitenoise.android.ui.conversation

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.rememberGroupSystemCopy
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.theme.AppDivider
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
internal fun UnreadMessagesDivider(count: Int) {
    val text = pluralStringResource(R.plurals.unread_messages_count, count, count)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppDivider(
            modifier = Modifier.weight(1f),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        AppDivider(
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun differentDay(
    a: ULong,
    b: ULong,
): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochSecond(a.toLong()).atZone(zone).toLocalDate() !=
        Instant.ofEpochSecond(b.toLong()).atZone(zone).toLocalDate()
}

// Today/Yesterday, then weekday within a week, then a locale-medium date —
// all sourced from the platform so the ribbon needs no new translation keys.
internal fun messageDayLabel(
    epochSeconds: ULong,
    locale: Locale,
): String {
    if (epochSeconds == 0uL) return ""
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(zone))
    return when {
        days <= 0L || days == 1L ->
            DateUtils
                .getRelativeTimeSpanString(
                    epochSeconds.toLong() * 1000L,
                    System.currentTimeMillis(),
                    DateUtils.DAY_IN_MILLIS,
                ).toString()
        days in 2L..6L -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

@Composable
internal fun DaySeparator(label: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

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
                    actorName = actorHex?.let { appState.displayName(it) },
                    subjectName = event.subject?.let { appState.displayName(it) },
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
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .then(
                            if (onDeleteForMe != null && record.messageIdHex.isNotBlank()) {
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { actionMenuOpen = true },
                                )
                            } else {
                                Modifier
                            },
                        ).background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        ).padding(horizontal = 10.dp, vertical = 4.dp),
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
internal fun EmptyGroupConversation(onAddMembers: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(56.dp))
            Text(
                stringResource(R.string.group_empty_only_you_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.group_empty_invite_members),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddMembers) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_member))
            }
        }
    }
}

/**
 * Sticky day-ribbon overlay: the day label of the topmost visible message,
 * faded in only while the timeline is actively scrolling (the inline
 * [DaySeparator]s carry the day at rest).
 *
 * Reads the scroll-backed state (`labelState` derived from
 * `firstVisibleItemIndex`, and `listState.isScrollInProgress`) inside this
 * small child so per-scroll-frame recomposition is confined here and does not
 * propagate to the LazyColumn-hosting Box scope (#375).
 */
@Composable
internal fun BoxScope.StickyDayRibbon(
    listState: androidx.compose.foundation.lazy.LazyListState,
    labelState: State<String>,
) {
    val label by labelState
    val alpha by animateFloatAsState(
        targetValue =
            if (shouldShowStickyDayRibbon(listState.isScrollInProgress, listState.canScrollBackward || listState.canScrollForward, label)) {
                1f
            } else {
                0f
            },
        label = "stickyDayRibbon",
    )
    if (alpha > 0.01f) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .alpha(alpha)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

internal fun shouldShowStickyDayRibbon(
    isScrollInProgress: Boolean,
    canScrollContent: Boolean,
    label: String,
): Boolean = isScrollInProgress && canScrollContent && label.isNotEmpty()
