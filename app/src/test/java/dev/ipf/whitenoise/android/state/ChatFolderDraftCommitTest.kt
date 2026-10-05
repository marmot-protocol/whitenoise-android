package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Test the actual existing preference schema with one editor transaction and one complete StateFlow publication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatFolderDraftCommitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: FolderCommitRecordingPreferences
    private lateinit var store: ChatFolderPreferences

    /** Seeds with the unchanged legacy owner before observing an explicit editor save. */
    @Before fun setUp() {
        val delegate = context.getSharedPreferences("folder-atomic-test", Context.MODE_PRIVATE)
        delegate.edit().clear().commit()
        preferences = FolderCommitRecordingPreferences(delegate)
        store = ChatFolderPreferences(context, preferences)
        store.foldersFor(A)
        preferences.writes = 0
    }

    /** Sorting is saved atomically for custom and seeded folders, without leaking to another account. */
    @Test
    fun folderSortIsAtomicIsolatedAndSurvivesMetadataEdits() {
        val custom = store.createFolder(A, "Work")!!
        val seeded = ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID
        store.foldersFor("other")
        for (id in listOf(custom.id, seeded)) {
            preferences.writes = 0
            store.commitFolderDraft(
                A,
                id,
                null,
                "Sorted",
                setOf("chat"),
                ChatFolderRule(archivedOnly = true),
                sortOrder = ChatFolderSortOrder.NAME,
            )
            assertEquals(1, preferences.writes)
            store.commitFolderDraft(
                A,
                id,
                null,
                "Renamed description",
                setOf("chat"),
                ChatFolderRule(archivedOnly = true),
            )
        }
        store.reorderFolders(A, listOf(custom.id, seeded))
        val reloaded = ChatFolderPreferences(context, preferences)
        for (id in listOf(custom.id, seeded)) {
            assertEquals(ChatFolderSortOrder.NAME, reloaded.foldersFor(A).first { it.id == id }.sortOrder)
            assertEquals(setOf("chat"), reloaded.membershipFor(A, id))
            assertEquals(ChatFolderRule(archivedOnly = true), reloaded.folderRule(A, id))
        }
        assertEquals(ChatFolderSortOrder.RECENT, reloaded.foldersFor("other").first { it.id == seeded }.sortOrder)
    }

    /** Missing and unknown sort values preserve all legacy folder metadata and default ordering. */
    @Test
    fun oldFoldersDefaultWithoutResettingTheirDefinition() {
        val raw = context.getSharedPreferences("folder-sort-legacy", Context.MODE_PRIVATE)
        raw
            .edit()
            .putString(
                "cf:legacy:folders",
                """[{"id":"old","name":"Legacy","description":"Keep","order":8,"showWhenEmpty":true},""" +
                    """{"id":"future","name":"Future","order":9,"sortOrder":"FUTURE"}]""",
            ).commit()
        val folders = ChatFolderPreferences(context, raw).foldersFor("legacy")
        assertEquals(listOf("old", "future"), folders.map { it.id })
        assertEquals(listOf(8, 9), folders.map { it.order })
        assertEquals("Keep", folders.first().description)
        assertEquals(true, folders.first().showWhenEmpty)
        assertEquals(listOf(ChatFolderSortOrder.RECENT, ChatFolderSortOrder.RECENT), folders.map { it.sortOrder })
    }

    /** The per-account visibility setting shares the draft write and survives other folder edits. */
    @Test
    fun emptyVisibilityIsAtomicAccountScopedAndPreservedByMetadataAndReorder() {
        val folder = store.createFolder(A, "Empty")!!
        val other = store.createFolder("acct-other", "Other")!!
        preferences.writes = 0
        store.commitFolderDraft(A, folder.id, "Visible", "", emptySet(), null, showWhenEmpty = true)
        assertEquals(1, preferences.writes)
        store.commitFolderDraft(A, folder.id, "Renamed", "", emptySet(), null)
        store.reorderFolders(A, listOf(folder.id))
        val reloaded = ChatFolderPreferences(context, preferences)
        assertEquals(true, reloaded.foldersFor(A).first { it.id == folder.id }.showWhenEmpty)
        assertEquals(false, reloaded.foldersFor("acct-other").first { it.id == other.id }.showWhenEmpty)
        store.commitFolderDraft(A, folder.id, null, "", emptySet(), null, showWhenEmpty = false)
        val disabled = ChatFolderPreferences(context, preferences).foldersFor(A).first { it.id == folder.id }
        assertEquals(false, disabled.showWhenEmpty)
    }

    /** Listeners see only the old or complete new definition, and a new reader gets all fields together. */
    @Test fun updateUsesOneTransactionAndOneCompletePublication() =
        runBlocking {
            val folder = store.createFolder(A, "Before", "Old description")!!
            preferences.writes = 0
            val observed = mutableListOf<ChatFolderAccountState>()
            val collection =
                launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    store.state.collect { it[A]?.let(observed::add) }
                }
            try {
                val rule = ChatFolderRule(includeMemberPubkeys = setOf("person"), keyword = "team", includeMuted = true)
                val updated =
                    store.commitFolderDraft(
                        A,
                        folder.id,
                        " After ",
                        " New description ",
                        setOf(" G1 "),
                        rule,
                    )!!
                assertEquals(1, preferences.writes)
                assertEquals(2, observed.size)
                assertEquals(updated, observed.last().folders.first { it.id == folder.id })
                assertEquals(setOf("g1"), observed.last().membership[folder.id])
                assertEquals(rule, observed.last().rules[folder.id])
                val reloaded = ChatFolderPreferences(context, preferences)
                assertEquals("After", reloaded.foldersFor(A).first { it.id == folder.id }.name)
                assertEquals("New description", reloaded.foldersFor(A).first { it.id == folder.id }.description)
                assertEquals(setOf("g1"), reloaded.membershipFor(A, folder.id))
                assertEquals(rule, reloaded.folderRule(A, folder.id))
            } finally {
                collection.cancelAndJoin()
            }
        }

    /** Empty membership and rule removal use the same transaction as the edited text. */
    @Test fun clearingMembershipAndRuleIsAtomic() {
        val folder = store.createFolder(A, "Work")!!
        store.setChatInFolder(A, folder.id, "g1", true)
        store.setFolderRule(A, folder.id, ChatFolderRule(keyword = "old"))
        preferences.writes = 0
        store.commitFolderDraft(A, folder.id, null, "Cleared", emptySet(), null)
        assertEquals(1, preferences.writes)
        val reloaded = ChatFolderPreferences(context, preferences)
        assertEquals("Work", reloaded.foldersFor(A).first { it.id == folder.id }.name)
        assertEquals(emptySet<String>(), reloaded.membershipFor(A, folder.id))
        assertNull(reloaded.folderRule(A, folder.id))
    }

    /** Validation cannot create a replacement for a deleted target or persist a blank/new unnamed folder. */
    @Test fun invalidDraftsDoNotWriteOrPublish() {
        val before = store.state.value
        val disk = preferences.all.toMap()
        assertNull(store.commitFolderDraft(A, null, " ", "", emptySet(), null))
        assertNull(store.commitFolderDraft(A, null, null, "", emptySet(), null))
        assertNull(store.commitFolderDraft(A, "deleted", "Work", "", emptySet(), null))
        assertNull(store.commitFolderDraft("never-loaded", "deleted", "Work", "", emptySet(), null))
        assertEquals(0, preferences.writes)
        assertEquals(before, store.state.value)
        assertEquals(disk, preferences.all)
    }

    /** Failure before applying the edit retains the old state and permits the exact draft to retry. */
    @Test fun failedApplyDoesNotPublishOrPartiallyPersistDraft() {
        val folder = store.createFolder(A, "Before")!!
        preferences.writes = 0
        val before = store.state.value
        val disk = preferences.all.toMap()
        preferences.failNext = true
        assertThrows(IllegalStateException::class.java) {
            store.commitFolderDraft(A, folder.id, "After", "New", setOf("g1"), ChatFolderRule(keyword = "new"))
        }
        assertEquals(before, store.state.value)
        assertEquals(disk, preferences.all)
        assertEquals(0, preferences.writes)
        val retried =
            store.commitFolderDraft(
                A,
                folder.id,
                "After",
                "New",
                setOf("g1"),
                ChatFolderRule(keyword = "new"),
            )
        assertEquals("After", retried?.name)
        assertEquals(1, preferences.writes)
    }

    /** New folders contain every field; untouched defaults retain their ID/order and blank localized name. */
    @Test fun newDraftAndUntouchedDefaultKeepIdentitySemantics() {
        val custom = store.commitFolderDraft(A, null, "Work", "Team", setOf("g1"), ChatFolderRule(keyword = "team"))!!
        assertEquals(1, preferences.writes)
        assertEquals(setOf("g1"), store.membershipFor(A, custom.id))
        val before = store.foldersFor(A).first { it.systemKind == SystemFolderKind.UNREAD }
        val after = store.commitFolderDraft(A, before.id, null, "Changed", emptySet(), store.folderRule(A, before.id))!!
        assertEquals(before.id, after.id)
        assertEquals(before.order, after.order)
        assertEquals(before.systemKind, after.systemKind)
        assertEquals("", after.name)
    }

    /** A new cold account is rejected before even legacy default seeding can write or publish. */
    @Test fun coldNewAccountDoesNotInitializeInsideSave() {
        val before = store.state.value
        val disk = preferences.all.toMap()
        preferences.failNext = true
        assertNull(store.commitFolderDraft("cold", null, "Work", "", emptySet(), null))
        assertEquals(0, preferences.writes)
        assertEquals(before, store.state.value)
        assertEquals(disk, preferences.all)
        assertEquals(true, preferences.failNext)
    }

    /** An existing on-disk account must be initialized separately; Save performs no implicit load/publication. */
    @Test fun existingUnloadedAccountDoesNotInitializeInsideSave() {
        val folder = store.createFolder(A, "Before")!!
        val reloaded = ChatFolderPreferences(context, preferences)
        val disk = preferences.all.toMap()
        preferences.writes = 0
        preferences.failNext = true
        assertNull(reloaded.commitFolderDraft(A, folder.id, "After", "", emptySet(), null))
        assertEquals(0, preferences.writes)
        assertEquals(emptyMap<String, ChatFolderAccountState>(), reloaded.state.value)
        assertEquals(disk, preferences.all)
        assertEquals(true, preferences.failNext)
    }

    /** Once explicit opening has completed, failed Save still leaves a reloaded account's full snapshot intact. */
    @Test fun initializedReloadFailureDoesNotWriteOrPublishDraft() {
        val folder = store.createFolder(A, "Before")!!
        val reloaded = ChatFolderPreferences(context, preferences)
        reloaded.foldersFor(A)
        val before = reloaded.state.value
        val disk = preferences.all.toMap()
        preferences.writes = 0
        preferences.failNext = true
        assertThrows(IllegalStateException::class.java) {
            reloaded.commitFolderDraft(A, folder.id, "After", "New", setOf("g1"), ChatFolderRule(keyword = "new"))
        }
        assertEquals(0, preferences.writes)
        assertEquals(before, reloaded.state.value)
        assertEquals(disk, preferences.all)
    }

    private companion object {
        const val A = "atomic-account"
    }
}

/** Records actual preference transactions, with a deterministic failure before the delegate applies any data. */
internal class FolderCommitRecordingPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var writes = 0
    var failNext = false

    /** Fake preferences: opens an editor. */
    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            /** Fake preferences editor: stores a string. */
            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor =
                apply {
                    editor.putString(key, value)
                }

            /** Fake preferences editor: stores a string set. */
            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor =
                apply {
                    editor.putStringSet(key, values)
                }

            /** Fake preferences editor: stores an int. */
            override fun putInt(
                key: String?,
                value: Int,
            ): SharedPreferences.Editor =
                apply {
                    editor.putInt(key, value)
                }

            /** Fake preferences editor: removes a key. */
            override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

            /** Fake preferences editor: commits the pending writes. */
            override fun apply() {
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("Test preference failure before apply")
                }
                writes++
                editor.apply()
            }
        }
    }
}
