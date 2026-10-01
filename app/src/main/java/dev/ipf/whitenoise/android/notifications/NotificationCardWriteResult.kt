package dev.ipf.whitenoise.android.notifications

/** A stale write refusal must never trigger platform-failure cancellation or retry. */
internal enum class NotificationCardWriteResult { WRITTEN, REFUSED, FAILED }
