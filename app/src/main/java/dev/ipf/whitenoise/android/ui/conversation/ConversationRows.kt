package dev.ipf.whitenoise.android.ui.conversation

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
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
