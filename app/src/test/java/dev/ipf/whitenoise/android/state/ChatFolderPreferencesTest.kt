package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatFolderPreferencesTest {
    private lateinit var store: ChatFolderPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = ChatFolderPreferences(context)
    }

    @Test
    fun firstReadSeedsTheThreeSystemFoldersInOrder() {
        val folders = store.foldersFor("acct-a")

        assertEquals(
            listOf(SystemFolderKind.UNREAD, SystemFolderKind.ARCHIVED, SystemFolderKind.GROUPS),
            folders.map { it.systemKind },
        )
        // Each default seeds with its old chip behavior expressed as a rule.
        assertEquals(
            ChatFolderRule(unreadOnly = true, includeMuted = true),
            store.folderRule("acct-a", ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID),
        )
        assertEquals(
            ChatFolderRule(archivedOnly = true, includeMuted = true),
            store.folderRule("acct-a", ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID),
        )
        assertEquals(
            ChatFolderRule(groupsOnly = true, includeMuted = true),
            store.folderRule("acct-a", ChatFolderPreferences.SYSTEM_FOLDER_GROUPS_ID),
        )
        // Seeding persists: a fresh store instance reads the same state back.
        val reloaded = ChatFolderPreferences(ApplicationProvider.getApplicationContext())
        assertEquals(folders, reloaded.foldersFor("acct-a"))
    }

    @Test
    fun crudRoundTripsAcrossStoreInstances() {
        val created = store.createFolder("acct-a", "Work", "Team chats")
        assertNotNull(created)
        val id = created!!.id

        assertTrue(store.renameFolder("acct-a", id, "Work stuff"))
        assertTrue(store.editFolderDescription("acct-a", id, "All of it"))
        assertTrue(store.setChatInFolder("acct-a", id, "GROUP1", included = true))

        val reloaded = ChatFolderPreferences(ApplicationProvider.getApplicationContext())
        val folder = reloaded.foldersFor("acct-a").first { it.id == id }
        assertEquals("Work stuff", folder.name)
        assertEquals("All of it", folder.description)
        assertEquals(3, folder.order)
        assertEquals(setOf("group1"), reloaded.membershipFor("acct-a", id))

        assertTrue(reloaded.setChatInFolder("acct-a", id, "group1", included = false))
        assertEquals(emptySet<String>(), reloaded.membershipFor("acct-a", id))
        assertTrue(reloaded.deleteFolder("acct-a", id))
        assertTrue(reloaded.foldersFor("acct-a").none { it.id == id })
    }

    @Test
    fun deletingAFolderPurgesItsMembershipAndRuleKeysFromDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = store.createFolder("acct-a", "Work")!!
        store.setChatInFolder("acct-a", folder.id, "g1", included = true)
        store.setFolderRule("acct-a", folder.id, ChatFolderRule(setOf("aa"), unreadOnly = false, includeMuted = true))

        assertTrue(store.deleteFolder("acct-a", folder.id))

        val raw = context.getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
        assertTrue(raw.all.keys.none { it.contains(folder.id) })
    }

    @Test
    fun seededDefaultsRenameRuleEditAndDeletePersistAcrossInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store.foldersFor("acct-a")
        val unreadId = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
        val groupsId = ChatFolderPreferences.SYSTEM_FOLDER_GROUPS_ID

        assertTrue(store.renameFolder("acct-a", unreadId, "Catch up"))
        assertTrue(store.setFolderRule("acct-a", unreadId, ChatFolderRule(unreadOnly = true, keyword = "work")))
        assertTrue(store.deleteFolder("acct-a", groupsId))

        val reloaded = ChatFolderPreferences(context)
        val folders = reloaded.foldersFor("acct-a")
        assertEquals("Catch up", folders.first { it.id == unreadId }.name)
        assertEquals(SystemFolderKind.UNREAD, folders.first { it.id == unreadId }.systemKind)
        assertEquals(
            ChatFolderRule(unreadOnly = true, keyword = "work"),
            reloaded.folderRule("acct-a", unreadId),
        )
        assertTrue(folders.none { it.id == groupsId })
    }

    @Test
    fun deletingEveryFolderIsAValidStateThatSurvivesReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store.foldersFor("acct-a").forEach { assertTrue(store.deleteFolder("acct-a", it.id)) }

        assertEquals(emptyList<ChatFolder>(), store.foldersFor("acct-a"))
        // A stored empty list is user intent, not corruption — no reseeding
        // on a fresh instance (a reload/app-update stand-in).
        val reloaded = ChatFolderPreferences(context)
        assertEquals(emptyList<ChatFolder>(), reloaded.foldersFor("acct-a"))
    }

    @Test
    fun restoreDefaultsReAddsOnlyWhatIsMissingAndIsIdempotent() {
        store.foldersFor("acct-a")
        val unreadId = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
        assertTrue(store.renameFolder("acct-a", ChatFolderPreferences.SYSTEM_FOLDER_GROUPS_ID, "Teams"))
        assertTrue(store.deleteFolder("acct-a", unreadId))

        assertTrue(store.restoreDefaultFolders("acct-a"))

        val folders = store.foldersFor("acct-a")
        // The restored default re-appears at the end with its default rule;
        // the surviving (renamed) defaults are untouched.
        assertEquals(unreadId, folders.last().id)
        assertEquals(
            ChatFolderRule(unreadOnly = true, includeMuted = true),
            store.folderRule("acct-a", unreadId),
        )
        assertEquals("Teams", folders.first { it.systemKind == SystemFolderKind.GROUPS }.name)
        // Nothing missing → nothing to do, and no duplicates ever.
        assertFalse(store.restoreDefaultFolders("acct-a"))
        assertEquals(folders, store.foldersFor("acct-a"))
    }

    @Test
    fun legacyAccountsGainDefaultRulesExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Hand-write a pre-rule account: seeded folders on disk, no rule keys,
        // no version stamp — the shape existing installs carry.
        val legacyFolders =
            """[{"id":"system:unread","name":"","description":"","order":0,"systemKind":"UNREAD"},""" +
                """{"id":"system:archived","name":"","description":"","order":1,"systemKind":"ARCHIVED"},""" +
                """{"id":"system:groups","name":"","description":"","order":2,"systemKind":"GROUPS"}]"""
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .putString("cf:acct-legacy:folders", legacyFolders)
            .commit()

        val migrated = ChatFolderPreferences(context)
        val unreadId = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
        migrated.foldersFor("acct-legacy")
        assertEquals(
            ChatFolderRule(unreadOnly = true, includeMuted = true),
            migrated.folderRule("acct-legacy", unreadId),
        )

        // Clearing a seeded rule afterwards is user intent — the one-time
        // backfill must not resurrect it on the next load.
        assertTrue(migrated.setFolderRule("acct-legacy", unreadId, null))
        val reloaded = ChatFolderPreferences(context)
        assertNull(reloaded.folderRule("acct-legacy", unreadId))
    }

    @Test
    fun reorderAssignsPositionsAndKeepsUnmentionedFoldersBehind() {
        val custom = store.createFolder("acct-a", "Family")!!

        assertTrue(
            store.reorderFolders(
                "acct-a",
                listOf(custom.id, ChatFolderPreferences.SYSTEM_FOLDER_GROUPS_ID),
            ),
        )

        val ordered = store.foldersFor("acct-a").map { it.id }
        assertEquals(custom.id, ordered[0])
        assertEquals(ChatFolderPreferences.SYSTEM_FOLDER_GROUPS_ID, ordered[1])
        assertEquals(4, ordered.size)
    }

    @Test
    fun rulesStoreAndClearPerFolder() {
        val folder = store.createFolder("acct-a", "VIPs")!!
        val rule =
            ChatFolderRule(
                includeMemberPubkeys = setOf("aa", "bb"),
                unreadOnly = true,
                includeMuted = false,
                keyword = "work",
            )

        assertTrue(store.setFolderRule("acct-a", folder.id, rule))
        assertEquals(rule, store.folderRule("acct-a", folder.id))
        // Rules ride the state flow so folder consumers observe rule edits.
        assertEquals(
            rule,
            store.state.value
                .getValue("acct-a")
                .rules[folder.id],
        )
        assertTrue(store.setFolderRule("acct-a", folder.id, null))
        assertNull(store.folderRule("acct-a", folder.id))
        assertNull(
            store.state.value
                .getValue("acct-a")
                .rules[folder.id],
        )
    }

    @Test
    fun rulesReloadFromDiskAndAbsentFieldsDefaultOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = store.createFolder("acct-a", "VIPs")!!
        // A rule persisted with only the member list — newer fields absent.
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .putString("cf:acct-a:r:${folder.id}", """{"includeMemberPubkeys":["aa"]}""")
            .commit()

        val reloaded = ChatFolderPreferences(context)

        assertEquals(
            ChatFolderRule(includeMemberPubkeys = setOf("aa"), unreadOnly = false, includeMuted = false),
            reloaded.folderRule("acct-a", folder.id),
        )
    }

    @Test
    fun accountsAreIsolatedAndClearable() {
        val a = store.createFolder("acct-a", "Mine")!!
        store.createFolder("acct-b", "Theirs")
        assertTrue(store.setChatInFolder("acct-a", a.id, "g1", included = true))

        assertTrue(store.clearAllForAccount("acct-a"))

        // acct-a is reseeded fresh (the wipe removed its seed marker too);
        // acct-b is untouched.
        assertTrue(store.foldersFor("acct-a").all { it.systemKind != null })
        assertTrue(store.foldersFor("acct-b").any { it.name == "Theirs" })
    }
}
