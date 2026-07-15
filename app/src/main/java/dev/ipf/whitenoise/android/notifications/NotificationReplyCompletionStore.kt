package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences

internal data class NotificationReplyRecoveryBoundary(
    val timelineAt: ULong,
    val messageIdHex: String,
)

internal enum class NotificationReplyAbandonedOutcome {
    Success,
    Failure,
}

internal class NotificationReplyCompletionStore(
    private val preferences: SharedPreferences,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun isCompleted(key: String): Boolean = completedAt(key) != null

    fun hasStarted(key: String): Boolean = startedAt(key) != null

    fun abandonedOutcome(key: String): NotificationReplyAbandonedOutcome? =
        preferences
            .getString(abandonedOutcomeStorageKey(key), null)
            ?.let { stored -> NotificationReplyAbandonedOutcome.entries.firstOrNull { it.name == stored } }

    fun startedRecoveryState(key: String): NotificationReplyRecoveryState? =
        synchronized(STATE_LOCK) {
            if (hasStarted(key)) recoveryState(key) else null
        }

    fun recoveryLookup(key: String): NotificationReplyRecoveryLookup =
        synchronized(STATE_LOCK) {
            if (!hasStarted(key)) return@synchronized NotificationReplyRecoveryLookup.NotStarted
            val state = recoveryState(key) ?: return@synchronized NotificationReplyRecoveryLookup.Indeterminate
            NotificationReplyRecoveryLookup.Ready(
                NotificationReplyRecoverySnapshot(
                    recoveryState = state,
                    nextAttemptBoundary = nextRecoveryBoundary(key, state),
                ),
            )
        }

    fun recoverySnapshot(key: String): NotificationReplyRecoverySnapshot? = (recoveryLookup(key) as? NotificationReplyRecoveryLookup.Ready)?.snapshot

    fun markStarted(
        key: String,
        scope: String,
        recoveryBoundary: NotificationReplyRecoveryBoundary,
    ): NotificationReplyRecoveryBoundary? {
        if (
            scope.isBlank() ||
            recoveryBoundary.timelineAt > Long.MAX_VALUE.toULong() ||
            !MESSAGE_ID.matches(recoveryBoundary.messageIdHex)
        ) {
            return null
        }
        // Persist and compare boundaries in lowercase so the fence orders consistently
        // against the engine's canonical (lowercase) timeline ids.
        val canonicalBoundary = recoveryBoundary.copy(messageIdHex = recoveryBoundary.messageIdHex.lowercase())
        val now = nowMillis()
        return synchronized(STATE_LOCK) {
            val previousSequence = preferences.getLong(NEXT_RECOVERY_SEQUENCE_KEY, 0L)
            if (previousSequence == Long.MAX_VALUE) return@synchronized null
            val sequence = previousSequence + 1L
            val persistedBoundary = strictlyLaterBoundary(scope, canonicalBoundary) ?: return@synchronized null
            val persisted =
                preferences
                    .edit()
                    .putLong(NEXT_RECOVERY_SEQUENCE_KEY, sequence)
                    .putLong(startedStorageKey(key), now)
                    .putLong(recoveryTimelineAtStorageKey(key), persistedBoundary.timelineAt.toLong())
                    .putString(recoveryMessageIdStorageKey(key), persistedBoundary.messageIdHex)
                    .putString(recoveryScopeStorageKey(key), scope)
                    .putLong(recoverySequenceStorageKey(key), sequence)
                    .remove(abandonedStorageKey(key))
                    .remove(abandonedOutcomeStorageKey(key))
                    .remove(committedMessageIdStorageKey(key))
                    .commit()
            if (persisted) {
                pruneUnneededRecoveryStates()
                persistedBoundary
            } else {
                null
            }
        }
    }

    fun markCommittedMessage(
        key: String,
        messageIdHex: String,
    ): Boolean {
        if (!MESSAGE_ID.matches(messageIdHex)) return false
        val canonicalMessageId = messageIdHex.lowercase()
        return synchronized(STATE_LOCK) {
            if (!hasStarted(key)) return@synchronized false
            preferences.edit().putString(committedMessageIdStorageKey(key), canonicalMessageId).commit()
        }
    }

    fun markCompleted(key: String) {
        val now = nowMillis()
        synchronized(STATE_LOCK) {
            preferences
                .edit()
                .putLong(completedStorageKey(key), now)
                .remove(abandonedStorageKey(key))
                .remove(abandonedOutcomeStorageKey(key))
                .remove(startedStorageKey(key))
                .commit()
            // Keep the recovery boundary until no older active request needs it
            // as an upper fence.
            pruneUnneededRecoveryStates()
        }
    }

    fun markAbandoned(
        key: String,
        outcome: NotificationReplyAbandonedOutcome,
    ): Boolean {
        val now = nowMillis()
        return synchronized(STATE_LOCK) {
            val retainRecoveryFence = recoveryStateIsRequiredFence(key)
            var editor =
                preferences
                    .edit()
                    .putLong(abandonedStorageKey(key), now)
                    .putString(abandonedOutcomeStorageKey(key), outcome.name)
                    .remove(startedStorageKey(key))
                    .remove(committedMessageIdStorageKey(key))
            if (!retainRecoveryFence) editor = removeRecoveryState(editor, key)
            val persisted = editor.commit()
            if (persisted) pruneUnneededRecoveryStates()
            persisted
        }
    }

    private fun strictlyLaterBoundary(
        scope: String,
        proposed: NotificationReplyRecoveryBoundary,
    ): NotificationReplyRecoveryBoundary? {
        val latest =
            preferences.all.keys
                .asSequence()
                .filter { it.startsWith(RECOVERY_SEQUENCE_KEY_PREFIX) }
                .map { it.removePrefix(RECOVERY_SEQUENCE_KEY_PREFIX) }
                .mapNotNull(::recoveryState)
                .filter { it.scope == scope }
                .map { it.boundary }
                .maxWithOrNull(compareBy({ it.timelineAt }, { it.messageIdHex }))
                ?: return proposed
        if (compareRecoveryBoundaries(proposed, latest) > 0) return proposed
        if (latest.timelineAt >= Long.MAX_VALUE.toULong()) return null
        return NotificationReplyRecoveryBoundary(
            timelineAt = latest.timelineAt + 1uL,
            messageIdHex = MAX_MESSAGE_ID,
        )
    }

    private fun nextRecoveryBoundary(
        key: String,
        recoveryState: NotificationReplyRecoveryState,
    ): NotificationReplyRecoveryBoundary? =
        preferences.all.keys
            .asSequence()
            .filter { it.startsWith(RECOVERY_SEQUENCE_KEY_PREFIX) }
            .map { it.removePrefix(RECOVERY_SEQUENCE_KEY_PREFIX) }
            .filter { it != key }
            .mapNotNull(::recoveryState)
            .filter { it.scope == recoveryState.scope && it.sequence > recoveryState.sequence }
            .minByOrNull { it.sequence }
            ?.boundary

    private fun recoveryState(key: String): NotificationReplyRecoveryState? {
        val timelineAt =
            preferences
                .getLong(recoveryTimelineAtStorageKey(key), MISSING_TIMESTAMP)
                .takeUnless { it == MISSING_TIMESTAMP || it < 0L }
                ?.toULong()
                ?: return null
        val messageId =
            preferences
                .getString(recoveryMessageIdStorageKey(key), null)
                ?.takeIf(MESSAGE_ID::matches)
                ?: return null
        val scope = preferences.getString(recoveryScopeStorageKey(key), null)?.takeIf { it.isNotBlank() } ?: return null
        val sequence =
            preferences
                .getLong(recoverySequenceStorageKey(key), MISSING_SEQUENCE)
                .takeUnless { it == MISSING_SEQUENCE || it <= 0L }
                ?: return null
        return NotificationReplyRecoveryState(
            boundary = NotificationReplyRecoveryBoundary(timelineAt = timelineAt, messageIdHex = messageId),
            scope = scope,
            sequence = sequence,
            committedMessageIdHex = preferences.getString(committedMessageIdStorageKey(key), null),
        )
    }

    private fun completedAt(key: String): Long? =
        preferences
            .getLong(completedStorageKey(key), MISSING_TIMESTAMP)
            .takeUnless { it == MISSING_TIMESTAMP }

    private fun startedAt(key: String): Long? =
        preferences
            .getLong(startedStorageKey(key), MISSING_TIMESTAMP)
            .takeUnless { it == MISSING_TIMESTAMP }

    private fun pruneUnneededRecoveryStates() {
        val terminalKeys =
            preferences.all.keys
                .asSequence()
                .filter { it.startsWith(RECOVERY_SEQUENCE_KEY_PREFIX) }
                .map { it.removePrefix(RECOVERY_SEQUENCE_KEY_PREFIX) }
                .filterNot(::hasStarted)
                .toList()
        val prunableKeys = terminalKeys.filterNot(::recoveryStateIsRequiredFence)
        if (prunableKeys.isEmpty()) return
        val editor = preferences.edit()
        prunableKeys.forEach { key -> removeRecoveryState(editor, key) }
        editor.commit()
    }

    private fun recoveryStateIsRequiredFence(key: String): Boolean {
        val terminalState = recoveryState(key) ?: return false
        return preferences.all.keys
            .asSequence()
            .filter { it.startsWith(STARTED_KEY_PREFIX) }
            .map { it.removePrefix(STARTED_KEY_PREFIX) }
            .filter { it != key }
            .any { activeKey ->
                val active = recoveryState(activeKey) ?: return@any true
                active.scope == terminalState.scope && active.sequence < terminalState.sequence
            }
    }

    private fun removeRecoveryState(key: String): SharedPreferences.Editor = removeRecoveryState(preferences.edit(), key)

    private fun removeRecoveryState(
        editor: SharedPreferences.Editor,
        key: String,
    ): SharedPreferences.Editor =
        editor
            .remove(startedStorageKey(key))
            .remove(recoveryTimelineAtStorageKey(key))
            .remove(recoveryMessageIdStorageKey(key))
            .remove(recoveryScopeStorageKey(key))
            .remove(recoverySequenceStorageKey(key))
            .remove(committedMessageIdStorageKey(key))

    private fun compareRecoveryBoundaries(
        left: NotificationReplyRecoveryBoundary,
        right: NotificationReplyRecoveryBoundary,
    ): Int {
        val timelineComparison = left.timelineAt.compareTo(right.timelineAt)
        return if (timelineComparison != 0) timelineComparison else left.messageIdHex.compareTo(right.messageIdHex)
    }

    companion object {
        private const val PREFERENCES_NAME = "whitenoise.notification_replies"
        private const val COMPLETED_KEY_PREFIX = "completed_"
        private const val ABANDONED_KEY_PREFIX = "abandoned_"
        private const val ABANDONED_OUTCOME_KEY_PREFIX = "abandoned_outcome_"
        private const val STARTED_KEY_PREFIX = "started_"
        private const val RECOVERY_TIMELINE_AT_KEY_PREFIX = "recovery_timeline_at_"
        private const val RECOVERY_MESSAGE_ID_KEY_PREFIX = "recovery_message_id_"
        private const val RECOVERY_SCOPE_KEY_PREFIX = "recovery_scope_"
        private const val RECOVERY_SEQUENCE_KEY_PREFIX = "recovery_sequence_"
        private const val COMMITTED_MESSAGE_ID_KEY_PREFIX = "committed_message_id_"
        private const val NEXT_RECOVERY_SEQUENCE_KEY = "next_recovery_sequence"
        private const val MISSING_TIMESTAMP = Long.MIN_VALUE
        private const val MISSING_SEQUENCE = Long.MIN_VALUE
        private val MESSAGE_ID = Regex("[0-9a-fA-F]{64}")
        private val MAX_MESSAGE_ID = "f".repeat(64)
        private val STATE_LOCK = Any()

        fun create(context: Context): NotificationReplyCompletionStore =
            NotificationReplyCompletionStore(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        internal fun completedStorageKey(key: String): String = COMPLETED_KEY_PREFIX + key

        private fun abandonedStorageKey(key: String): String = ABANDONED_KEY_PREFIX + key

        private fun abandonedOutcomeStorageKey(key: String): String = ABANDONED_OUTCOME_KEY_PREFIX + key

        internal fun startedStorageKey(key: String): String = STARTED_KEY_PREFIX + key

        internal fun recoveryTimelineAtStorageKey(key: String): String = RECOVERY_TIMELINE_AT_KEY_PREFIX + key

        internal fun recoveryMessageIdStorageKey(key: String): String = RECOVERY_MESSAGE_ID_KEY_PREFIX + key

        private fun recoveryScopeStorageKey(key: String): String = RECOVERY_SCOPE_KEY_PREFIX + key

        internal fun recoverySequenceStorageKey(key: String): String = RECOVERY_SEQUENCE_KEY_PREFIX + key

        private fun committedMessageIdStorageKey(key: String): String = COMMITTED_MESSAGE_ID_KEY_PREFIX + key
    }
}
