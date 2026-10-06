package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.presentedRow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Completeness belongs to the existing account read, independently of the retained local window. */
@RunWith(RobolectricTestRunner::class)
class DestinationPickerCompletenessTest {
    @get:Rule val composeRule = createComposeRule()

    /** A cold off-window participant is unknown until its bounded account-owned roster read resolves. */
    @Test
    @Suppress("LongMethod") // One mounted picker is observed before and after the deferred roster lands.
    fun offWindowParticipantFolderConvergesAfterRosterHydration() {
        val state = emptyAppState()
        val roster = CompletableDeferred<Unit>()
        val updatedRoster = CompletableDeferred<Unit>()
        val reads = mutableListOf<Pair<String, String>>()
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("no live window") },
                openChats = { _, _ -> error("no live feed") },
                presentedChatList = { _, _ -> listOf(presentedRow(GROUP_B)) },
            )
        val controller =
            ChatsController(
                state,
                initialAccountRef = ACCOUNT_REF,
                initialLocalSnapshot =
                    AccountSwitchLocalSnapshot(
                        ACCOUNT_REF,
                        ACCOUNT_HEX,
                        emptyList(),
                        listOf(group(GROUP_B)),
                        emptyList(),
                        emptyList(),
                    ),
                memberSnapshotLoader = { account, group ->
                    reads += account to group
                    if (reads.size == 1) roster.await() else updatedRoster.await()
                    listOf(member(ACCOUNT_HEX, true), member(if (reads.size == 1) PEER_B else PEER_A, false))
                },
            )
        state.attachChatsController(controller)
        val store = state.chatFolderPreferences
        store.clearAllForAccount(ACCOUNT_REF)
        store.foldersFor(ACCOUNT_REF)
        val folder =
            requireNotNull(
                store.commitFolderDraft(
                    ACCOUNT_REF,
                    null,
                    "People",
                    "",
                    emptySet(),
                    ChatFolderRule(includeMemberPubkeys = setOf(PEER_B)),
                ),
            )
        var complete = false
        var matches = emptyList<String>()
        var source: ShareChatPickerDataSource? = null
        composeRule.setContent {
            val current = rememberShareChatPickerDataSource(state, ACCOUNT_REF, { error("active") }, { _, _ -> })
            val folders =
                rememberDestinationFolderRows(
                    state,
                    current.targets,
                    ACCOUNT_REF,
                    ACCOUNT_HEX,
                    current.memberSnapshotsRevision,
                )
            val inputsComplete =
                destinationFolderInputsComplete(
                    state,
                    current.targets,
                    ACCOUNT_REF,
                    folder.id,
                    folders,
                )
            SideEffect {
                source = current
                complete = current.targetsComplete && inputsComplete
                matches = folders.single { it.first.id == folder.id }.second
            }
        }
        composeRule.waitUntil(5_000) { source?.targetsComplete == true && reads.isNotEmpty() }
        composeRule.runOnIdle {
            assertFalse(complete)
            assertTrue(matches.isEmpty())
            assertTrue(source!!.targets.any { it.group.groupIdHex == GROUP_B })
            roster.complete(Unit)
        }
        composeRule.waitUntil(5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            complete && GROUP_B in matches
        }
        composeRule.runOnIdle {
            assertEquals(listOf(ACCOUNT_REF to GROUP_B), reads)
            assertFalse(controller.containsGroup(GROUP_B))
            // This follows the same fold/invalidation path as a live group update after hydration ended.
            controller.applyLocalGroupUpdate(group(GROUP_B))
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(100))
        }
        composeRule.waitUntil(5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            reads.size == 2 && !complete
        }
        composeRule.runOnIdle { updatedRoster.complete(Unit) }
        composeRule.waitUntil(5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            complete && matches.isEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(listOf(ACCOUNT_REF to GROUP_B, ACCOUNT_REF to GROUP_B), reads)
            assertFalse(controller.containsGroup(GROUP_B))
            val members = source!!.targets.single { it.group.groupIdHex == GROUP_B }.memberSnapshot!!
            assertTrue(PEER_A in members.foldedMemberIds)
            assertFalse(PEER_B in members.foldedMemberIds)
        }
    }

    /** A failed retry keeps previously chosen off-window destinations without claiming a complete folder. */
    @Test
    fun failedRetryRetainsSameAccountTargetsAndReportsIncomplete() {
        val state = emptyAppState()
        var failRead = false
        var reads = 0
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("No live window in this fixture") },
                openChats = { _, _ -> error("No live feed in this fixture") },
                presentedChatList = { _, _ ->
                    reads += 1
                    check(!failRead)
                    listOf(presentedRow(GROUP_B))
                },
            )
        val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyLocalDirectChat(GROUP_A, ACCOUNT_HEX, PEER_A)
        state.attachChatsController(controller)
        var source: ShareChatPickerDataSource? = null
        composeRule.setContent {
            val current = rememberShareChatPickerDataSource(state, ACCOUNT_REF, { error("active owner") }, { _, _ -> })
            SideEffect { source = current }
        }
        composeRule.waitUntil(5_000) { source?.targetsComplete == true }
        composeRule.runOnIdle {
            assertTrue(source!!.targets.any { it.group.groupIdHex == GROUP_B })
            failRead = true
            source!!.retryLoad()
        }
        composeRule.waitUntil(5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            reads >= 2 && source?.targetsComplete == false
        }
        composeRule.runOnIdle {
            assertFalse(source!!.targetsComplete)
            assertTrue(source!!.targets.any { it.group.groupIdHex == GROUP_B })
        }
    }
}
