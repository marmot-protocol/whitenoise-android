package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNotificationRoutingTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var routing: ConversationNotificationRouting

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences("conversation-routing-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        routing =
            ConversationNotificationRouting(
                context,
                ConversationNotificationRoutingPreferences(context, preferences),
            )
        NotificationChannels.ensureChannels(context)
    }

    @Test
    fun newConversationCreatesOnlyItsRequiredMessageChild() {
        val shortcut = "conversation-new"

        val message =
            routing.resolveForPost(
                channel = NotificationChannelSpec.GROUP_MESSAGES,
                conversationShortcutId = shortcut,
                conversationTitle = "Family",
                primaryVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
            )

        assertEquals(
            ConversationNotificationChannels.conversationChannelId("messages_group", shortcut),
            message.channelId,
        )
        OverridableConversationNotificationCategory.entries.forEach { category ->
            val route =
                routing.resolveForPost(
                    channel = category.channel,
                    conversationShortcutId = shortcut,
                    conversationTitle = "Family",
                    primaryVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
                )
            assertEquals(category.channel.id, route.channelId)
            assertEquals(ConversationNotificationScope.USE_GLOBAL_DEFAULT, route.scope)
            assertNull(
                manager.getNotificationChannel(
                    ConversationNotificationChannels.conversationChannelId(category.channel.id, shortcut),
                ),
            )
        }
        assertTrue(routing.state.value.isEmpty())
    }

    @Test
    fun customActivationCreatesOnlyTheSelectedCategoryAndRoutesThroughIt() {
        val descriptor = descriptor("conversation-custom")

        val applied =
            routing
                .setScope(
                    descriptor,
                    OverridableConversationNotificationCategory.REACTIONS,
                    ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
                ).getOrThrow()

        val reactionChild =
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.REACTIONS.id,
                descriptor.shortcutId,
            )
        assertEquals(reactionChild, applied.settingsTarget.channelId)
        assertEquals("Family · Reactions (custom)", manager.getNotificationChannel(reactionChild).name.toString())
        assertNull(
            manager.getNotificationChannel(
                ConversationNotificationChannels.conversationChannelId(
                    NotificationChannelSpec.MENTIONS.id,
                    descriptor.shortcutId,
                ),
            ),
        )
        assertEquals(
            reactionChild,
            routing
                .resolveForPost(
                    channel = NotificationChannelSpec.REACTIONS,
                    conversationShortcutId = descriptor.shortcutId,
                    conversationTitle = descriptor.title,
                    primaryVibrationPattern = descriptor.primaryVibrationPattern,
                ).channelId,
        )
    }

    @Test
    fun failedInitialCustomPersistenceDoesNotCreateAChildThatMigrationCouldActivate() {
        val descriptor = descriptor("conversation-failed-custom")
        val category = OverridableConversationNotificationCategory.REACTIONS
        val failingPreferences =
            object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val delegate = preferences.edit()
                    return object : SharedPreferences.Editor by delegate {
                        override fun putStringSet(
                            key: String,
                            values: Set<String>?,
                        ): SharedPreferences.Editor {
                            delegate.putStringSet(key, values)
                            return this
                        }

                        override fun commit(): Boolean = false
                    }
                }
            }
        val failingRouting =
            ConversationNotificationRouting(
                context,
                ConversationNotificationRoutingPreferences(context, failingPreferences),
            )

        val result =
            failingRouting.setScope(
                descriptor,
                category,
                ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
            )

        assertTrue(result.isFailure)
        assertNull(
            manager.getNotificationChannel(
                ConversationNotificationChannels.conversationChannelId(category.channel.id, descriptor.shortcutId),
            ),
        )
    }

    @Test
    fun resetPersistsInheritanceAndDoesNotDeleteTheOldChild() {
        val descriptor = descriptor("conversation-reset")
        val category = OverridableConversationNotificationCategory.MENTIONS
        routing.setScope(descriptor, category, ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT).getOrThrow()
        val childId = ConversationNotificationChannels.conversationChannelId(category.channel.id, descriptor.shortcutId)

        routing.setScope(descriptor, category, ConversationNotificationScope.USE_GLOBAL_DEFAULT).getOrThrow()

        assertNotNull(manager.getNotificationChannel(childId))
        val restarted = restartRouting()
        val route =
            restarted.resolveForPost(
                channel = category.channel,
                conversationShortcutId = descriptor.shortcutId,
                conversationTitle = descriptor.title,
                primaryVibrationPattern = descriptor.primaryVibrationPattern,
            )
        assertEquals(category.channel.id, route.channelId)
        assertEquals(ConversationNotificationScope.USE_GLOBAL_DEFAULT, route.scope)
        assertNotNull(manager.getNotificationChannel(childId))
    }

    @Test
    fun missingPreferenceTreatsALegacyChildAsAnActiveOverrideAcrossRestart() {
        val descriptor = descriptor("conversation-legacy")
        val category = OverridableConversationNotificationCategory.AGENT_ACTIVITY
        val legacyChild =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = category.channel.id,
                conversationShortcutId = descriptor.shortcutId,
                conversationTitle = descriptor.title,
            )!!

        val first =
            routing.resolveForPost(
                channel = category.channel,
                conversationShortcutId = descriptor.shortcutId,
                conversationTitle = descriptor.title,
                primaryVibrationPattern = descriptor.primaryVibrationPattern,
            )
        val restarted = restartRouting()
        val second =
            restarted.resolveForPost(
                channel = category.channel,
                conversationShortcutId = descriptor.shortcutId,
                conversationTitle = descriptor.title,
                primaryVibrationPattern = descriptor.primaryVibrationPattern,
            )

        assertEquals(legacyChild, first.channelId)
        assertEquals(legacyChild, second.channelId)
        assertEquals(ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT, second.scope)
    }

    @Test
    fun migrationRefreshesOnlyCopyAndPreservesLegacyAlertSettings() {
        val descriptor = descriptor("conversation-preserved")
        val category = OverridableConversationNotificationCategory.REACTIONS
        val childId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = category.channel.id,
                conversationShortcutId = descriptor.shortcutId,
            )!!
        val legacy = manager.getNotificationChannel(childId)
        val customSound = Uri.parse("content://test/preserved-sound")
        legacy.setSound(customSound, legacy.audioAttributes)
        legacy.enableVibration(false)
        legacy.setShowBadge(false)
        manager.createNotificationChannel(legacy)

        val rows = routing.settings(descriptor)

        val migrated = manager.getNotificationChannel(childId)
        assertEquals(
            ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
            rows.single { it.channel == category.channel }.scope,
        )
        assertEquals(customSound, migrated.sound)
        assertFalse(migrated.shouldVibrate())
        assertFalse(migrated.canShowBadge())
    }

    @Test
    fun dmAndGroupMessageChildrenRemainIndependentAndVersioned() {
        val dm = descriptor("conversation-dm", isDm = true, vibration = ConversationVibrationPattern.DOUBLE)
        val group = descriptor("conversation-group", isDm = false, vibration = ConversationVibrationPattern.LONG)

        val dmRoute = routing.settings(dm).first()
        val groupRoute = routing.settings(group).first()

        assertEquals(NotificationChannelSpec.DIRECT_MESSAGES, dmRoute.channel)
        assertEquals(NotificationChannelSpec.GROUP_MESSAGES, groupRoute.channel)
        assertTrue(dmRoute.settingsTarget.channelId.contains(ConversationVibrationPattern.DOUBLE.channelToken))
        assertTrue(groupRoute.settingsTarget.channelId.contains(ConversationVibrationPattern.LONG.channelToken))
    }

    /** Reading settings predicts the required child id without creating it before shortcut publication. */
    @Test
    fun settingsModelDoesNotCreateTheRequiredMessageChild() {
        val conversation = descriptor("conversation-read-only", vibration = ConversationVibrationPattern.DOUBLE)
        val expectedId =
            ConversationNotificationChannels.conversationChannelId(
                parentChannelId = NotificationChannelSpec.GROUP_MESSAGES.id,
                conversationShortcutId = conversation.shortcutId,
                vibrationPattern = ConversationVibrationPattern.DOUBLE,
            )

        val route = routing.settings(conversation).first()

        assertEquals(expectedId, route.settingsTarget.channelId)
        assertNull(manager.getNotificationChannel(expectedId))
    }

    @Test
    fun explicitChoicesAreScopedToOneConversation() {
        val first = descriptor("conversation-first")
        val second = descriptor("conversation-second")
        val category = OverridableConversationNotificationCategory.INVITES
        routing.setScope(first, category, ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT).getOrThrow()

        val firstRows = routing.settings(first)
        val secondRows = routing.settings(second)

        assertEquals(
            ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
            firstRows.single { it.channel == category.channel }.scope,
        )
        assertEquals(
            ConversationNotificationScope.USE_GLOBAL_DEFAULT,
            secondRows.single { it.channel == category.channel }.scope,
        )
    }

    private fun restartRouting(): ConversationNotificationRouting =
        ConversationNotificationRouting(
            context,
            ConversationNotificationRoutingPreferences(context, preferences),
        )

    private fun descriptor(
        shortcutId: String,
        isDm: Boolean = false,
        vibration: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ): NotificationConversationDescriptor =
        NotificationConversationDescriptor(
            shortcutId = shortcutId,
            isDm = isDm,
            title = "Family",
            primaryVibrationPattern = vibration,
        )
}
