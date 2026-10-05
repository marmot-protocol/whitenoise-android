package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID

/** Transient SystemUI handoff; never place its plaintext in saved-instance state or diagnostics. */
data class NotificationReplyDraft(
    val id: String,
    val text: String,
) {
    override fun toString(): String = "NotificationReplyDraft(redacted)"
}

internal fun notificationReplyDraftFrom(intent: Intent, kind: NotificationTargetKind): NotificationReplyDraft? =
    if (kind == NotificationTargetKind.MESSAGE) {
        intent.getStringExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_REMOTE_DRAFT_CHARS }
            ?.let { NotificationReplyDraft(UUID.randomUUID().toString(), it) }
    } else {
        null
    }

/** Retains an accepted tap through unlock and Activity recreation without serializing its text. */
class NotificationInboundState : ViewModel() {
    var target by mutableStateOf<NotificationTarget?>(null)
    var requestId by mutableLongStateOf(0L)
}

private const val MAX_REMOTE_DRAFT_CHARS = 65_536

internal const val NOTIFICATION_BOUND_ROUTE_QUERY = "bound"

/** Stable card identity; the mutable fill-in cannot change its signed destination. */
internal fun NotificationNavigation.applyBoundToIntent(
    intent: Intent,
    target: NotificationTarget,
    notificationKey: String,
    signature: String,
) {
    applyToIntent(intent, target, notificationKey, signature)
    intent.data = intent.data?.buildUpon()?.appendQueryParameter(NOTIFICATION_BOUND_ROUTE_QUERY, "1")?.build()
}
