package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi

/**
 * A command result bridges the gap until the chat-list subscription delivers
 * an authoritative projection. Timed results stop winning at their expiry.
 */
internal fun effectiveMuteOverride(
    override: ChatNotificationSettingsFfi?,
    nowMillis: Long,
): ChatNotificationSettingsFfi? {
    override ?: return null
    val expired = override.muted && override.mutedUntilMs?.let { it <= nowMillis } == true
    return if (expired) override.copy(muted = false, mutedUntilMs = null) else override
}

/** Only a projection of the command result supersedes its short-lived override. */
internal fun shouldDropMuteOverride(
    override: ChatNotificationSettingsFfi,
    projectedMuted: Boolean,
    projectedMutedUntilMs: Long?,
): Boolean = override.muted == projectedMuted && override.mutedUntilMs == projectedMutedUntilMs
