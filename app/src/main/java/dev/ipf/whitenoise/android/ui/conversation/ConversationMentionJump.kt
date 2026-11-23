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
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.isUnreadMention
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanation
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag

/** Newer pages a jump may load past the read anchor while looking for a mention outside the loaded window. */
internal const val MENTION_RESOLUTION_MAX_NEWER_PAGES = 20

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
 * lie outside the loaded window. It stops counting once the rows cover the whole unread range or a jump finds no
 * mention it could reach.
 */
@Stable
internal class ConversationMentionJumpSeed(
    private val entryCount: Int,
) {
    private val readIds = mutableStateSetOf<String>()
    private var lastLoadedIds: List<String> = emptyList()
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
     * Counts a mention as read only when it leaves the unread list while its row stays loaded behind a loaded read
     * anchor. A mention that leaves because paging evicted it or the anchor is still unread.
     */
    fun observe(
        loadedIds: List<String>,
        coverage: ConversationMentionCoverage,
        anchorLoaded: Boolean,
        rowLoaded: (String) -> Boolean,
    ) {
        if (retired) return
        if (anchorLoaded) {
            lastLoadedIds.filter { it !in loadedIds && rowLoaded(it) }.forEach(readIds::add)
            lastLoadedIds = loadedIds
        }
        if (coverage == ConversationMentionCoverage.COMPLETE) retire()
    }

    /** A visited mention is read even when it was found outside the loaded rows. */
    fun visited(messageId: String) {
        if (!retired) readIds.add(messageId)
    }

    fun retire() {
        retired = true
        readIds.clear()
        lastLoadedIds = emptyList()
    }
}

/**
 * The target for a jump tap. A tap made before entry positioning waits for it; then the oldest loaded mention wins,
 * and only an active seed may search outside the loaded window.
 */
internal suspend fun ConversationMentionJumpSeed.jumpTarget(
    awaitAnchored: suspend () -> Unit,
    loadedTarget: () -> String?,
    resolve: suspend () -> String?,
): String? {
    awaitAnchored()
    return loadedTarget() ?: if (active) resolve() else null
}

/** One seed per entry session; the entry row is captured once, so the count changes at most from absent to known. */
@Composable
internal fun rememberConversationMentionJumpSeed(
    sessionIdentity: Any,
    entryUnreadMentionCount: ULong?,
): ConversationMentionJumpSeed {
    val entryCount = (entryUnreadMentionCount ?: 0uL).coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    return remember(sessionIdentity, entryCount) { ConversationMentionJumpSeed(entryCount) }
}

/**
 * Finds the oldest unread mention by walking newer from the read anchor, or from the entry's first unread row when
 * nothing was read yet. It loads the start row when the window does not hold it and then at most [maxNewerPages]
 * newer pages. Returns null when no reachable mention exists.
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
): String? {
    val start = readAnchorMessageId ?: firstUnreadMessageId ?: return null
    val includeStart = readAnchorMessageId == null
    if (timeline().none { it.record.messageIdHex == start } && !loadMessage(start)) return null
    var passedStart = false
    for (page in 0..maxNewerPages) {
        val rows = timeline()
        val startIndex = rows.indexOfFirst { it.record.messageIdHex == start }
        val candidates =
            when {
                startIndex >= 0 -> rows.drop(if (includeStart) startIndex else startIndex + 1)
                // Paging newer slid the start out of the window; every loaded row is now newer than it.
                passedStart -> rows
                else -> return null
            }
        passedStart = true
        candidates.firstOrNull { it.isUnreadMention(mentionsActiveAccount) }?.let { return it.record.messageIdHex }
        if (page == maxNewerPages || !hasMoreAfter() || !loadNewer()) return null
    }
    return null
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
            Text(if (count > 99) "99+" else count.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}
