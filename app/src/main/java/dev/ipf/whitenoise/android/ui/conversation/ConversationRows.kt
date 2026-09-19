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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.rememberGroupSystemCopy
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

internal const val UNREAD_MESSAGES_DIVIDER_CONTENT_TAG = "unread-messages-divider-content"

/** Actual entry-unread count beneath the prototype outline, with native slot spacing accounted for. */
@Composable
internal fun UnreadMessagesDivider(
    count: Int,
    followsDayHeader: Boolean = false,
    followsGroupEvent: Boolean = false,
) {
    val text = pluralStringResource(R.plurals.unread_messages_count, count, count)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                // Prototype has separate 2dp slots around its 24/16dp divider insets (26/18dp total).
                // Native rows contribute 8dp before this embedded divider and no slot after it.
                .padding(top = if (followsDayHeader || followsGroupEvent) 24.dp else 18.dp, bottom = 18.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(UNREAD_MESSAGES_DIVIDER_CONTENT_TAG)
                    .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Top gap that opens a sender cluster. The unread divider carries its own inset below the outline, so a
 * first unread row that also starts a cluster must not add the cluster gap on top of it: the prototype
 * leaves one interval there, and stacking both doubled the space below "N unread messages".
 */
internal fun conversationClusterTopGap(
    sameSenderAsOlderBubble: Boolean,
    followsUnreadDivider: Boolean,
): Dp =
    if (sameSenderAsOlderBubble || followsUnreadDivider) {
        0.dp
    } else {
        WhiteNoiseSpacing.ConversationCluster
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

/** Flat inset the prototype gives a system-event row on both sides. */
private val GroupSystemRowVerticalPadding = 8.dp

/** Localized native date, presented as the prototype's transparent inline heading. */
@Composable
internal fun DaySeparator(
    label: String,
    atTranscriptStart: Boolean = false,
    followsGroupEvent: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                // A preceding group event already removes the native slot surplus from its bottom inset.
                .padding(
                    top =
                        if (atTranscriptStart) {
                            12.dp
                        } else if (followsGroupEvent) {
                            16.dp
                        } else {
                            10.dp
                        },
                    bottom = 18.dp,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .testTag("conversation.date.inline")
                    .background(
                        color = Color.Transparent,
                        shape = CircleShape,
                    ).padding(horizontal = 12.dp, vertical = 3.dp)
                    .semantics { heading() },
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

/**
 * The empty-conversation message, which explains the retention policy when one is in force.
 *
 * The wording never says history was lost: an empty timeline can equally mean nothing was ever sent
 * here (#2674).
 */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationEmptyMessage(
    state: ConversationEmptyState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            ConversationEmptyState.NoMessages ->
                Text(
                    stringResource(R.string.no_messages_yet),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            is ConversationEmptyState.DisappearingMessages ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        stringResource(R.string.conversation_empty_disappearing_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(
                            R.string.conversation_empty_disappearing_body,
                            disappearingMessagesLabel(state.retentionSeconds),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
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
        PinnedDayRibbonLabel(
            label = label,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = WhiteNoiseSpacing.FormField).alpha(alpha),
        )
    }
}

/** The scrolling date overlay keeps its native visibility owner and the prototype's translucent circular pill. */
@Composable
@Suppress("FunctionNaming")
internal fun PinnedDayRibbonLabel(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics { heading() },
        border = amoledOutlineBorder(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceDim.copy(alpha = 0.82f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
}

internal fun shouldShowStickyDayRibbon(
    isScrollInProgress: Boolean,
    canScrollContent: Boolean,
    label: String,
): Boolean = isScrollInProgress && canScrollContent && label.isNotEmpty()
