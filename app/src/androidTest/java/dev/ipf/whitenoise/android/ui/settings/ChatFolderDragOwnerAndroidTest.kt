package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual folder-screen pointer, owner and preference integration on a disposable emulator. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ChatFolderDragOwnerAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val account = "folder-drag-${UUID.randomUUID()}"
    private val otherAccount = "$account-other"
    private var mounted by mutableStateOf(true)
    private var fixtureStore: ChatFolderPreferences? = null

    /** Dispose the actual screen before removing only this test's generated account keys. */
    @After
    fun disposeFixture() {
        rule.mainClock.autoAdvance = true
        try {
            rule.runOnUiThread { mounted = false }
            rule.waitForIdle()
        } finally {
            fixtureStore?.clearAllForAccount(account)
            fixtureStore?.clearAllForAccount(otherAccount)
        }
    }

    /** A real drag changes only order; reopening with a fresh owner reads the saved folder definition. */
    @Test
    fun actualScreenDragSavesOrderAndReopensWithoutLosingFolderDefinition() {
        var owner by mutableStateOf(appState(account))
        val store = owner.chatFolderPreferences.also { fixtureStore = it }
        val folder = requireNotNull(store.createFolder(account, "Work", "Synthetic team chats"))
        val folderRule = ChatFolderRule(keyword = "team", includeMuted = true)
        store.setFolderRule(account, folder.id, folderRule)
        store.setChatInFolder(account, folder.id, "synthetic-chat", true)
        store.reorderFolders(
            account,
            listOf(folder.id) + store.foldersFor(account).filter { it.id != folder.id }.map { it.id },
        )
        val original = store.foldersFor(account).map { it.id }
        val otherBefore = store.foldersFor(otherAccount)
        rule.setContent { WhiteNoiseTheme { if (mounted) ChatFoldersScreen(owner, onBack = {}) } }
        dragFirstToThird(original)
        assertEquals(
            "preview must not persist an order before release",
            original,
            store.foldersFor(account).map { it.id },
        )
        rule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val expected = original.toMutableList().apply { add(2, removeAt(0)) }
        assertEquals(expected, store.foldersFor(account).map { it.id })
        assertEquals(otherBefore, store.foldersFor(otherAccount))
        rule.runOnIdle { mounted = false }
        rule.waitForIdle()
        val reader = ChatFolderPreferences(rule.activity)
        assertEquals(expected, reader.foldersFor(account).map { it.id })
        assertEquals("Synthetic team chats", reader.foldersFor(account).first { it.id == folder.id }.description)
        assertEquals(setOf("synthetic-chat"), reader.membershipFor(account, folder.id))
        assertEquals(folderRule, reader.folderRule(account, folder.id))
        rule.runOnIdle {
            owner = appState(account)
            mounted = true
        }
        rule.waitForIdle()
        val displayed =
            expected.take(3).map {
                rule
                    .onNodeWithTag("folder.row.$it")
                    .fetchSemanticsNode()
                    .boundsInRoot
                    .top
            }
        assertTrue("the reopened actual screen must show saved order", displayed.zipWithNext().all { (a, b) -> a < b })
    }

    /** Injects the gesture through the stable viewport while the actual row handle owns pointer admission. */
    private fun dragFirstToThird(ids: List<String>) {
        val origin =
            rule
                .onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG)
                .fetchSemanticsNode()
                .boundsInRoot
                .topLeft
        val start =
            rule
                .onNodeWithTag("folder.drag.${ids[0]}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .center
        val end =
            rule
                .onNodeWithTag("folder.drag.${ids[2]}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .center
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(CHAT_FOLDERS_CONTENT_TAG).performTouchInput {
            down(start - origin)
            moveTo(end - origin, 300)
        }
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    /** Injected identities avoid engine/network setup; the screen still owns its real per-account preference store. */
    private fun appState(account: String) =
        WhiteNoiseAppState(
            context = rule.activity,
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = account,
                        accountIdHex = "a".repeat(64),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = account,
        )

    /** The fixture does not read or modify the application's draft storage. */
    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
