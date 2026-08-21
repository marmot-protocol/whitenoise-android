package dev.ipf.whitenoise.android.notifications

import android.os.Bundle

/** Lightweight notification route state that is safe to persist in an Activity bundle. */
internal data class PendingNotificationRoute(
    val target: NotificationTarget,
    val requestId: Long,
)

internal data class RestoredNotificationRouteState(
    val latestRequestId: Long,
    val pendingRoute: PendingNotificationRoute?,
)

internal fun Bundle.putNotificationRouteState(
    latestRequestId: Long,
    pendingRoute: PendingNotificationRoute?,
) {
    putLong(NOTIFICATION_REQUEST_ID_KEY, latestRequestId)
    pendingRoute?.let { route ->
        putString(NOTIFICATION_ACCOUNT_REF_KEY, route.target.accountRef)
        putString(NOTIFICATION_GROUP_ID_KEY, route.target.groupIdHex)
        putString(NOTIFICATION_MESSAGE_ID_KEY, route.target.messageIdHex)
        putString(NOTIFICATION_KIND_KEY, route.target.kind.name)
        putLong(NOTIFICATION_PENDING_REQUEST_ID_KEY, route.requestId)
    }
}

internal fun Bundle.restoreNotificationRouteState(): RestoredNotificationRouteState {
    val latestRequestId = getLong(NOTIFICATION_REQUEST_ID_KEY, 0L).coerceAtLeast(0L)
    val accountRef = getString(NOTIFICATION_ACCOUNT_REF_KEY)?.takeIf(String::isNotBlank)
    val groupIdHex = getString(NOTIFICATION_GROUP_ID_KEY)?.takeIf(String::isNotBlank)
    val pendingRequestId = getLong(NOTIFICATION_PENDING_REQUEST_ID_KEY, 0L)
    val kind =
        getString(NOTIFICATION_KIND_KEY)?.let { encoded ->
            runCatching { NotificationTargetKind.valueOf(encoded) }.getOrNull()
        }
    val pendingRoute =
        if (accountRef != null && groupIdHex != null && pendingRequestId > 0L && kind != null) {
            PendingNotificationRoute(
                target =
                    NotificationTarget(
                        accountRef = accountRef,
                        groupIdHex = groupIdHex,
                        messageIdHex = getString(NOTIFICATION_MESSAGE_ID_KEY)?.takeIf(String::isNotBlank),
                        kind = kind,
                    ),
                requestId = pendingRequestId,
            )
        } else {
            null
        }
    return RestoredNotificationRouteState(
        latestRequestId = maxOf(latestRequestId, pendingRequestId),
        pendingRoute = pendingRoute,
    )
}

private const val NOTIFICATION_REQUEST_ID_KEY = "notification_route.request_id"
private const val NOTIFICATION_PENDING_REQUEST_ID_KEY = "notification_route.pending_request_id"
private const val NOTIFICATION_ACCOUNT_REF_KEY = "notification_route.account_ref"
private const val NOTIFICATION_GROUP_ID_KEY = "notification_route.group_id"
private const val NOTIFICATION_MESSAGE_ID_KEY = "notification_route.message_id"
private const val NOTIFICATION_KIND_KEY = "notification_route.kind"
