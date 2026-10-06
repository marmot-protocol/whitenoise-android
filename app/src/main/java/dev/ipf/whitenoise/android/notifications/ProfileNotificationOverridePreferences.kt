package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Device-local alert routing only; no profile, group or message content is stored. */
internal enum class ProfileNotificationMode { DEFAULT, MUTED, CUSTOM }

/** The last chosen waveform remains available when a custom channel is made dormant. */
internal data class ProfileNotificationOverride(
    val mode: ProfileNotificationMode = ProfileNotificationMode.DEFAULT,
    val vibration: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
)

/** Hashed account/author preferences shared by UI and background notification entry points. */
internal class ProfileNotificationOverridePreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val revision = MutableStateFlow(0L)
    val state = revision.asStateFlow()

    /** Read the shared memory-backed preferences so another adapter's changes are immediately visible. */
    fun get(
        accountRef: String?,
        author: String?,
    ): ProfileNotificationOverride =
        synchronized(mutationLock) {
            val key = key(accountRef, author) ?: return@synchronized ProfileNotificationOverride()
            val fields = preferences.getString(key, null)?.split('|').orEmpty()
            ProfileNotificationOverride(
                mode =
                    ProfileNotificationMode.entries.firstOrNull { it.name == fields.getOrNull(0) }
                        ?: ProfileNotificationMode.DEFAULT,
                vibration =
                    ConversationVibrationPattern.entries.firstOrNull { it.name == fields.getOrNull(1) }
                        ?: ConversationVibrationPattern.SYSTEM_DEFAULT,
            )
        }

    /** A stored choice may have dormant channels whose mutable labels still need to follow a rename. */
    fun hasChoice(
        accountRef: String,
        author: String,
    ): Boolean = synchronized(mutationLock) { key(accountRef, author)?.let(preferences::contains) == true }

    /** Commit off-main before reporting success; a failed save must not pretend routing changed durably. */
    fun set(
        accountRef: String,
        author: String,
        selection: ProfileNotificationOverride,
        canWrite: () -> Boolean = { true },
    ): Boolean =
        synchronized(mutationLock) {
            if (!canWrite()) return@synchronized false
            val key = key(accountRef, author) ?: return@synchronized false
            val previous = preferences.getString(key, null)
            val saved = preferences.edit().putString(key, "${selection.mode.name}|${selection.vibration.name}").commit()
            if (!saved) restoreInMemory(mapOf(key to previous))
            if (saved) revision.value += 1
            saved
        }

    /** Account removal preserves other owners and restores retryable choices after failed or thrown writes. */
    fun clearAccount(accountRef: String): Boolean =
        synchronized(mutationLock) {
            val prefix = accountPrefix(accountRef)
            val previous =
                preferences.all.keys
                    .filter { it.startsWith(prefix) }
                    .associateWith { preferences.getString(it, null) }
            val editor = preferences.edit()
            previous.keys.forEach(editor::remove)
            val saved =
                runCatching { editor.commit() }.getOrElse { error ->
                    runCatching { restoreInMemory(previous) }
                    throw error
                }
            if (!saved) restoreInMemory(previous)
            if (saved) revision.value += 1
            saved
        }

    /**
     * Commit off-main, preserving retryable choices after a failed flush and rejecting superseded account snapshots.
     */
    fun retainAccounts(
        accountRefs: Collection<String>,
        canRetain: () -> Boolean = { true },
    ): Boolean {
        val retained = accountRefs.map(::accountPrefix).toSet()
        return synchronized(mutationLock) {
            if (!canRetain()) return@synchronized true
            val previous =
                preferences.all.keys
                    .filter { key -> retained.none(key::startsWith) }
                    .associateWith { preferences.getString(it, null) }
            if (previous.isEmpty()) return@synchronized true
            val editor = preferences.edit()
            previous.keys.forEach(editor::remove)
            val saved =
                runCatching { editor.commit() }.getOrElse { error ->
                    runCatching { restoreInMemory(previous) }
                    throw error
                }
            if (!saved) restoreInMemory(previous)
            if (saved) revision.value += 1
            saved
        }
    }

    /**
     * Android mutates its memory map before commit reports a failed disk write. Roll it back while
     * readers hold the same lock; even a failed rollback flush still restores the old in-memory policy.
     */
    private fun restoreInMemory(previous: Map<String, String?>) {
        val editor = preferences.edit()
        previous.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        editor.commit()
    }

    /** Only another author's actual incoming message participates in person-specific alert policy. */
    fun isMuted(update: NotificationUpdateFfi): Boolean {
        val author = profileNotificationAuthor(update) ?: return false
        return get(update.accountRef, author).mode == ProfileNotificationMode.MUTED
    }

    companion object {
        private const val PREFERENCES_NAME = "whitenoise.profile_notification_overrides"
        private val mutationLock = Any()
        private val authorPattern = Regex("[0-9a-f]{64}")

        /** Strict canonical author identity prevents malformed updates from selecting someone else's override. */
        fun normalizedAuthor(author: String?): String? = author?.lowercase(Locale.ROOT)?.takeIf(authorPattern::matches)

        /** Both pieces have fixed-width hashes; neither profile text nor raw identity enters Android settings. */
        fun key(
            accountRef: String?,
            author: String?,
        ): String? {
            val account = accountRef?.takeIf { it.isNotBlank() && it == it.trim() }
            val identity = normalizedAuthor(author)
            return if (account != null && identity != null) accountPrefix(account) + sha256Hex(identity) else null
        }

        /** Separates account records by a fixed-width digest so cleanup cannot match a similarly named owner. */
        private fun accountPrefix(account: String): String = "${sha256Hex(account)}."
    }
}

/** Reactions, membership, invites, self messages and agent progress retain their existing category policy. */
internal fun profileNotificationAuthor(update: NotificationUpdateFfi): String? =
    when {
        update.trigger != NotificationTriggerFfi.NEW_MESSAGE || update.isFromSelf -> null
        update.trafficClass == NotificationTrafficClassFfi.AGENT_ACTIVITY -> null
        LocalNotificationFormatter.isReaction(update) -> null
        else -> ProfileNotificationOverridePreferences.normalizedAuthor(update.sender.accountIdHex)
    }
