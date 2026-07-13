package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNotificationChannelsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun conversationChannelIdIsDeterministicAndScopedToParentAndShortcut() {
        val id = ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-abc")

        assertEquals(id, ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-abc"))
        assertNotEquals(id, ConversationNotificationChannels.conversationChannelId("mentions", "conversation-abc"))
        assertNotEquals(id, ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-xyz"))
    }

    @Test
    fun primaryMessageParentIsTheMessageChannelNotMentions() {
        assertEquals(NotificationChannelSpec.DIRECT_MESSAGES, ConversationNotificationChannels.primaryMessageParent(isDm = true))
        assertEquals(NotificationChannelSpec.GROUP_MESSAGES, ConversationNotificationChannels.primaryMessageParent(isDm = false))
    }

    @Test
    fun relevantParentsAreThePrimaryMessageParentPlusMentionsOnly() {
        assertEquals(
            listOf(NotificationChannelSpec.GROUP_MESSAGES, NotificationChannelSpec.MENTIONS),
            ConversationNotificationChannels.relevantParents(isDm = false),
        )
        assertEquals(
            listOf(NotificationChannelSpec.DIRECT_MESSAGES, NotificationChannelSpec.MENTIONS),
            ConversationNotificationChannels.relevantParents(isDm = true),
        )
    }

    @Test
    fun ensureCreatesConversationChannelsForGroupPrimaryAndMentions() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-group"

        ConversationNotificationChannels.ensureConversationChannels(context, shortcut, isDm = false)

        val groupChannel = manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("messages_group", shortcut))
        val mentionChannel = manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("mentions", shortcut))
        assertNotNull(groupChannel)
        assertEquals("messages_group", groupChannel!!.parentChannelId)
        assertEquals(shortcut, groupChannel.conversationId)
        assertNotNull(mentionChannel)
        assertEquals("mentions", mentionChannel!!.parentChannelId)
        assertEquals(shortcut, mentionChannel.conversationId)
        // A group conversation never receives on the DM parent, so no DM child.
        assertNull(manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("messages_dm", shortcut)))
    }

    @Test
    fun ensureCreatesConversationChannelsForDmPrimaryAndMentions() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-dm"

        ConversationNotificationChannels.ensureConversationChannels(context, shortcut, isDm = true)

        assertNotNull(manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("messages_dm", shortcut)))
        assertNotNull(manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("mentions", shortcut)))
        assertNull(manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("messages_group", shortcut)))
    }

    @Test
    fun conversationChannelInheritsParentImportance() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-importance"

        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertNotNull(convId)
        assertEquals(
            manager.getNotificationChannel("messages_group").importance,
            manager.getNotificationChannel(convId!!).importance,
        )
    }

    @Test
    fun ensureIsIdempotentAndReturnsTheSameChannelId() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-idempotent"

        val first = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)
        val second = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertEquals(first, second)
        assertNotNull(manager.getNotificationChannel(first))
    }

    @Test
    fun ensureReturnsNullAndCreatesNothingWhenParentChannelMissing() {
        // Parents intentionally not created for this conversation's parent id.
        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "missing_parent", "conversation-orphan")

        assertNull(convId)
        assertNull(manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId("missing_parent", "conversation-orphan")))
    }
}
