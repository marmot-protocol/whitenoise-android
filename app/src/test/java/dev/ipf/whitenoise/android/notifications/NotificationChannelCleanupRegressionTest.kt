package dev.ipf.whitenoise.android.notifications

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
import dev.ipf.whitenoise.android.state.LocalGroupDeleteCleanupJournal
import dev.ipf.whitenoise.android.state.PendingLocalGroupDeleteCleanup
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for multi-account upgrade cleanup, write-lock races against destructive
 * cleanup, and pre-redaction/enumeration failure retry retention.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationChannelCleanupRegressionTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager
        get() = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val authorA = "a".repeat(64)
    private val defaultPattern = ConversationVibrationPattern.SYSTEM_DEFAULT
    private val doublePattern = ConversationVibrationPattern.DOUBLE

    /** Resets platform shortcuts, channels, vibration preferences, and owner records. */
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
     * An upgraded multi-account install may hold vibration and reaction channels with neither a
     * shortcut nor an ownership record. Signing out their owner with known groups and stored
     * vibration preferences removes those channels while keeping the other signed-in account intact.
     */
    @Test
    fun twoAccountUpgradeSignOutDeletesChannelsWithoutShortcutsOrOwnerRecords() {
        val upgradeId = checkNotNull(conversationShortcutId("account-a", "group-upgrade"))
        val upgradeReactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-upgrade"))
        val upgradeVib =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, upgradeId, "Upgrade Secret", doublePattern)
        val upgradeReaction = convChannel(NotificationChannelSpec.REACTIONS, upgradeReactionId, "Upgrade Reaction")

        val vibPrefOnlyId = checkNotNull(conversationShortcutId("account-a", "group-vib-pref"))
        val vibPrefOnlyChannel =
            convChannel(NotificationChannelSpec.DIRECT_MESSAGES, vibPrefOnlyId, "Vib Pref Secret", doublePattern)
        ConversationVibrationPreferences(context).setPattern("account-a", "GROUP-VIB-PREF", doublePattern)

        val retainedId = checkNotNull(conversationShortcutId("account-b", "group-upgrade"))
        val retainedChannel =
            convChannel(
                NotificationChannelSpec.GROUP_MESSAGES,
                retainedId,
                "Retained Account B",
                doublePattern,
                accountRef = "account-b",
            )
        val retainedSound = Uri.parse("content://sounds/retained-upgrade")
        customizeChannel(retainedChannel, retainedSound, NotificationManager.IMPORTANCE_LOW)

        assertTrue(ownerPrefs().getStringSet(sha256Hex("account-a"), emptySet()).isNullOrEmpty())

        clearRevokedConversationShortcutsForAccount(
            context = context,
            accountRef = "account-a",
            includeUnscopedLegacy = false,
            knownGroupIds = listOf("group-upgrade"),
        )

        assertNull(manager.getNotificationChannel(upgradeVib))
        assertNull(manager.getNotificationChannel(upgradeReaction))
        assertNull(manager.getNotificationChannel(vibPrefOnlyChannel))
        assertNoChannelContainsText("Upgrade Secret", "Upgrade Reaction", "Vib Pref Secret")
        val surviving = checkNotNull(manager.getNotificationChannel(retainedChannel))
        assertEquals(retainedSound, surviving.sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, surviving.importance)
    }

    /**
     * In-flight channel writes that already captured a pre-cleanup epoch and wait behind
     * `mutationLock` must not recreate deleted conversation or profile channels after cleanup wins.
     */
    @Test
    fun staleChannelWritesWaitingBehindCleanupDoNotRecreateDeletedChannels() {
        val convId = checkNotNull(conversationShortcutId("account-a", "group-race"))
        val initialConv =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, convId, "Race Chat", accountRef = "account-a")
        val initialProfile = profileChannel("account-a", authorA, "Race Alice")
        val convResult = AtomicReference<String?>("pending")
        val profileResult = AtomicReference<Result<String>?>(null)
        val started = CountDownLatch(2)
        val convWriter =
            Thread {
                started.countDown()
                convResult.set(
                    ConversationNotificationChannels.ensureConversationChannel(
                        context,
                        NotificationChannelSpec.GROUP_MESSAGES.id,
                        convId,
                        "Race Chat",
                        doublePattern,
                        accountRef = "account-a",
                    ),
                )
            }
        val profileWriter =
            Thread {
                started.countDown()
                profileResult.set(
                    runCatching {
                        ProfileNotificationChannels(context)
                            .customize("account-a", authorA, "Race Alice", doublePattern, defaultPattern)
                    },
                )
            }
        synchronized(UserEventNotificationGroup.mutationLock) {
            convWriter.start()
            profileWriter.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            waitUntilBlocked(convWriter)
            waitUntilBlocked(profileWriter)
            assertTrue(deleteConversationNotificationChannels(context, "account-a", "group-race"))
            assertTrue(deleteAccountNotificationChannels(context, "account-a", emptyList(), emptyList(), false))
        }
        convWriter.join(2_000)
        profileWriter.join(2_000)
        assertNull(convResult.get())
        assertTrue(checkNotNull(profileResult.get()).isFailure)
        assertNull(manager.getNotificationChannel(initialConv))
        assertNull(manager.getNotificationChannel(initialProfile))
        assertNoChannelContainsText("Race Chat", "Race Alice")
        assertTrue(ownerPrefs().getStringSet(sha256Hex("account-a"), emptySet()).isNullOrEmpty())
        assertNotNull(
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, convId, "Fresh Chat", accountRef = "account-a"),
        )
    }

    /**
     * Enumeration and pre-redaction failures propagate an incomplete result and retain departed
     * account ownership records (including profile-only departures) until retry succeeds.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun enumerationAndRedactionFailuresPropagateIncompleteAndRetryDeletesChannels() {
        val convId = checkNotNull(conversationShortcutId("account-a", "group-redact"))
        val convCh =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, convId, "Secret Redact Chat", accountRef = "account-a")
        val profileCh = profileChannel("account-a", authorA, "Secret Redact Alice")
        val profileOnlyCh = profileChannel("account-profile-only", authorA, "Profile Only Secret")

        try {
            FailingDeleteNotificationManager.failingCreateChannelIds = setOf(convCh, profileCh, profileOnlyCh)
            assertFalse(deleteConversationNotificationChannels(context, "account-a", "group-redact"))
            assertTrue(
                checkNotNull(manager.getNotificationChannel(convCh)).name.toString().contains("Secret Redact Chat"),
            )
            assertFalse(
                deleteAccountNotificationChannels(
                    context = context,
                    accountRef = "account-a",
                    shortcuts = emptyList(),
                    accountShortcutIds = emptyList(),
                    includeUnscopedLegacy = false,
                ),
            )
            assertFalse(
                deleteAccountNotificationChannels(
                    context = context,
                    accountRef = "account-profile-only",
                    shortcuts = emptyList(),
                    accountShortcutIds = emptyList(),
                    includeUnscopedLegacy = false,
                ),
            )
            assertFalse(retainConversationChannelOwners(context, listOf("account-b")))
            assertTrue(ownerPrefs().contains(sha256Hex("account-a")))
            assertTrue(ownerPrefs().contains(sha256Hex("account-profile-only")))

            FailingDeleteNotificationManager.reset()
            FailingDeleteNotificationManager.failEnumeration = true
            assertFalse(deleteConversationNotificationChannels(context, "account-a", "group-redact"))
            assertFalse(retainConversationChannelOwners(context, listOf("account-b")))
            assertTrue(ownerPrefs().contains(sha256Hex("account-a")))
            assertTrue(ownerPrefs().contains(sha256Hex("account-profile-only")))
        } finally {
            FailingDeleteNotificationManager.reset()
        }

        assertTrue(retainConversationChannelOwners(context, listOf("account-b")))
        assertNull(manager.getNotificationChannel(convCh))
        assertNull(manager.getNotificationChannel(profileCh))
        assertNull(manager.getNotificationChannel(profileOnlyCh))
        assertNoChannelContainsText("Secret Redact Chat", "Secret Redact Alice", "Profile Only Secret")
        assertFalse(ownerPrefs().contains(sha256Hex("account-a")))
        assertFalse(ownerPrefs().contains(sha256Hex("account-profile-only")))
    }

    /**
     * When account channel redaction fails, sign-out relabels the account's shortcuts instead of removing them,
     * so Android cannot soft-delete the named channel; retention then deletes the channel and the shortcut.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun failedAccountRedactionRelabelsShortcutsUntilRetentionRemovesThem() {
        val convId = checkNotNull(conversationShortcutId("account-a", "group-relabel"))
        val channel =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, convId, "Relabel Chat", accountRef = "account-a")
        val scope = checkNotNull(conversationShortcutAccountScope("account-a"))
        ShortcutManagerCompat.pushDynamicShortcut(
            context,
            ShortcutInfoCompat
                .Builder(context, convId)
                .setShortLabel("Relabel Chat")
                .setIntent(buildShareShortcutIntent(context))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                .setLongLived(true)
                .setExtras(conversationShortcutAccountScopeExtras(scope))
                .build(),
        )

        FailingDeleteNotificationManager.failingChannelIds = setOf(channel)
        try {
            clearRevokedConversationShortcutsForAccount(context, "account-a", includeUnscopedLegacy = false)
        } finally {
            FailingDeleteNotificationManager.failingChannelIds = emptySet()
        }
        val kept = ShortcutManagerCompat.getDynamicShortcuts(context).single { it.id == convId }
        assertEquals(context.getString(R.string.app_name), kept.shortLabel.toString())

        assertTrue(retainConversationChannelOwners(context, listOf("account-b")))

        assertNull(manager.getNotificationChannel(channel))
        assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == convId })
        assertNoChannelContainsText("Relabel Chat")
    }

    /**
     * A conversation delete that fails before redaction leaves its group only in the local-delete journal.
     * Signing that account out while another stays signed in deletes the named channel from the journal's group ids.
     */
    @Test
    @Config(sdk = [36], shadows = [FailingDeleteNotificationManager::class])
    fun journaledDeleteFailureFollowedBySignOutDeletesTheNamedChannel() {
        val journalDir = File(context.cacheDir, "journal-sign-out").apply { deleteRecursively() }
        val journal = LocalGroupDeleteCleanupJournal(journalDir)
        val departedId = checkNotNull(conversationShortcutId("account-a", "group-journal"))
        val departed = convChannel(NotificationChannelSpec.GROUP_MESSAGES, departedId, "Journal Secret")
        val retainedId = checkNotNull(conversationShortcutId("account-b", "group-journal"))
        val retained =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, retainedId, "Retained B", accountRef = "account-b")

        try {
            FailingDeleteNotificationManager.failingCreateChannelIds = setOf(departed)
            assertFalse(deleteConversationNotificationChannels(context, "account-a", "group-journal"))
        } finally {
            FailingDeleteNotificationManager.reset()
        }
        journal.stage(PendingLocalGroupDeleteCleanup("account-a", "group-journal", emptyList(), emptySet()))
        journal.stage(PendingLocalGroupDeleteCleanup("account-b", "group-other", emptyList(), emptySet()))

        clearRevokedConversationShortcutsForAccount(
            context = context,
            accountRef = "account-a",
            includeUnscopedLegacy = false,
            knownGroupIds = journal.groupIdsForAccount("account-a"),
        )

        assertNull(manager.getNotificationChannel(departed))
        assertNoChannelContainsText("Journal Secret")
        assertNotNull(manager.getNotificationChannel(retained))
    }

    /**
     * Sign-out saves its capture before native revocation. If the process dies after native sign-out commits
     * but before Android cleanup, startup retention with another signer present deletes the departed account's
     * upgrade-era channels, which have no shortcut, earlier record or vibration preference. While the account
     * is still retained, as after a sign-out that never committed, its channels stay.
     */
    @Test
    fun restartBetweenNativeSignOutAndChannelCleanupDeletesTheCapturedChannels() {
        val departedId = checkNotNull(conversationShortcutId("account-a", "group-restart"))
        val departedReactionId = checkNotNull(reactionConversationShortcutId("account-a", "group-restart"))
        val departed = convChannel(NotificationChannelSpec.GROUP_MESSAGES, departedId, "Restart Secret", doublePattern)
        val departedReaction = convChannel(NotificationChannelSpec.REACTIONS, departedReactionId, "Restart Reaction")
        val retainedId = checkNotNull(conversationShortcutId("account-b", "group-restart"))
        val retained =
            convChannel(NotificationChannelSpec.GROUP_MESSAGES, retainedId, "Retained B", accountRef = "account-b")

        assertTrue(recordDepartingConversationChannelOwners(context, "account-a", setOf("group-restart")))
        assertTrue(retainConversationChannelOwners(context, listOf("account-a", "account-b")))
        assertNotNull(manager.getNotificationChannel(departed))

        // The process died before Android cleanup: startup sees account-a gone and account-b still signed in.
        assertTrue(retainConversationChannelOwners(context, listOf("account-b"), signingAccountRemains = true))

        assertNull(manager.getNotificationChannel(departed))
        assertNull(manager.getNotificationChannel(departedReaction))
        assertNoChannelContainsText("Restart Secret", "Restart Reaction")
        assertNotNull(manager.getNotificationChannel(retained))
        assertFalse(ownerPrefs().contains(sha256Hex("account-a")))
    }

    /** A last-signer departure survives a restart even when a read-only profile remains. */
    @Test
    fun restartSweepsOwnerlessChannelsAfterLastSignerLeavesReadOnlyProfile() {
        val shortcut = checkNotNull(conversationShortcutId("retired-account", "legacy-chat"))
        val channel = convChannel(NotificationChannelSpec.GROUP_MESSAGES, shortcut, "Ownerless private title")

        assertTrue(recordDepartingConversationChannelOwners(context, "account-a", emptySet(), true))
        assertTrue(retainConversationChannelOwners(context, listOf("account-a", "readonly")))
        assertNotNull(manager.getNotificationChannel(channel))

        assertTrue(retainConversationChannelOwners(context, listOf("readonly"), signingAccountRemains = false))

        assertNull(manager.getNotificationChannel(channel))
        assertNoChannelContainsText("Ownerless private title")
        assertFalse(ownerPrefs().contains(LEGACY_SWEEP_PENDING_KEY))
    }

    /**
     * When Android updates SharedPreferences memory before a disk write fails, a subsequent call with
     * the same shortcut ids must not treat the uncommitted in-memory set as durably saved.
     */
    @Test
    fun failedOwnerCommitDoesNotSatisfyLaterFastPathAndRetriesOnNextCall() {
        val shortcutId = checkNotNull(conversationShortcutId("account-a", "group-uncommitted"))
        val failingPrefs = MemoryRetainingFailingCommitPrefs(ownerPrefs())
        val failingContext =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences =
                    if (name == CONVERSATION_CHANNEL_OWNERS_PREFS) {
                        failingPrefs
                    } else {
                        super.getSharedPreferences(name, mode)
                    }
            }

        assertFalse(recordConversationChannelOwners(failingContext, "account-a", listOf(shortcutId)))
        assertEquals(1, failingPrefs.commitAttempts)
        assertTrue(failingPrefs.getStringSet(sha256Hex("account-a"), emptySet()).orEmpty().contains(shortcutId))

        // Memory already has the id, yet the second call must still attempt commit and report false.
        assertFalse(recordConversationChannelOwners(failingContext, "account-a", listOf(shortcutId)))
        assertEquals(2, failingPrefs.commitAttempts)

        // Once disk writes recover, retry commits durably and only then activates the fast path.
        failingPrefs.failCommits = false
        assertTrue(recordConversationChannelOwners(failingContext, "account-a", listOf(shortcutId)))
        assertEquals(3, failingPrefs.commitAttempts)
        assertTrue(recordConversationChannelOwners(failingContext, "account-a", listOf(shortcutId)))
        assertEquals(3, failingPrefs.commitAttempts)
    }

    /** Unaccounted channel detection covers vibration variants, unscoped shortcuts, other owners, and prefs errors. */
    @Test
    fun hasUnaccountedConversationChannelsHandlesEdgeCasesAndFailsClosedOnLookupError() {
        assertFalse(hasUnaccountedConversationNotificationChannels(context, "account-a", emptySet()))

        val otherShortcutId = checkNotNull(conversationShortcutId("account-b", "group-vib"))
        convChannel(NotificationChannelSpec.GROUP_MESSAGES, otherShortcutId, "Other Vib Chat", doublePattern)
        assertTrue(hasUnaccountedConversationNotificationChannels(context, "account-a", emptySet()))

        // An unscoped legacy shortcut carries no account scope and must remain unaccounted.
        val shortcutBuilder =
            ShortcutInfoCompat
                .Builder(context, otherShortcutId)
                .setShortLabel("Other Vib Chat")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                .setIntent(buildShareShortcutIntent(context))
                .setLongLived(true)
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcutBuilder.build())
        assertTrue(hasUnaccountedConversationNotificationChannels(context, "account-a", emptySet()))

        // A scoped shortcut for another account accounts for the channel.
        val scope = checkNotNull(conversationShortcutAccountScope("account-b"))
        val scopedShortcut =
            ShortcutInfoCompat
                .Builder(context, otherShortcutId)
                .setShortLabel("Other Vib Chat")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_whitenoise))
                .setIntent(buildShareShortcutIntent(context))
                .setLongLived(true)
                .setExtras(conversationShortcutAccountScopeExtras(scope))
                .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, scopedShortcut)
        assertFalse(hasUnaccountedConversationNotificationChannels(context, "account-a", emptySet()))

        // Without shortcuts, an owner record for another account still accounts for the channel.
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        assertTrue(recordConversationChannelOwners(context, "account-b", listOf(otherShortcutId)))
        assertFalse(hasUnaccountedConversationNotificationChannels(context, "account-a", emptySet()))

        // A failing prefs.all read must fail closed without throwing.
        val throwingContext =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences {
                    val delegate = super.getSharedPreferences(name, mode)
                    if (name != CONVERSATION_CHANNEL_OWNERS_PREFS) return delegate
                    return object : SharedPreferences by delegate {
                        override fun getAll(): MutableMap<String, *> = error("injected owner-store read failure")
                    }
                }
            }
        assertTrue(hasUnaccountedConversationNotificationChannels(throwingContext, "account-a", emptySet()))
    }

    /** Returns the hashed conversation-channel owner store cleanup reads and writes. */
    private fun ownerPrefs(): SharedPreferences {
        val name = "whitenoise.conversation_channel_owners"
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /** Creates one customized contact channel for [accountRef] and returns its id. */
    private fun profileChannel(
        accountRef: String,
        author: String,
        title: String,
    ): String {
        val channels = ProfileNotificationChannels(context)
        return channels.customize(accountRef, author, title, defaultPattern, defaultPattern)
    }

    /** Creates one `:conv:` conversation channel, recording [accountRef] ownership when given, and returns its id. */
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

    /** Applies user-visible customization to an existing channel so cleanup must handle a modified record. */
    private fun customizeChannel(
        channelId: String,
        sound: Uri,
        importance: Int,
    ) {
        val channel = checkNotNull(manager.getNotificationChannel(channelId))
        channel.importance = importance
        channel.setSound(sound, channel.audioAttributes)
        manager.createNotificationChannel(channel)
    }

    /** Waits until [thread] is blocked on a monitor, failing the test after the barrier deadline. */
    private fun waitUntilBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    /** Asserts no remaining channel name or description contains any of [forbiddenTexts]. */
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

    /** Simulates Android committing set mutations into memory even when the synchronous disk flush fails. */
    private class MemoryRetainingFailingCommitPrefs(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var failCommits: Boolean = true
        var commitAttempts: Int = 0

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putStringSet(
                    key: String?,
                    values: MutableSet<String>?,
                ): SharedPreferences.Editor = apply { editor.putStringSet(key, values) }

                override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

                override fun clear(): SharedPreferences.Editor = apply { editor.clear() }

                override fun commit(): Boolean {
                    commitAttempts += 1
                    val written = editor.commit()
                    return !failCommits && written
                }
            }
        }
    }
}
