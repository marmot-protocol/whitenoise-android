package dev.ipf.whitenoise.android.state

enum class TtsAutoReadOverride {
    ON,
    OFF,
}

data class TtsAutoReadPreferenceState(
    val globalDefaultEnabled: Boolean = false,
    val overrides: Map<String, TtsAutoReadOverride> = emptyMap(),
)

/** Effective auto-read: per-chat override when set, otherwise the global default. */
fun resolveConversationAutoRead(
    globalDefaultEnabled: Boolean,
    override: TtsAutoReadOverride?,
): Boolean = override?.let { it == TtsAutoReadOverride.ON } ?: globalDefaultEnabled

/** Captures the policy generation used by one pending auto-read projection. */
internal fun WhiteNoiseAppState.captureTtsAutoReadPolicy(groupIdHex: String): () -> Boolean {
    val policy = ttsAutoReadPreferences.state.value
    // Turning auto-read off and back on publishes a new snapshot and revokes old projections.
    return { ttsAutoReadPreferences.state.value === policy && isConversationAutoRead(groupIdHex) }
}

/** Scopes the auto-read session key to one account and conversation. */
internal fun ttsAutoReadKey(
    account: String?,
    group: String,
): String? = account?.let { "$it|${group.lowercase()}" }
