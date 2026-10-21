package dev.ipf.whitenoise.android.state

import android.app.Notification
import android.app.NotificationManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import java.util.concurrent.CopyOnWriteArrayList

/** Records actual platform payloads, including picture-only writes that do not emit an AFTER_WRITE hook. */
@Implements(NotificationManager::class)
class ReactionWriteRecordingNotificationManager : ShadowNotificationManager() {
    /** Copies the submitted payload before normal notification-shadow behavior can replace the visible card. */
    @Implementation
    override fun notify(
        tag: String?,
        id: Int,
        notification: Notification,
    ) {
        writes.add(RecordedReactionWrite(tag, id, notification.clone()))
        super.notify(tag, id, notification)
    }

    companion object {
        val writes = CopyOnWriteArrayList<RecordedReactionWrite>()
    }
}

/** Exact platform identity and immutable payload observed by the reaction race fixture. */
data class RecordedReactionWrite(
    val tag: String?,
    val id: Int,
    val notification: Notification,
)
