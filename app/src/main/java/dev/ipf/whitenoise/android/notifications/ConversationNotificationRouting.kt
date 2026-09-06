package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Categories for which a chat may opt out of the stable global channel. */
internal enum class OverridableConversationNotificationCategory(
    val channel: NotificationChannelSpec,
) {
    MENTIONS(NotificationChannelSpec.MENTIONS),
    REACTIONS(NotificationChannelSpec.REACTIONS),
    INVITES(NotificationChannelSpec.INVITES),
    AGENT_ACTIVITY(NotificationChannelSpec.AGENT_ACTIVITY),
    ;

    companion object {
        fun from(channel: NotificationChannelSpec) = entries.firstOrNull { it.channel == channel }
    }
}

/** The Android-only routing choice for one event category in one chat. */
internal enum class ConversationNotificationScope {
    USE_GLOBAL_DEFAULT,
    CUSTOM_FOR_THIS_CHAT,
}

internal data class ConversationNotificationRoutingKey(
    val shortcutId: String,
    val category: OverridableConversationNotificationCategory,
)

internal data class NotificationConversationDescriptor(
    val shortcutId: String,
    val isDm: Boolean,
    val title: String?,
    val primaryVibrationPattern: ConversationVibrationPattern,
)

internal sealed interface AndroidNotificationSettingsTarget {
    val channelId: String

    data class Global(
        val channel: NotificationChannelSpec,
    ) : AndroidNotificationSettingsTarget {
        override val channelId: String = channel.id
    }

    data class Conversation(
        override val channelId: String,
        val shortcutId: String,
    ) : AndroidNotificationSettingsTarget
}

internal data class ConversationNotificationCategorySetting(
    val channel: NotificationChannelSpec,
    val scope: ConversationNotificationScope,
    val canChangeScope: Boolean,
    val settingsTarget: AndroidNotificationSettingsTarget,
)

internal data class ConversationNotificationPostRoute(
    val channelId: String,
    val scope: ConversationNotificationScope,
    val conversationShortcutId: String?,
)

/**
 * Owns the inheritance boundary between stable global channels and lazily
 * materialized conversation children.
 *
 * A missing preference is intentionally distinct from an explicit inherit
 * choice: a missing value probes for a legacy child and treats it as custom,
 * while an explicit inherit marker keeps that undeleted child inactive. New
 * inherited categories remain absent so the store grows only when a user has
 * changed a scope or an undeleted child needs an inheritance tombstone.
 */
internal class ConversationNotificationRouting(
    context: Context,
    private val preferences: ConversationNotificationRoutingPreferences =
        ConversationNotificationRoutingPreferences(context),
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    val state: StateFlow<Map<ConversationNotificationRoutingKey, ConversationNotificationScope>> =
        preferences.state

    fun resolveForPost(
        channel: NotificationChannelSpec,
        conversationShortcutId: String?,
        conversationTitle: String?,
        primaryVibrationPattern: ConversationVibrationPattern,
    ): ConversationNotificationPostRoute {
        if (conversationShortcutId == null || channel.conversationPolicy == ConversationChannelPolicy.GLOBAL_ONLY) {
            return globalRoute(channel, conversationShortcutId)
        }
        return when (channel.conversationPolicy) {
            ConversationChannelPolicy.REQUIRED_CHILD -> {
                val child =
                    ConversationNotificationChannels.ensureConversationChannel(
                        context = appContext,
                        parentChannelId = channel.id,
                        conversationShortcutId = conversationShortcutId,
                        conversationTitle = conversationTitle,
                        vibrationPattern = primaryVibrationPattern,
                    )
                ConversationNotificationPostRoute(
                    channelId = child ?: channel.id,
                    scope = ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
                    conversationShortcutId = conversationShortcutId,
                )
            }

            ConversationChannelPolicy.GLOBAL_UNTIL_OVERRIDE -> {
                val category = OverridableConversationNotificationCategory.from(channel)
                if (category == null) {
                    globalRoute(channel, conversationShortcutId)
                } else {
                    val scope = effectiveScope(conversationShortcutId, category)
                    val child =
                        if (scope == ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT) {
                            ConversationNotificationChannels.ensureConversationChannel(
                                context = appContext,
                                parentChannelId = channel.id,
                                conversationShortcutId = conversationShortcutId,
                                conversationTitle = conversationTitle,
                            )
                        } else {
                            null
                        }
                    ConversationNotificationPostRoute(
                        channelId = child ?: channel.id,
                        scope = scope,
                        conversationShortcutId = conversationShortcutId,
                    )
                }
            }

            ConversationChannelPolicy.GLOBAL_ONLY -> globalRoute(channel, conversationShortcutId)
        }
    }

    /**
     * Returns the complete per-chat settings model and performs the idempotent
     * legacy inference. Channel ids are deterministic, so this read path does
     * not create children before the lifecycle-bound settings preparer has
     * published the conversation shortcut.
     */
    fun settings(conversation: NotificationConversationDescriptor): List<ConversationNotificationCategorySetting> {
        val primary = ConversationNotificationChannels.primaryMessageParent(conversation.isDm)
        val primaryId =
            ConversationNotificationChannels.conversationChannelId(
                parentChannelId = primary.id,
                conversationShortcutId = conversation.shortcutId,
                vibrationPattern = conversation.primaryVibrationPattern,
            )
        return buildList {
            add(
                ConversationNotificationCategorySetting(
                    channel = primary,
                    scope = ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
                    canChangeScope = false,
                    settingsTarget =
                        AndroidNotificationSettingsTarget.Conversation(
                            channelId = primaryId,
                            shortcutId = conversation.shortcutId,
                        ),
                ),
            )
            OverridableConversationNotificationCategory.entries.forEach { category ->
                val scope = effectiveScope(conversation.shortcutId, category)
                val childId =
                    ConversationNotificationChannels.conversationChannelId(
                        category.channel.id,
                        conversation.shortcutId,
                    )
                add(
                    ConversationNotificationCategorySetting(
                        channel = category.channel,
                        scope = scope,
                        canChangeScope = true,
                        settingsTarget =
                            if (scope == ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT) {
                                AndroidNotificationSettingsTarget.Conversation(childId, conversation.shortcutId)
                            } else {
                                AndroidNotificationSettingsTarget.Global(category.channel)
                            },
                    ),
                )
            }
        }
    }

    /**
     * Applies one explicit scope choice. Custom activation creates the child
     * before persistence; resetting persists inheritance without deleting it.
     */
    fun setScope(
        conversation: NotificationConversationDescriptor,
        category: OverridableConversationNotificationCategory,
        scope: ConversationNotificationScope,
    ): Result<ConversationNotificationCategorySetting> =
        runCatching {
            // Establish a durable pre-override state before creating a new
            // child. Otherwise a failed custom write would leave a child with
            // no preference, and the legacy migration probe would activate it
            // on the next read even though the UI reported a failure.
            if (
                scope == ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT &&
                preferences.get(conversation.shortcutId, category) == null
            ) {
                val initialScope =
                    if (legacyChildExists(conversation.shortcutId, category)) {
                        ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
                    } else {
                        ConversationNotificationScope.USE_GLOBAL_DEFAULT
                    }
                check(preferences.set(conversation.shortcutId, category, initialScope)) {
                    "Could not initialize notification scope"
                }
            }
            val target =
                when (scope) {
                    ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT -> {
                        val child =
                            requireNotNull(
                                ConversationNotificationChannels.ensureConversationChannel(
                                    context = appContext,
                                    parentChannelId = category.channel.id,
                                    conversationShortcutId = conversation.shortcutId,
                                    conversationTitle = conversation.title,
                                ),
                            ) { "Global notification channel is unavailable" }
                        check(preferences.set(conversation.shortcutId, category, scope)) {
                            "Could not persist notification scope"
                        }
                        AndroidNotificationSettingsTarget.Conversation(child, conversation.shortcutId)
                    }

                    ConversationNotificationScope.USE_GLOBAL_DEFAULT -> {
                        check(preferences.set(conversation.shortcutId, category, scope)) {
                            "Could not persist notification scope"
                        }
                        AndroidNotificationSettingsTarget.Global(category.channel)
                    }
                }
            ConversationNotificationCategorySetting(
                channel = category.channel,
                scope = scope,
                canChangeScope = true,
                settingsTarget = target,
            )
        }

    private fun effectiveScope(
        shortcutId: String,
        category: OverridableConversationNotificationCategory,
    ): ConversationNotificationScope =
        preferences.get(shortcutId, category)
            ?: if (legacyChildExists(shortcutId, category)) {
                ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
            } else {
                ConversationNotificationScope.USE_GLOBAL_DEFAULT
            }

    private fun legacyChildExists(
        shortcutId: String,
        category: OverridableConversationNotificationCategory,
    ): Boolean =
        manager?.getNotificationChannel(
            ConversationNotificationChannels.conversationChannelId(
                category.channel.id,
                shortcutId,
            ),
        ) != null

    private fun globalRoute(
        channel: NotificationChannelSpec,
        conversationShortcutId: String?,
    ): ConversationNotificationPostRoute =
        ConversationNotificationPostRoute(
            channelId = channel.id,
            scope = ConversationNotificationScope.USE_GLOBAL_DEFAULT,
            conversationShortcutId = conversationShortcutId,
        )
}

/** Small durable Android routing store; it does not contain protocol policy. */
internal class ConversationNotificationRoutingPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val _state = MutableStateFlow(read(preferences))
    val state: StateFlow<Map<ConversationNotificationRoutingKey, ConversationNotificationScope>> = _state.asStateFlow()

    fun get(
        shortcutId: String,
        category: OverridableConversationNotificationCategory,
    ): ConversationNotificationScope? = read(preferences)[ConversationNotificationRoutingKey(shortcutId, category)]

    fun set(
        shortcutId: String,
        category: OverridableConversationNotificationCategory,
        scope: ConversationNotificationScope,
    ): Boolean =
        synchronized(mutationLock) {
            val key = ConversationNotificationRoutingKey(shortcutId, category)
            val current = read(preferences)
            if (current[key] == scope) {
                _state.value = current
                return@synchronized true
            }
            persist(current + (key to scope))
        }

    private fun persist(updated: Map<ConversationNotificationRoutingKey, ConversationNotificationScope>): Boolean {
        val encoded = updated.map { (key, scope) -> encode(key, scope) }.toSet()
        val committed = preferences.edit().putStringSet(KEY_ROUTES, encoded).commit()
        if (committed) _state.value = updated
        return committed
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.conversation_notification_routing"
        private const val KEY_ROUTES = "routes"

        // All encoded fields are enum names or a `conversation-<hex>` shortcut
        // id, so `|` is both unambiguous and safe in SharedPreferences XML.
        private const val FIELD_SEPARATOR = "|"
        private val mutationLock = Any()

        private fun encode(
            key: ConversationNotificationRoutingKey,
            scope: ConversationNotificationScope,
        ): String =
            listOf(
                scope.name,
                key.category.name,
                key.shortcutId,
            ).joinToString(FIELD_SEPARATOR)

        private fun read(preferences: SharedPreferences) =
            preferences
                .getStringSet(KEY_ROUTES, emptySet())
                .orEmpty()
                .mapNotNull(::decode)
                .toMap()

        private fun decode(encoded: String): Pair<ConversationNotificationRoutingKey, ConversationNotificationScope>? {
            val fields = encoded.split(FIELD_SEPARATOR, limit = ENCODED_FIELD_COUNT)
            if (fields.size != ENCODED_FIELD_COUNT || fields[SHORTCUT_FIELD_INDEX].isBlank()) return null
            val scope =
                ConversationNotificationScope.entries.firstOrNull {
                    it.name == fields[SCOPE_FIELD_INDEX]
                }
            val category =
                OverridableConversationNotificationCategory.entries.firstOrNull {
                    it.name == fields[CATEGORY_FIELD_INDEX]
                }
            return if (scope != null && category != null) {
                ConversationNotificationRoutingKey(fields[SHORTCUT_FIELD_INDEX], category) to scope
            } else {
                null
            }
        }

        private const val ENCODED_FIELD_COUNT = 3
        private const val SCOPE_FIELD_INDEX = 0
        private const val CATEGORY_FIELD_INDEX = 1
        private const val SHORTCUT_FIELD_INDEX = 2
    }
}
