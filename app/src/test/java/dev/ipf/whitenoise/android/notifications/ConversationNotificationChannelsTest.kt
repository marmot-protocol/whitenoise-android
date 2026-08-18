package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNotificationChannelsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun globalChannelNamesMakeTheirScopeExplicitWithoutChangingStableIds() {
        NotificationChannels.ensureChannels(context)

        assertEquals(
            "Global defaults",
            manager.notificationChannelGroups
                .single { it.id == NotificationChannels.GLOBAL_DEFAULTS_GROUP_ID }
                .name
                .toString(),
        )
        assertEquals(
            "Reactions · Default for all chats",
            manager.getNotificationChannel(NotificationChannelSpec.REACTIONS.id).name.toString(),
        )
        assertEquals(
            NotificationChannels.GLOBAL_DEFAULTS_GROUP_ID,
            manager.getNotificationChannel(NotificationChannelSpec.REACTIONS.id).group,
        )
        assertEquals(
            "App updates · App-wide",
            manager.getNotificationChannel(NotificationChannelSpec.APP_UPDATES.id).name.toString(),
        )
        assertEquals("reactions_v2", NotificationChannelSpec.REACTIONS.id)
    }

    @Test
    fun globalChannelCopyRefreshPreservesUserOwnedAlertSettings() {
        val customSound = Uri.parse("content://test/global-custom-sound")
        val legacy =
            NotificationChannel(
                NotificationChannelSpec.MENTIONS.id,
                "Mentions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(customSound, audioAttributes)
                enableVibration(false)
                setShowBadge(false)
            }
        manager.createNotificationChannel(legacy)

        NotificationChannels.ensureChannels(context)

        val refreshed = manager.getNotificationChannel(NotificationChannelSpec.MENTIONS.id)
        assertEquals("Mentions · Default for all chats", refreshed.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, refreshed.importance)
        assertEquals(customSound, refreshed.sound)
        assertFalse(refreshed.shouldVibrate())
        assertFalse(refreshed.canShowBadge())
        assertEquals(NotificationChannels.GLOBAL_DEFAULTS_GROUP_ID, refreshed.group)
    }

    @Test
    fun conversationChannelIdIsDeterministicAndScopedToParentAndShortcut() {
        val id = ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-abc")

        assertEquals(id, ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-abc"))
        assertNotEquals(id, ConversationNotificationChannels.conversationChannelId("mentions", "conversation-abc"))
        assertNotEquals(id, ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-xyz"))
    }

    @Test
    fun customPatternsResolveToStableBoundedChannelVersions() {
        val defaultId =
            ConversationNotificationChannels.conversationChannelId(
                "messages_group",
                "conversation-abc",
                ConversationVibrationPattern.SYSTEM_DEFAULT,
            )
        val shortId =
            ConversationNotificationChannels.conversationChannelId(
                "messages_group",
                "conversation-abc",
                ConversationVibrationPattern.SHORT,
            )

        assertEquals(
            ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-abc"),
            defaultId,
        )
        assertNotEquals(defaultId, shortId)
        assertEquals(
            shortId,
            ConversationNotificationChannels.conversationChannelId(
                "messages_group",
                "conversation-abc",
                ConversationVibrationPattern.SHORT,
            ),
        )
        assertEquals(
            4,
            ConversationVibrationPattern.entries
                .map {
                    ConversationNotificationChannels.conversationChannelId("messages_group", "conversation-abc", it)
                }.toSet()
                .size,
        )
    }

    @Test
    @SuppressLint("NewApi")
    fun api34UsesWaveformAndApi35UsesVibrationEffectWithWaveformFallback() {
        val api34 = NotificationChannel("api34", "API 34", NotificationManager.IMPORTANCE_DEFAULT)
        val api35 = NotificationChannel("api35", "API 35", NotificationManager.IMPORTANCE_DEFAULT)
        val fallback = NotificationChannel("fallback", "Fallback", NotificationManager.IMPORTANCE_DEFAULT)

        applyConversationVibration(api34, ConversationVibrationPattern.DOUBLE, sdkInt = 34)
        applyConversationVibration(api35, ConversationVibrationPattern.DOUBLE, sdkInt = 35)
        applyConversationVibration(
            fallback,
            ConversationVibrationPattern.DOUBLE,
            sdkInt = 35,
            vibrationEffectFactory = { null },
        )

        assertEquals(listOf(0L, 100L, 100L, 100L), api34.vibrationPattern!!.toList())
        assertNotNull(api35.vibrationEffect)
        assertEquals(listOf(0L, 100L, 100L, 100L), fallback.vibrationPattern!!.toList())
    }

    @Test
    fun changingPatternCreatesANewChannelAndCopiesUnrelatedAlertSettings() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-versioned"
        val originalId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_group",
                conversationShortcutId = shortcut,
                vibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
            )!!
        val original = manager.getNotificationChannel(originalId)
        val customSound = Uri.parse("content://test/custom-sound")
        original.setSound(customSound, original.audioAttributes)
        original.enableLights(true)
        original.lightColor = 0x00ff00
        original.setShowBadge(false)
        manager.createNotificationChannel(original)

        val customId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_group",
                conversationShortcutId = shortcut,
                vibrationPattern = ConversationVibrationPattern.DOUBLE,
                sourceVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
            )!!

        assertNotEquals(originalId, customId)
        assertNotNull(manager.getNotificationChannel(originalId))
        assertEquals(listOf(0L, 150L), manager.getNotificationChannel(originalId).vibrationPattern!!.toList())
        val custom = manager.getNotificationChannel(customId)
        assertEquals(original.importance, custom.importance)
        assertEquals(original.sound, custom.sound)
        assertEquals(original.canShowBadge(), custom.canShowBadge())
        assertEquals(original.shouldShowLights(), custom.shouldShowLights())
        assertEquals(original.lightColor, custom.lightColor)
        assertEquals(listOf(0L, 100L, 100L, 100L), custom.vibrationPattern!!.toList())
    }

    @Test
    fun returningToSystemDefaultKeepsConversationOverridesAndRestoresParentVibration() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-return-default"
        val customId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_group",
                conversationShortcutId = shortcut,
                vibrationPattern = ConversationVibrationPattern.DOUBLE,
            )!!
        val custom = manager.getNotificationChannel(customId)
        val customSound = Uri.parse("content://test/conversation-sound")
        custom.setSound(customSound, custom.audioAttributes)
        custom.setShowBadge(false)
        manager.createNotificationChannel(custom)

        val defaultId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_group",
                conversationShortcutId = shortcut,
                vibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
                sourceVibrationPattern = ConversationVibrationPattern.DOUBLE,
            )!!

        val restored = manager.getNotificationChannel(defaultId)
        val parent = manager.getNotificationChannel("messages_group")
        assertEquals(customSound, restored.sound)
        assertFalse(restored.canShowBadge())
        assertEquals(parent.shouldVibrate(), restored.shouldVibrate())
        assertEquals(parent.vibrationPattern!!.toList(), restored.vibrationPattern!!.toList())
    }

    @Test
    fun customPatternOnlyVersionsThePrimaryMessageChannel() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-primary-only"

        ConversationNotificationChannels.ensureConversationChannels(
            context = context,
            conversationShortcutId = shortcut,
            isDm = false,
            primaryVibrationPattern = ConversationVibrationPattern.LONG,
        )

        assertNotNull(
            conversationChannel(
                "messages_group",
                shortcut,
                ConversationVibrationPattern.LONG,
            ),
        )
        assertNull(
            conversationChannel(
                "mentions",
                shortcut,
                ConversationVibrationPattern.LONG,
            ),
        )
        assertNull(conversationChannel("mentions", shortcut))
    }

    @Test
    fun effectiveVibrationReflectsAndroidDisablingTheActiveChannel() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-effective"
        val channelId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_group",
                conversationShortcutId = shortcut,
                vibrationPattern = ConversationVibrationPattern.DOUBLE,
            )!!

        val selected =
            ConversationNotificationChannels.effectiveVibration(
                context = context,
                conversationShortcutId = shortcut,
                isDm = false,
                selectedPattern = ConversationVibrationPattern.DOUBLE,
            )
        assertEquals(ConversationVibrationPattern.DOUBLE, selected.pattern)
        assertTrue(selected.enabled)

        val disabled = manager.getNotificationChannel(channelId)
        disabled.enableVibration(false)
        manager.createNotificationChannel(disabled)

        val effective =
            ConversationNotificationChannels.effectiveVibration(
                context = context,
                conversationShortcutId = shortcut,
                isDm = false,
                selectedPattern = ConversationVibrationPattern.DOUBLE,
            )
        assertFalse(effective.enabled)
        assertNull(effective.pattern)
        assertTrue(effective.overriddenByAndroid)
    }

    @Test
    fun systemDefaultEffectiveStateReflectsAnAndroidWaveformOverride() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-default-overridden"
        val channelId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "messages_group",
                conversationShortcutId = shortcut,
            )!!
        val overridden = manager.getNotificationChannel(channelId)
        overridden.vibrationPattern = ConversationVibrationPattern.SHORT.waveform
        manager.createNotificationChannel(overridden)

        val effective =
            ConversationNotificationChannels.effectiveVibration(
                context = context,
                conversationShortcutId = shortcut,
                isDm = false,
                selectedPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
            )

        assertEquals(ConversationVibrationPattern.SHORT, effective.pattern)
        assertTrue(effective.enabled)
        assertTrue(effective.overriddenByAndroid)
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
    fun ensureCreatesOnlyTheRequiredGroupMessageConversationChannel() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-group"

        ConversationNotificationChannels.ensureConversationChannels(context, shortcut, isDm = false)

        val groupChannel = conversationChannel(NotificationChannelSpec.GROUP_MESSAGES.id, shortcut)
        val mentionChannel = conversationChannel(NotificationChannelSpec.MENTIONS.id, shortcut)
        assertNotNull(groupChannel)
        assertEquals(NotificationChannelSpec.GROUP_MESSAGES.id, groupChannel!!.parentChannelId)
        assertEquals(shortcut, groupChannel.conversationId)
        assertNull(groupChannel.group)
        assertNull(mentionChannel)
        assertNull(conversationChannel(NotificationChannelSpec.REACTIONS.id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.INVITES.id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.AGENT_ACTIVITY.id, shortcut))
        // A group conversation never receives on the DM parent, so no DM child.
        assertNull(conversationChannel(ConversationNotificationChannels.primaryMessageParent(isDm = true).id, shortcut))
    }

    @Test
    fun ensureCreatesOnlyTheRequiredDmMessageConversationChannel() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-dm"

        ConversationNotificationChannels.ensureConversationChannels(context, shortcut, isDm = true)

        assertNotNull(conversationChannel(ConversationNotificationChannels.primaryMessageParent(isDm = true).id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.MENTIONS.id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.REACTIONS.id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.INVITES.id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.AGENT_ACTIVITY.id, shortcut))
        assertNull(conversationChannel(NotificationChannelSpec.GROUP_MESSAGES.id, shortcut))
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
    fun ensureWithoutTitlePreservesExistingCustomChannelCopy() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-custom-copy-preserved"
        val convId =
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = "reactions_v2",
                conversationShortcutId = shortcut,
                conversationTitle = "Green Orca",
            )!!

        ConversationNotificationChannels.ensureConversationChannel(
            context = context,
            parentChannelId = "reactions_v2",
            conversationShortcutId = shortcut,
        )

        val preserved = manager.getNotificationChannel(convId)
        assertEquals("Green Orca · Reactions (custom)", preserved.name.toString())
        assertEquals(
            "Custom Reactions alert behavior for Green Orca",
            preserved.description,
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

    @Test
    fun conversationChannelDoesNotClaimDndBypass() {
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-dnd"

        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)

        assertFalse(manager.getNotificationChannel(convId!!).canBypassDnd())
    }

    @Test
    fun conversationChannelCopiesAnEffectiveParentDndBypass() {
        Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(true)
        NotificationChannels.ensureChannels(context)
        val parent = manager.getNotificationChannel("messages_group")
        parent.setBypassDnd(true)
        manager.createNotificationChannel(parent)

        val convId =
            ConversationNotificationChannels.ensureConversationChannel(
                context,
                "messages_group",
                "conversation-dnd-parent",
            )

        assertTrue(manager.getNotificationChannel(convId!!).canBypassDnd())
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
        Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(true)
        NotificationChannels.ensureChannels(context)
        val shortcut = "conversation-user-set"
        val convId = ConversationNotificationChannels.ensureConversationChannel(context, "messages_group", shortcut)!!
        val customised = manager.getNotificationChannel(convId)
        customised.enableVibration(false)
        customised.setBypassDnd(true)
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
        assertTrue(updated.canBypassDnd())
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
        vibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ) = manager.getNotificationChannel(
        ConversationNotificationChannels.conversationChannelId(parentChannelId, shortcut, vibrationPattern),
    )
}
