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

    /** Seed with the unchanged legacy owner before observing an explicit editor save. */
    @Before fun setUp() {
        val delegate = context.getSharedPreferences("folder-atomic-test", Context.MODE_PRIVATE)
        delegate.edit().clear().commit()
        preferences = FolderCommitRecordingPreferences(delegate)
        store = ChatFolderPreferences(context, preferences)
        store.foldersFor(A)
        preferences.writes = 0
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

/** Record actual preference transactions, with a deterministic failure before the delegate applies any data. */
internal class FolderCommitRecordingPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var writes = 0
    var failNext = false

    override fun edit(): SharedPreferences.Editor {
        val editor = delegate.edit()
        return object : SharedPreferences.Editor by editor {
            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor =
                apply {
                    editor.putString(key, value)
                }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor =
                apply {
                    editor.putStringSet(key, values)
                }

            override fun putInt(
                key: String?,
                value: Int,
            ): SharedPreferences.Editor =
                apply {
                    editor.putInt(key, value)
                }

            override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

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
