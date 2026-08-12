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

/** A non-pending projection supersedes the short-lived command result. */
internal fun shouldDropMuteOverride(
    override: ChatNotificationSettingsFfi,
    projectedMuted: Boolean,
    projectedMutedUntilMs: Long?,
    commandPending: Boolean,
): Boolean =
    !commandPending ||
        (override.muted == projectedMuted && override.mutedUntilMs == projectedMutedUntilMs)
