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
