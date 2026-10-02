package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.os.Build

/**
 * Hidden framework key, set by `Notification.Builder.setRemoteInputHistory` on API 30+, that holds
 * the structured `RemoteInputHistoryItem[]` copy of the RemoteInput history.
 */
internal const val EXTRA_REMOTE_INPUT_HISTORY_ITEMS = "android.remoteInputHistoryItems"

/**
 * Removes the structured RemoteInput history items from a built card on Android 11 (API 30) only.
 *
 * Android 11 SystemUI casts this extra straight to `RemoteInputHistoryItem[]` in
 * `NotificationEntry.isLastMessageFromReply`, but an app-posted extra arrives unparcelled as a
 * plain `Parcelable[]`. Once the user has replied or reacted from the shade, the re-post that
 * stamps the history makes SystemUI throw `ClassCastException` and restart. Android 12 fixed the
 * cast, so later releases keep the items. The legacy `CharSequence[]` history stays and the
 * re-post still ends SystemUI's reply spinner, though Android 11 draws no history line without
 * the items.
 */
internal fun Notification.withoutApi30RemoteInputHistoryItems(sdkInt: Int = Build.VERSION.SDK_INT): Notification =
    apply { if (sdkInt == Build.VERSION_CODES.R) extras?.remove(EXTRA_REMOTE_INPUT_HISTORY_ITEMS) }
