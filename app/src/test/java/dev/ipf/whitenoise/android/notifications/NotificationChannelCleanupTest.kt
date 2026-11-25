package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.buildShareShortcutIntent
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Verifies destructive notification channel cleanup on account Sign Out, Sign Out & Wipe, and
 * local conversation deletion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationChannelCleanupTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager
        get() = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val authorA = "a".repeat(64)
    private val authorB = "b".repeat(64)
    private val defaultPattern = ConversationVibrationPattern.SYSTEM_DEFAULT
    private val doublePattern = ConversationVibrationPattern.DOUBLE

    /** Resets platform shortcuts, channels, and hashed channel-owner state between test runs. */
    @Before
    fun setUp() {
        FailingDeleteNotificationManager.reset()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
        ownerPrefs().edit().clear().commit()
        val vibrationPrefs = context.getSharedPreferences("whitenoise.conversation_vibration", Context.MODE_PRIVATE)
        vibrationPrefs.edit().clear().commit()
        NotificationChannels.ensureChannels(context)
    }

    /**
     * Multi-account Sign Out / Wipe deletes only the leaving account's profile and `:conv:`
     * channels (dynamic, pinned, evicted-shortcut, vibration, and custom-category variants),
     * leaving no private nickname or title behind while preserving other accounts and globals.
     */
    @Test
    fun multiAccountSignOutDeletesOnlyLeavingAccountsProfileAndConversationChannels() {
        val leavingProfile = profileChannel("account-a", authorA, "Secret Alice")
        val leavingProfileVib = profileChannel("account-a", authorA, "Secret Alice", doublePattern)
        val retainedProfile = profileChannel("account-b", authorB, "Retained Bob")
        val retainedProfileSound = Uri.parse("content://sounds/retained-bob")
        customizeChannelSoundAndImportance(retainedProfile, retainedProfileSound, NotificationManager.IMPORTANCE_LOW)

        val dynId = checkNotNull(conversationShortcutId("account-a", "group-dyn"))
        pushScopedShortcut(dynId, "account-a", "Boardroom Secret")
        val leavingDm = convChannel(NotificationChannelSpec.DIRECT_MESSAGES, dynId, "Boardroom Secret")
        val leavingVib = convChannel(NotificationChannelSpec.DIRECT_MESSAGES, dynId, "Boardroom Secret", doublePattern)
        val leavingMentions = convChannel(NotificationChannelSpec.MENTIONS, dynId, "Boardroom Secret")

        val evictedId = checkNotNull(conversationShortcutId("account-a", "group-evicted"))
        val leavingEvicted =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, evictedId, "Evicted Project", accountRef = "account-a")
        val pinnedBaseId = checkNotNull(conversationShortcutId("account-a", "group-pinned"))
        pushScopedShortcut("$pinnedBaseId-pin-0123456789abcdef01234567", "account-a", "Pinned Family")
        val leavingPinned = convChannel(NotificationChannelSpec.GROUP_MESSAGES, pinnedBaseId, "Pinned Family")

        val retainedConvId = checkNotNull(conversationShortcutId("account-b", "group-b"))
        pushScopedShortcut(retainedConvId, "account-b", "Retained Group")
        val retainedConv =
            convChannel(
                NotificationChannelSpec.GROUP_MESSAGES,
                retainedConvId,
                "Retained Group",
                accountRef = "account-b",
            )
        val retainedConvSound = Uri.parse("content://sounds/retained-group")
        customizeChannelSoundAndImportance(retainedConv, retainedConvSound, NotificationManager.IMPORTANCE_DEFAULT)

        clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = false)

        listOf(
            leavingProfile,
            leavingProfileVib,
            leavingDm,
            leavingVib,
            leavingMentions,
            leavingEvicted,
            leavingPinned,
        ).forEach { deletedId ->
            assertNull("Expected channel $deletedId to be deleted", manager.getNotificationChannel(deletedId))
        }
        assertNoChannelContainsText("Secret Alice", "Boardroom Secret", "Evicted Project", "Pinned Family")
        val survivingProfile = checkNotNull(manager.getNotificationChannel(retainedProfile))
        assertEquals(retainedProfileSound, survivingProfile.sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, survivingProfile.importance)
        val survivingConv = checkNotNull(manager.getNotificationChannel(retainedConv))
        assertEquals(retainedConvSound, survivingConv.sound)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, survivingConv.importance)
        assertAllGlobalChannelsPresent()
    }

    /**
     * Multi-account sign-out deletes a leaving account's `-reaction` channel even when Android
     * evicted the `-reaction` shortcut and only the base shortcut, pinned shortcut, or base-only
     * owner record remains, while preserving other accounts' `-reaction` channels.
     */
    @Test
    fun multiAccountSignOutDeletesReactionChannelWithoutReactionShortcut() {
        val baseOnlyId = checkNotNull(conversationShortcutId("account-a", "group-react-base"))
        val baseOnlyReactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-react-base"))
        pushScopedShortcut(baseOnlyId, "account-a", "Reaction Base Chat")
        val baseOnlyReactionChannel =
            convChannel(NotificationChannelSpec.REACTIONS, baseOnlyReactionId, "Reaction Base Chat")

        val pinnedOnlyId = checkNotNull(conversationShortcutId("account-a", "group-react-pin"))
        val pinnedOnlyReactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-react-pin"))
        pushScopedShortcut("$pinnedOnlyId-pin-abcdef0123456789abcdef01", "account-a", "Reaction Pinned Chat")
        val pinnedOnlyReactionChannel =
            convChannel(NotificationChannelSpec.REACTIONS, pinnedOnlyReactionId, "Reaction Pinned Chat")

        val storedOnlyId = checkNotNull(conversationShortcutId("account-a", "group-react-stored"))
        val storedOnlyReactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-react-stored"))
        recordConversationChannelOwner(context, "account-a", storedOnlyId)
        val storedOnlyReactionChannel =
            convChannel(NotificationChannelSpec.REACTIONS, storedOnlyReactionId, "Reaction Stored Chat")

        val retainedBaseId = checkNotNull(conversationShortcutId("account-b", "group-react-b"))
        val retainedReactionId = checkNotNull(reactionConversationShortcutId("account-b", "group-react-b"))
        pushScopedShortcut(retainedBaseId, "account-b", "Retained Reaction Chat")
        val retainedReactionChannel =
            convChannel(NotificationChannelSpec.REACTIONS, retainedReactionId, "Retained Reaction Chat")

        clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = false)

        assertNull(manager.getNotificationChannel(baseOnlyReactionChannel))
        assertNull(manager.getNotificationChannel(pinnedOnlyReactionChannel))
        assertNull(manager.getNotificationChannel(storedOnlyReactionChannel))
        assertNoChannelContainsText("Reaction Base Chat", "Reaction Pinned Chat", "Reaction Stored Chat")
        assertNotNull(manager.getNotificationChannel(retainedReactionChannel))
        assertAllGlobalChannelsPresent()
    }

    /**
     * Channels materialized directly via `resolveForPost` or `setScope` (such as hidden-preview
     * posts, plain posts, failed shortcut publishes, or scope overrides) record ownership at
     * creation time so multi-account sign-out deletes them even with zero shortcuts published.
     */
    @Test
    fun routingResolveForPostAndSetScopeRecordOwnershipAtChannelCreation() {
        val routing = ConversationNotificationRouting(context)
        val postShortcut = checkNotNull(conversationShortcutId("account-a", "group-hidden-post"))
        val postRoute =
            routing.resolveForPost(
                channel = NotificationChannelSpec.DIRECT_MESSAGES,
                conversationShortcutId = postShortcut,
                conversationTitle = "Hidden Preview DM",
                primaryVibrationPattern = defaultPattern,
                accountRef = "account-a",
            )
        val scopeShortcut = checkNotNull(conversationShortcutId("account-a", "group-scope-only"))
        val scopeSetting =
            routing
                .setScope(
                    conversation =
                        NotificationConversationDescriptor(
                            shortcutId = scopeShortcut,
                            isDm = false,
                            title = "Scoped Mentions Group",
                            primaryVibrationPattern = defaultPattern,
                            accountRef = "account-a",
                        ),
                    category = OverridableConversationNotificationCategory.MENTIONS,
                    scope = ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
                ).getOrThrow()
        val otherShortcut = checkNotNull(conversationShortcutId("account-b", "group-other-post"))
        val otherRoute =
            routing.resolveForPost(
                channel = NotificationChannelSpec.GROUP_MESSAGES,
                conversationShortcutId = otherShortcut,
                conversationTitle = "Account B Chat",
                primaryVibrationPattern = defaultPattern,
                accountRef = "account-b",
            )

        clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = false)

        assertNull(manager.getNotificationChannel(postRoute.channelId))
        assertNull(manager.getNotificationChannel(scopeSetting.settingsTarget.channelId))
        assertNoChannelContainsText("Hidden Preview DM", "Scoped Mentions Group")
        assertNotNull(manager.getNotificationChannel(otherRoute.channelId))
        assertAllGlobalChannelsPresent()
    }

    /**
     * Last-account Sign Out / Wipe deletes unscoped legacy `:conv:` channels and channels owned by
     * stale removed accounts, then clears the entire owner store while keeping all global channels.
     */
    @Test
    fun lastAccountWipeDeletesOrphanedAndStaleOwnerChannelsAndClearsStore() {
        val soloProfile = profileChannel("account-a", authorA, "Solo Contact")
        val orphanId = checkNotNull(conversationShortcutId("legacy-account", "orphan-group"))
        val orphanChannel = convChannel(NotificationChannelSpec.GROUP_MESSAGES, orphanId, "Orphaned Legacy Chat")
        val staleId = checkNotNull(conversationShortcutId("stale-account", "stale-group"))
        val staleChannel =
            convChannel(
                NotificationChannelSpec.GROUP_MESSAGES,
                staleId,
                "Stale Removed Chat",
                accountRef = "stale-account",
            )
        val reactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-1"))
        val reactionChannel = convChannel(NotificationChannelSpec.REACTIONS, reactionId, "Solo Contact")

        clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = true)

        assertNull(manager.getNotificationChannel(soloProfile))
        assertNull(manager.getNotificationChannel(orphanChannel))
        assertNull(manager.getNotificationChannel(staleChannel))
        assertNull(manager.getNotificationChannel(reactionChannel))
        assertTrue("Expected owner store to be completely cleared", ownerPrefs().all.isEmpty())
        assertNoChannelContainsText("Solo Contact", "Orphaned Legacy Chat", "Stale Removed Chat")
        assertAllGlobalChannelsPresent()
    }

    /**
     * The last signing account's cleanup keeps a remaining read-only account's customized contact channel,
     * and account-refresh retention then deletes contact channels of accounts that are no longer present.
     */
    @Test
    fun lastSignerKeepsReadOnlyProfileChannelAndRetentionDeletesDepartedOnes() {
        val leavingProfile = profileChannel("account-a", authorA, "Solo Contact")
        val readOnlyProfile = profileChannel("read-only-account", authorA, "Kept Alice")
        val removedProfile = profileChannel("removed-account", authorB, "Forgotten Bob")

        clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = true)

        assertNull(manager.getNotificationChannel(leavingProfile))
        assertNotNull(manager.getNotificationChannel(readOnlyProfile))

        assertTrue(retainConversationChannelOwners(context, listOf("read-only-account")))

        assertNotNull(manager.getNotificationChannel(readOnlyProfile))
        assertNull(manager.getNotificationChannel(removedProfile))
        assertNoChannelContainsText("Solo Contact", "Forgotten Bob")
        assertAllGlobalChannelsPresent()
    }

    /** With no account left, account-refresh retention deletes ownerless legacy conversation channels too. */
    @Test
    fun retentionWithNoAccountsDeletesOwnerlessConversationChannels() {
        val legacyId = checkNotNull(conversationShortcutId("legacy-account", "legacy-group"))
        val legacyChannel = convChannel(NotificationChannelSpec.GROUP_MESSAGES, legacyId, "Ownerless Legacy Chat")

        assertTrue(retainConversationChannelOwners(context, emptyList()))

        assertNull(manager.getNotificationChannel(legacyChannel))
        assertNoChannelContainsText("Ownerless Legacy Chat")
        assertAllGlobalChannelsPresent()
    }

    /**
     * `retainConversationChannelOwners` prunes stale account keys not in the allowlist while
     * honoring `canRetain` so a superseded account snapshot cannot erase active ownership records.
     */
    @Test
    fun retainConversationChannelOwnersPrunesStaleKeysAndHonorsFence() {
        val keptId = checkNotNull(conversationShortcutId("account-a", "group-kept"))
        val staleId = checkNotNull(conversationShortcutId("removed-account", "group-stale"))
        recordConversationChannelOwner(context, "account-a", keptId)
        recordConversationChannelOwner(context, "removed-account", staleId)
        recordConversationChannelOwner(context, " account-untrimmed ", keptId)

        assertEquals(2, ownerPrefs().all.size)
        assertTrue(retainConversationChannelOwners(context, listOf("account-a")) { false })
        assertEquals(2, ownerPrefs().all.size)

        assertTrue(retainConversationChannelOwners(context, listOf("account-a")) { true })
        assertEquals(setOf(sha256Hex("account-a")), ownerPrefs().all.keys)
    }

    /**
     * When `NotificationManager` is unavailable, neither conversation nor account cleanup erases
     * the conversation-channel ownership store because zero OS channels were deleted.
     */
    @Test
    fun nullNotificationManagerPreservesConversationChannelOwnerStore() {
        val shortcutId = checkNotNull(conversationShortcutId("account-a", "group-null-mgr"))
        recordConversationChannelOwner(context, "account-a", shortcutId)
        val nullManagerContext = NullNotificationManagerContext(context)

        deleteConversationNotificationChannels(nullManagerContext, "account-a", "group-null-mgr")
        assertEquals(setOf(shortcutId), ownerPrefs().getStringSet(sha256Hex("account-a"), emptySet()))

        deleteAccountNotificationChannels(
            context = nullManagerContext,
            accountRef = "account-a",
            shortcuts = emptyList(),
            accountShortcutIds = listOf(shortcutId),
            includeUnscopedLegacy = true,
        )
        assertEquals(setOf(shortcutId), ownerPrefs().getStringSet(sha256Hex("account-a"), emptySet()))
    }

    /**
     * A platform exception deleting one channel does not abort deleting the remaining channels for
     * the conversation or account. The failed channel is still relabeled generically, and its owner
     * record stays until account-refresh retention prunes it or a last-account sweep clears the store.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun perChannelDeleteFailureIsolatesErrorAndRetainsOwnerStore() {
        val convId = checkNotNull(conversationShortcutId("account-a", "group-err"))
        val failingChannel =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, convId, "Error Chat", accountRef = "account-a")
        val siblingChannel =
            convChannel(NotificationChannelSpec.MENTIONS, convId, "Error Chat", accountRef = "account-a")
        val profileCh = profileChannel("account-a", authorA, "Error Profile")

        FailingDeleteNotificationManager.failingChannelIds = setOf(failingChannel)
        try {
            deleteAccountNotificationChannels(
                context = context,
                accountRef = "account-a",
                shortcuts = emptyList(),
                accountShortcutIds = listOf(convId),
                includeUnscopedLegacy = true,
            )
        } finally {
            FailingDeleteNotificationManager.failingChannelIds = emptySet()
        }

        assertNotNull(
            "Expected failing channel to remain after simulated error",
            manager.getNotificationChannel(failingChannel),
        )
        assertNull(
            "Expected sibling conversation channel to still be deleted",
            manager.getNotificationChannel(siblingChannel),
        )
        assertNull("Expected profile channel to still be deleted", manager.getNotificationChannel(profileCh))
        assertNoChannelContainsText("Error Chat", "Error Profile")
        assertEquals(
            "Expected owner store entry to remain until all deletions succeed",
            setOf(convId),
            ownerPrefs().getStringSet(sha256Hex("account-a"), emptySet()),
        )
    }

    /**
     * A failed last-signer sweep leaves a pending marker, so account-refresh retention still deletes an
     * ownerless legacy conversation channel and shortcut while only a read-only account remains.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun failedLastSignerSweepIsRetriedWhileReadOnlyAccountRemains() {
        val legacyId = checkNotNull(conversationShortcutId("legacy-account", "legacy-group"))
        val legacyChannel = convChannel(NotificationChannelSpec.GROUP_MESSAGES, legacyId, "Ownerless Legacy Chat")
        ShortcutManagerCompat.pushDynamicShortcut(
            context,
            ShortcutInfoCompat
                .Builder(context, legacyId)
                .setShortLabel("Ownerless Legacy Chat")
                .setIntent(buildShareShortcutIntent(context))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                .setLongLived(true)
                .build(),
        )

        FailingDeleteNotificationManager.failingChannelIds = setOf(legacyChannel)
        try {
            clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = true)
        } finally {
            FailingDeleteNotificationManager.failingChannelIds = emptySet()
        }
        assertNotNull(manager.getNotificationChannel(legacyChannel))

        val untrackedId = checkNotNull(conversationShortcutId("legacy-account", "untracked-group"))
        val untrackedChannel = convChannel(NotificationChannelSpec.GROUP_MESSAGES, untrackedId, "Untracked Legacy Chat")
        assertTrue(retainConversationChannelOwners(context, listOf("new-signer"), signingAccountRemains = true))
        assertNotNull("a newly joined signer blocks the unowned sweep", manager.getNotificationChannel(untrackedChannel))
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == legacyId })

        val readOnly = listOf("read-only-account")
        assertTrue(retainConversationChannelOwners(context, readOnly, signingAccountRemains = false))

        assertNull(manager.getNotificationChannel(legacyChannel))
        assertNull(manager.getNotificationChannel(untrackedChannel))
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == legacyId })
        assertNoChannelContainsText("Ownerless Legacy Chat", "Untracked Legacy Chat")
        assertAllGlobalChannelsPresent()
    }

    /**
     * Local conversation deletion removes every `:conv:` channel for that `(account, group)` pair
     * across parent categories, vibration patterns, and reaction shortcuts, while preserving other
     * conversations, other accounts, per-profile channels, and global channels.
     */
    @Test
    fun localConversationDeletionDeletesOnlyThatConversationsChannels() {
        val targetId = checkNotNull(conversationShortcutId("account-a", "group-1"))
        val targetReactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-1"))
        val targetGroup =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, targetId, "Deleted Chat", accountRef = "account-a")
        val targetVib =
            convChannel(
                NotificationChannelSpec.GROUP_MESSAGES,
                targetId,
                "Deleted Chat",
                doublePattern,
                accountRef = "account-a",
            )
        val targetMentions =
            convChannel(NotificationChannelSpec.MENTIONS, targetId, "Deleted Chat", accountRef = "account-a")
        val targetReactions =
            convChannel(NotificationChannelSpec.REACTIONS, targetReactionId, "Deleted Chat", accountRef = "account-a")

        val siblingId = checkNotNull(conversationShortcutId("account-a", "group-2"))
        val siblingChannel =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, siblingId, "Kept Sibling", accountRef = "account-a")
        val otherAcctId = checkNotNull(conversationShortcutId("account-b", "group-1"))
        val otherAcctChannel =
            convChannel(
                NotificationChannelSpec.GROUP_MESSAGES,
                otherAcctId,
                "Other Account Same Group",
                accountRef = "account-b",
            )
        val profileChannel = profileChannel("account-a", authorA, "Kept Profile")

        deleteConversationNotificationChannels(context, "account-a", "group-1")

        listOf(targetGroup, targetVib, targetMentions, targetReactions).forEach { deletedId ->
            assertNull("Expected channel $deletedId to be removed", manager.getNotificationChannel(deletedId))
        }
        assertNoChannelContainsText("Deleted Chat")
        assertNotNull(manager.getNotificationChannel(siblingChannel))
        assertNotNull(manager.getNotificationChannel(otherAcctChannel))
        assertNotNull(manager.getNotificationChannel(profileChannel))
        assertEquals(setOf(siblingId), ownerPrefs().getStringSet(sha256Hex("account-a"), emptySet()))
        assertAllGlobalChannelsPresent()
    }

    /** Returns the hashed conversation-channel owner SharedPreferences used by cleanup. */
    private fun ownerPrefs(): SharedPreferences {
        val name = "whitenoise.conversation_channel_owners"
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /** Materializes one profile notification channel for [accountRef] and [author]. */
    private fun profileChannel(
        accountRef: String,
        author: String,
        title: String,
        pattern: ConversationVibrationPattern = defaultPattern,
    ): String = ProfileNotificationChannels(context).customize(accountRef, author, title, pattern, defaultPattern)

    /** Creates one `:conv:` channel and fails fast if Android did not materialize it. */
    private fun convChannel(
        parent: NotificationChannelSpec,
        shortcutId: String,
        title: String,
        pattern: ConversationVibrationPattern = defaultPattern,
        accountRef: String? = null,
    ): String =
        checkNotNull(
            ConversationNotificationChannels.ensureConversationChannel(
                context = context,
                parentChannelId = parent.id,
                conversationShortcutId = shortcutId,
                conversationTitle = title,
                vibrationPattern = pattern,
                accountRef = accountRef,
            ),
        )

    /** Applies custom Android-owned sound and importance to verify retained channels survive untouched. */
    private fun customizeChannelSoundAndImportance(
        channelId: String,
        sound: Uri,
        importance: Int,
    ) {
        val channel = checkNotNull(manager.getNotificationChannel(channelId))
        channel.importance = importance
        channel.setSound(sound, channel.audioAttributes)
        manager.createNotificationChannel(channel)
    }

    /** Pushes a long-lived conversation shortcut stamped with [accountRef]'s hashed account scope. */
    private fun pushScopedShortcut(
        shortcutId: String,
        accountRef: String,
        label: String,
    ) {
        val scope = checkNotNull(conversationShortcutAccountScope(accountRef))
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntent(buildShareShortcutIntent(context))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                .setLongLived(true)
                .setExtras(conversationShortcutAccountScopeExtras(scope))
                .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** Asserts that no remaining OS notification channel exposes any of [forbiddenTexts] in name or description. */
    private fun assertNoChannelContainsText(vararg forbiddenTexts: String) {
        manager.notificationChannels.forEach { channel ->
            val labelText = "${channel.name}\n${channel.description.orEmpty()}"
            forbiddenTexts.forEach { forbidden ->
                assertFalse(
                    "Channel ${channel.id} still exposed '$forbidden'",
                    labelText.contains(forbidden, ignoreCase = true),
                )
            }
        }
    }

    /** Verifies every app-wide parent channel remains present after destructive cleanup. */
    private fun assertAllGlobalChannelsPresent() {
        NotificationChannelSpec.entries.forEach { spec ->
            assertNotNull("Expected global channel ${spec.id} to remain", manager.getNotificationChannel(spec.id))
        }
    }

    /** Wraps [Context] so `getSystemService(NotificationManager::class.java)` returns null while prefs work. */
    private class NullNotificationManagerContext(
        base: Context,
    ) : ContextWrapper(base) {
        /** Keeps `context.applicationContext` inside this wrapper so cleanup observes the null manager. */
        override fun getApplicationContext(): Context = this

        /** Returns null only for [Context.NOTIFICATION_SERVICE] so cleanup observes an unavailable manager. */
        override fun getSystemService(name: String): Any? {
            val unavailable = name == Context.NOTIFICATION_SERVICE
            return if (unavailable) null else super.getSystemService(name)
        }
    }
}

/** Simulates platform enumeration, redaction, and deletion failures before delegating to Robolectric. */
@Implements(NotificationManager::class)
class FailingDeleteNotificationManager : ShadowNotificationManager() {
    /** Throws when [failEnumeration] is enabled; otherwise delegates to Robolectric's shadow. */
    @Implementation
    override fun getNotificationChannels(): MutableList<NotificationChannel> {
        if (failEnumeration) {
            error("simulated platform channel enumeration failure")
        }
        return super.getNotificationChannels()
    }

    /** Throws when [channel]'s id is listed in [failingCreateChannelIds]; otherwise delegates. */
    @Implementation
    public override fun createNotificationChannel(channel: NotificationChannel?) {
        val id = channel?.id
        if (id != null && id in failingCreateChannelIds) {
            error("simulated platform channel redaction failure for $id")
        }
        super.createNotificationChannel(channel)
    }

    /** Throws when [channelId] is listed in [failingChannelIds]; otherwise delegates to Robolectric's shadow. */
    @Implementation
    public override fun deleteNotificationChannel(channelId: String?) {
        if (channelId != null && channelId in failingChannelIds) {
            error("simulated platform channel deletion failure for $channelId")
        }
        super.deleteNotificationChannel(channelId)
    }

    companion object {
        @Volatile
        var failingChannelIds: Set<String> = emptySet()

        @Volatile
        var failingCreateChannelIds: Set<String> = emptySet()

        @Volatile
        var failEnumeration: Boolean = false

        /** Resets all simulated platform failure hooks between tests. */
        fun reset() {
            failingChannelIds = emptySet()
            failingCreateChannelIds = emptySet()
            failEnumeration = false
        }
    }
}
