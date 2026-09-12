package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real persisted folder store rather than a second implementation of its membership rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatFolderAssignmentTest {
    private lateinit var store: ChatFolderPreferences
    private var current = true
    private val targets = listOf("G1", "g2")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = ChatFolderPreferences(context)
    }

    private fun session(draft: ChatFolderAssignmentDraft = ChatFolderAssignmentDraft("alice", 7, targets)) =
        ChatFolderAssignmentSession("alice", targets, store, draft, 7) { current }

    @Test fun editsAreLocalAndCancelRevokesCapturedSave() {
        val folder = store.createFolder("alice", "Work")!!
        val session = session()
        session.choose(folder.id, true)
        assertEquals(emptySet<String>(), store.membershipFor("alice", folder.id))
        var leaves = 0
        session.leave { leaves++ }
        assertFalse(session.save())
        assertEquals(1, leaves)
        assertEquals(
            emptySet<String>(),
            ChatFolderPreferences(ApplicationProvider.getApplicationContext()).membershipFor("alice", folder.id),
        )
    }

    @Test fun saveTouchesOnlySelectedManualMembershipAndPreservesRules() {
        val first = store.createFolder("alice", "First")!!
        val second = store.createFolder("alice", "Second")!!
        val rule = ChatFolderRule(groupsOnly = true, keyword = "project")
        store.setFolderRule("alice", first.id, rule)
        store.setChatInFolder("alice", first.id, "outside", true)
        store.setChatInFolder("alice", first.id, "g1", true)
        store.setChatInFolder("alice", second.id, "g1", true)
        val session = session()
        session.choose(first.id, true)
        assertTrue(session.save())
        assertEquals(setOf("outside", "g1", "g2"), store.membershipFor("alice", first.id))
        assertEquals(setOf("g1"), store.membershipFor("alice", second.id))
        assertEquals(rule, store.folderRule("alice", first.id))
        assertFalse(session.save())
    }

    @Test fun currentFolderDeletionRejectsAllIntentsBeforeWriting() {
        val first = store.createFolder("alice", "First")!!
        val deleted = store.createFolder("alice", "Deleted")!!
        val session = session()
        session.choose(first.id, true)
        session.choose(deleted.id, true)
        store.deleteFolder("alice", deleted.id)
        assertFalse(session.save())
        assertTrue(store.membershipFor("alice", first.id).isEmpty())
        assertEquals(2, session.intents.size)
    }

    @Test fun alreadyAppliedIntentIsSuccessfulWithoutRewritingOtherMembers() {
        val folder = store.createFolder("alice", "Work")!!
        val session = session()
        session.choose(folder.id, true)
        store.setChatInFolder("alice", folder.id, "g1", true)
        store.setChatInFolder("alice", folder.id, "g2", true)
        assertTrue(session.save())
        assertEquals(setOf("g1", "g2"), store.membershipFor("alice", folder.id))
    }

    @Test fun ownerReplacementAndDisposalNeverWrite() {
        val folder = store.createFolder("alice", "Work")!!
        val first = session()
        first.choose(folder.id, true)
        current = false
        assertFalse(first.save())
        current = true
        val second = session()
        second.choose(folder.id, true)
        second.dispose()
        assertFalse(second.save())
        assertTrue(store.membershipFor("alice", folder.id).isEmpty())
    }

    @Test fun restoredDraftCannotBorrowDifferentAccountRuntimeOrTargetSet() {
        for (draft in listOf(
            ChatFolderAssignmentDraft("bob", 7, targets),
            ChatFolderAssignmentDraft("alice", 8, targets),
            ChatFolderAssignmentDraft("alice", 7, listOf("other")),
        )) {
            assertFalse(session(draft).isCurrent())
        }
        assertTrue(session(ChatFolderAssignmentDraft("alice", 7, listOf(" g2 ", "g1", "G1"))).isCurrent())
    }
}
