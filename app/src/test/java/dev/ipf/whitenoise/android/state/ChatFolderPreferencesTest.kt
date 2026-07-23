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
        assertTrue(folders.all { it.isSystem })
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
    fun systemFoldersCannotBeDeletedOrLoseTheirKind() {
        val system = store.foldersFor("acct-a").first()

        assertFalse(store.deleteFolder("acct-a", system.id))
        assertTrue(store.foldersFor("acct-a").any { it.id == system.id })
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
            )

        assertTrue(store.setFolderRule("acct-a", folder.id, rule))
        assertEquals(rule, store.folderRule("acct-a", folder.id))
        assertTrue(store.setFolderRule("acct-a", folder.id, null))
        assertNull(store.folderRule("acct-a", folder.id))
    }

    @Test
    fun accountsAreIsolatedAndClearable() {
        val a = store.createFolder("acct-a", "Mine")!!
        store.createFolder("acct-b", "Theirs")
        assertTrue(store.setChatInFolder("acct-a", a.id, "g1", included = true))

        assertTrue(store.clearAllForAccount("acct-a"))

        // acct-a is reseeded fresh; acct-b is untouched.
        assertTrue(store.foldersFor("acct-a").all { it.isSystem })
        assertTrue(store.foldersFor("acct-b").any { it.name == "Theirs" })
    }
}
