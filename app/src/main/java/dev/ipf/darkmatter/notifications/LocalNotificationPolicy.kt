package dev.ipf.darkmatter.notifications

import dev.ipf.marmotkit.NotificationUpdateFfi

object LocalNotificationPolicy {
    fun shouldPost(
        update: NotificationUpdateFfi,
        appInForeground: Boolean,
        activeConversationGroupIdHex: String?,
    ): Boolean {
        return !(appInForeground && activeConversationGroupIdHex == update.groupIdHex)
    }
}
