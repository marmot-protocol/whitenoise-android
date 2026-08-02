package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun relevantParentsCoverEveryConversationNotificationType() {
        assertEquals(
            listOf(
                NotificationChannelSpec.GROUP_MESSAGES,
                NotificationChannelSpec.MENTIONS,
                NotificationChannelSpec.REACTIONS,
                NotificationChannelSpec.INVITES,
                NotificationChannelSpec.AGENT_ACTIVITY,
            ),
            ConversationNotificationChannels.relevantParents(isDm = false),
        )
        assertEquals(
            listOf(
                NotificationChannelSpec.DIRECT_MESSAGES,
                NotificationChannelSpec.MENTIONS,
                NotificationChannelSpec.REACTIONS,
                NotificationChannelSpec.INVITES,
                NotificationChannelSpec.AGENT_ACTIVITY,
            ),
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
        assertNotNull(conversationChannel("reactions_v2", shortcut))
        assertNotNull(conversationChannel("invites_v2", shortcut))
        assertNotNull(conversationChannel("agent_activity_v1", shortcut))
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
        assertNotNull(conversationChannel("reactions_v2", shortcut))
        assertNotNull(conversationChannel("invites_v2", shortcut))
        assertNotNull(conversationChannel("agent_activity_v1", shortcut))
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
    fun conversationChannelNameIncludesConversationAndNotificationType() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-named"

        val convId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_dm",
                conversationShortcutId = shortcut,
                conversationTitle = "Green Orca",
            )

        assertEquals("Green Orca · Direct messages", manager.getNotificationChannel(convId!!).name.toString())
    }

    @Test
    fun ensureUpdatesAnExistingGenericChannelNameWithoutChangingItsSettings() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-renamed"
        val convId =
            ConversationNotificationChannels.ensureConversationChannel(
                context,
                "messages_dm",
                shortcut,
            )!!
        val existing = manager.getNotificationChannel(convId)
        existing.enableVibration(false)
        manager.createNotificationChannel(existing)

        ConversationNotificationChannels.ensureConversationChannel(
            context = context,
            parentChannelId = "messages_dm",
            conversationShortcutId = shortcut,
            conversationTitle = "Green Orca",
        )

        val updated = manager.getNotificationChannel(convId)
        assertEquals("Green Orca · Direct messages", updated.name.toString())
        assertEquals(false, updated.shouldVibrate())
    }

    @Test
    fun ensureWithoutTitleDoesNotDowngradeAnExistingConversationName() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-name-preserved"
        val convId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_dm",
                conversationShortcutId = shortcut,
                conversationTitle = "Green Orca",
            )!!

        ConversationNotificationChannels.ensureConversationChannel(
            context = context,
            parentChannelId = "messages_dm",
            conversationShortcutId = shortcut,
        )

        assertEquals("Green Orca · Direct messages", manager.getNotificationChannel(convId).name.toString())
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

    @Test
    fun conversationChannelDoesNotClaimDndBypass() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-dnd"

        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertFalse(manager.getNotificationChannel(convId!!).canBypassDnd())
    }

    @Test
    fun creationStillClonesTheParentImportanceEvenWhenItIsHigherThanTheDefault() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-create-high"

        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(convId!!).importance)
    }

    @Test
    fun pristineConversationChannelFollowsTheParentDownToALowerImportance() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-downgrade"
        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)!!
        lowerParentImportance("messages_group", NotificationManager.IMPORTANCE_LOW)

        ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(convId).importance)
    }

    @Test
    fun conversationChannelWithAUserSetImportanceIsLeftAloneWhenTheParentDrops() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-user-set"
        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)!!
        val customised = manager.getNotificationChannel(convId)
        customised.enableVibration(false)
        customised.markImportanceUserSet()
        manager.createNotificationChannel(customised)
        lowerParentImportance("messages_group", NotificationManager.IMPORTANCE_LOW)

        ConversationNotificationChannels.ensureConversationChannel(
            context = context,
            parentChannelId = "messages_group",
            conversationShortcutId = shortcut,
            conversationTitle = "Green Orca",
        )

        val updated = manager.getNotificationChannel(convId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, updated.importance)
        assertEquals(false, updated.shouldVibrate())
        // A permitted rename still lands; only the importance is off limits.
        assertEquals("Green Orca · Group messages", updated.name.toString())
    }

    @Test
    fun conversationChannelIsNeverRaisedBackUpToTheParentImportance() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-no-raise"
        lowerParentImportance("messages_group", NotificationManager.IMPORTANCE_LOW)
        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)!!
        lowerParentImportance("messages_group", NotificationManager.IMPORTANCE_HIGH)

        ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(convId).importance)
    }

    private fun lowerParentImportance(
        parentChannelId: String,
        importance: Int,
    ) {
        val parent = manager.getNotificationChannel(parentChannelId)
        parent.importance = importance
        manager.createNotificationChannel(parent)
    }

    /** `lockFields` is the only way to reproduce a user-edited importance off-device. */
    private fun NotificationChannel.markImportanceUserSet() {
        val userLockedImportance = NotificationChannel::class.java.getField("USER_LOCKED_IMPORTANCE").getInt(null)
        NotificationChannel::class
            .java
            .getMethod("lockFields", Int::class.javaPrimitiveType)
            .invoke(this, userLockedImportance)
    }

    private fun conversationChannel(
        parentChannelId: String,
        shortcut: String,
    ) = manager.getNotificationChannel(
        ConversationNotificationChannels.conversationChannelId(parentChannelId, shortcut),
    )
}
