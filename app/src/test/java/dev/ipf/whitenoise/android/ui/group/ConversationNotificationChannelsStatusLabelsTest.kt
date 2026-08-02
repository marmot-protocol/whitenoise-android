package dev.ipf.whitenoise.android.ui.group

import android.app.NotificationManager
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels.ConversationChannelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationNotificationChannelsStatusLabelsTest {
    @Test
    fun eachImportanceBucketGetsItsOwnLabel() {
        assertEquals(listOf(R.string.notification_importance_off), labelsFor(NotificationManager.IMPORTANCE_NONE))
        assertEquals(listOf(R.string.notification_importance_silent), labelsFor(NotificationManager.IMPORTANCE_MIN))
        assertEquals(listOf(R.string.notification_importance_silent), labelsFor(NotificationManager.IMPORTANCE_LOW))
        assertEquals(listOf(R.string.notification_importance_sound), labelsFor(NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals(listOf(R.string.notification_importance_pop_up), labelsFor(NotificationManager.IMPORTANCE_HIGH))
        assertEquals(listOf(R.string.notification_importance_pop_up), labelsFor(NotificationManager.IMPORTANCE_MAX))
    }

    @Test
    fun priorityAndCustomizedAreAppendedAfterTheImportance() {
        val labels =
            conversationChannelStatusLabels(
                ConversationChannelStatus(
                    importance = NotificationManager.IMPORTANCE_HIGH,
                    userSetImportance = true,
                    importantConversation = true,
                ),
            )

        assertEquals(
            listOf(
                R.string.notification_importance_pop_up,
                R.string.notification_importance_priority,
                R.string.notification_importance_customized,
            ),
            labels,
        )
    }

    @Test
    fun aMutedConversationReadsAsOffEvenWhenTheUserSetItThatWay() {
        val labels =
            conversationChannelStatusLabels(
                ConversationChannelStatus(
                    importance = NotificationManager.IMPORTANCE_NONE,
                    userSetImportance = true,
                    importantConversation = false,
                ),
            )

        assertEquals(
            listOf(R.string.notification_importance_off, R.string.notification_importance_customized),
            labels,
        )
    }

    private fun labelsFor(importance: Int): List<Int> =
        conversationChannelStatusLabels(
            ConversationChannelStatus(
                importance = importance,
                userSetImportance = false,
                importantConversation = false,
            ),
        )
}
