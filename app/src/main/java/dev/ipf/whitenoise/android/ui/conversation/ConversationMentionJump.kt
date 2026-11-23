package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.isUnreadMention
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanation
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag

/** Newer pages a jump may load past the read anchor while looking for a mention outside the loaded window. */
internal const val MENTION_RESOLUTION_MAX_NEWER_PAGES = 20

private const val MENTION_BADGE_MAX = 99

/** How much of the unread range the loaded timeline can account for. */
internal enum class ConversationMentionCoverage {
    /** The initial timeline or the read anchor is not settled yet. */
    PENDING,

    /** The read anchor is loaded or known, but unread rows may lie outside the loaded window. */
    PARTIAL,

    /** The loaded window holds the read anchor and reaches the newest row. */
    COMPLETE,
}

internal fun conversationMentionCoverage(
    anchored: Boolean,
    readAnchorMessageId: String?,
    timeline: List<TimelineMessage>,
    hasMoreAfter: Boolean,
): ConversationMentionCoverage =
    when {
        !anchored || readAnchorMessageId == null -> ConversationMentionCoverage.PENDING
        !hasMoreAfter && timeline.any { it.record.messageIdHex == readAnchorMessageId } ->
            ConversationMentionCoverage.COMPLETE
        else -> ConversationMentionCoverage.PARTIAL
    }

/**
 * Mention availability for one conversation entry. MDK's entry row already counts this account's unread mentions,
 * so the jump control can show on the first frame instead of waiting for the timeline to anchor. The loaded rows
 * supply the targets; the entry count, less the mentions read since entry, keeps the control while unread mentions
 * lie outside the loaded window. It stops counting once the rows cover the whole unread range or a jump runs out of
 * history without finding a mention.
 *
 * [entryLastReadMessageId] and [entryFirstUnreadMessageId] are the entry row's unread boundary. A mention counts as
 * read once the read anchor passes it on loaded rows known to follow that boundary.
 */
@Stable
internal class ConversationMentionJumpSeed(
    private val entryCount: Int,
    private val entryLastReadMessageId: String? = null,
    private val entryFirstUnreadMessageId: String? = null,
) {
    private val readIds = mutableStateSetOf<String>()
    private val afterBoundaryIds = HashSet<String>()
    private var retired by mutableStateOf(entryCount <= 0)

    /** True while the entry count may still name a mention the loaded rows have not shown. */
    val active: Boolean get() = !retired

    fun count(
        loadedIds: List<String>,
        coverage: ConversationMentionCoverage,
    ): Int =
        if (retired || coverage == ConversationMentionCoverage.COMPLETE) {
            loadedIds.size
        } else {
            maxOf(loadedIds.size, entryCount - readIds.size)
        }

    /**
     * Records the loaded mentions the read anchor has passed since entry. Rows count only from the entry boundary, or
     * from the first row an earlier window already placed after it, so paging or hiding the boundary rows never
     * counts an older mention.
     */
    fun observe(
        timeline: List<TimelineMessage>,
        readAnchorMessageId: String?,
        coverage: ConversationMentionCoverage,
        mentionsActiveAccount: (TimelineMessage) -> Boolean,
    ) {
        if (retired) return
        val start = afterBoundaryStart(timeline)
        if (start != null) {
            for (index in start until timeline.size) afterBoundaryIds.add(timeline[index].record.messageIdHex)
            val anchorIndex = readAnchorMessageId?.let { timeline.indexOfMessage(it) } ?: -1
            for (index in start..anchorIndex) {
                val row = timeline[index]
                if (row.isUnreadMention(mentionsActiveAccount)) readIds.add(row.record.messageIdHex)
            }
        }
        if (coverage == ConversationMentionCoverage.COMPLETE) retire()
    }

    /** The first loaded row known to follow the entry boundary, or null when no loaded row is. */
    private fun afterBoundaryStart(timeline: List<TimelineMessage>): Int? {
        val boundary =
            entryLastReadMessageId?.let { timeline.indexOfMessage(it).takeIf { i -> i >= 0 }?.plus(1) }
                ?: entryFirstUnreadMessageId?.let { timeline.indexOfMessage(it).takeIf { i -> i >= 0 } }
                // A chat with no boundary was entirely unread on entry.
                ?: 0.takeIf { entryLastReadMessageId == null && entryFirstUnreadMessageId == null }
        return boundary ?: timeline.indexOfFirst { it.record.messageIdHex in afterBoundaryIds }.takeIf { it >= 0 }
    }

    /** A visited mention is read even when it was found outside the loaded rows. */
    fun visited(messageId: String) {
        if (!retired) readIds.add(messageId)
    }

    fun retire() {
        retired = true
        readIds.clear()
        afterBoundaryIds.clear()
    }
}

private fun List<TimelineMessage>.indexOfMessage(messageId: String): Int =
    indexOfFirst { it.record.messageIdHex == messageId }

/** The outcome of looking for the oldest unread mention. */
internal sealed interface ConversationMentionSearch {
    data class Found(
        val messageId: String,
    ) : ConversationMentionSearch

    /** History after the start ran out, or the page budget did, without a mention. */
    data object Exhausted : ConversationMentionSearch

    /** The start row or a newer page could not load; a later tap may succeed. */
    data object Unavailable : ConversationMentionSearch
}

/**
 * The target for a jump tap. A tap made before entry positioning waits for it; then the oldest loaded mention wins,
 * and only an active seed may search outside the loaded window.
 */
internal suspend fun ConversationMentionJumpSeed.jumpTarget(
    awaitAnchored: suspend () -> Unit,
    loadedTarget: () -> String?,
    resolve: suspend () -> ConversationMentionSearch,
): ConversationMentionSearch {
    awaitAnchored()
    loadedTarget()?.let { return ConversationMentionSearch.Found(it) }
    return if (active) resolve() else ConversationMentionSearch.Exhausted
}

/** One seed per entry session; the entry row is captured once, so it changes at most from absent to known. */
@Composable
internal fun rememberConversationMentionJumpSeed(
    sessionIdentity: Any,
    entryRow: ChatListRowFfi?,
): ConversationMentionJumpSeed {
    val entryCount = (entryRow?.unreadMentionCount ?: 0uL).coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    return remember(sessionIdentity, entryRow) {
        ConversationMentionJumpSeed(entryCount, entryRow?.lastReadMessageIdHex, entryRow?.firstUnreadMessageIdHex)
    }
}

/**
 * Finds the oldest unread mention by walking newer from the read anchor, or from the entry's first unread row when
 * nothing was read yet. It loads the start row when the window does not hold it and then at most [maxNewerPages]
 * newer pages. A page that cannot load because another one is in flight still counts as progress when that other
 * page moved the window.
 */
@Suppress("LongParameterList", "ReturnCount")
internal suspend fun resolveFirstUnreadMention(
    readAnchorMessageId: String?,
    firstUnreadMessageId: String?,
    timeline: () -> List<TimelineMessage>,
    mentionsActiveAccount: (TimelineMessage) -> Boolean,
    loadMessage: suspend (String) -> Boolean,
    hasMoreAfter: () -> Boolean,
    loadNewer: suspend () -> Boolean,
    maxNewerPages: Int = MENTION_RESOLUTION_MAX_NEWER_PAGES,
): ConversationMentionSearch {
    val start = readAnchorMessageId ?: firstUnreadMessageId ?: return ConversationMentionSearch.Exhausted
    if (timeline().indexOfMessage(start) < 0 && !loadMessage(start)) return ConversationMentionSearch.Unavailable
    val scan = NewerMentionScan(start, includeStart = readAnchorMessageId == null, mentionsActiveAccount)
    for (page in 0..maxNewerPages) {
        val rows = timeline()
        scan.next(rows)?.let { return it }
        if (page == maxNewerPages || !hasMoreAfter()) return ConversationMentionSearch.Exhausted
        val newestBefore = rows.lastOrNull()?.record?.messageIdHex
        if (!loadNewer() && timeline().lastOrNull()?.record?.messageIdHex == newestBefore) {
            return ConversationMentionSearch.Unavailable
        }
    }
    return ConversationMentionSearch.Exhausted
}

/** Scans successive newer windows from [start]; once paging slides the start out, every loaded row is newer. */
private class NewerMentionScan(
    private val start: String,
    private val includeStart: Boolean,
    private val mentionsActiveAccount: (TimelineMessage) -> Boolean,
) {
    private var passedStart = false

    /** Found for a mention, Unavailable when the start was never loaded, null to keep paging. */
    fun next(rows: List<TimelineMessage>): ConversationMentionSearch? {
        val startIndex = rows.indexOfMessage(start)
        if (startIndex < 0 && !passedStart) return ConversationMentionSearch.Unavailable
        passedStart = true
        val candidates = if (startIndex < 0) rows else rows.drop(if (includeStart) startIndex else startIndex + 1)
        return candidates
            .firstOrNull { it.isUnreadMention(mentionsActiveAccount) }
            ?.let { ConversationMentionSearch.Found(it.record.messageIdHex) }
    }
}

/** The "@ count" control that visits the oldest unread mention. The badge caps at 99+; the label keeps the count. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationJumpToMentionButton(
    count: Int,
    onClick: () -> Unit,
) {
    val label =
        stringResource(R.string.conversation_jump_to_mention) + ", " +
            pluralStringResource(R.plurals.unread_mentions_count, count, count)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 2.dp,
        modifier =
            Modifier
                .height(34.dp)
                .performanceTestTag(PerformanceTestTags.JUMP_TO_MENTION)
                .explainOnLongPress(
                    composerExplanation(
                        R.string.conversation_jump_to_mention,
                        R.string.explanation_conversation_jump_to_mention,
                    ),
                ).clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics { contentDescription = label },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text("@", style = MaterialTheme.typography.titleMedium)
            Text(
                if (count > MENTION_BADGE_MAX) "$MENTION_BADGE_MAX+" else count.toString(),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
