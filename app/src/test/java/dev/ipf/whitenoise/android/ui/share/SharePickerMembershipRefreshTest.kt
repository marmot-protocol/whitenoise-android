package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.advanceLocalDeleteTestRuntime
import dev.ipf.whitenoise.android.state.presentedRow
import dev.ipf.whitenoise.android.state.replaceActiveAccountForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import dev.ipf.whitenoise.android.state.chatRow as nativeChatRow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharePickerMembershipRefreshTest {
    @get:Rule val composeRule = createComposeRule()
    private val states = mutableListOf<WhiteNoiseAppState>()
    private val controllers = mutableListOf<ChatsController>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    @After fun releaseOwners() {
        controllers.forEach(ChatsController::onCleared)
        gates.forEach { it.cancel() }
        states.forEach { it.mutationsScope.cancel() }
    }

    @Test fun activeAccountMembershipReloadIsIncompleteUntilSuccessfulReplacement() {
        val state = emptyAppState().also(states::add)
        val replacement = CompletableDeferred<Unit>().also(gates::add)
        var reads = 0
        val controller =
            controller(state) { account ->
                assertEquals(ACCOUNT_REF, account)
                reads++
                if (reads == 2) {
                    replacement.await()
                    error("replacement read failed")
                }
                listOf(presentedRow(GROUP_B))
            }
        state.attachChatsController(controller)
        val source = mount(state)
        awaitPicker { source()?.targetsComplete == true }
        composeRule.runOnIdle {
            assertTrue(source()!!.targets.any { it.id == GROUP_B })
            removeRetained(controller)
        }
        awaitPicker { reads == 2 && source()?.targetsComplete == false }
        composeRule.runOnIdle {
            assertFalse(source()!!.targets.any { it.id == GROUP_B })
            replacement.complete(Unit)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(source()!!.targetsComplete)
            source()!!.retryLoad()
        }
        awaitPicker { reads == 3 && source()?.targetsComplete == true }
        composeRule.runOnIdle { assertTrue(source()!!.targets.any { it.id == GROUP_B }) }
    }

    @Test fun activeAccountContentUpdatesKeepOffWindowRowsWithoutAnotherFullRead() {
        val state = emptyAppState().also(states::add)
        var reads = 0
        val controller =
            controller(state) {
                reads++
                listOf(presentedRow(GROUP_B))
            }
        state.attachChatsController(controller)
        val source = mount(state)
        awaitPicker { source()?.targetsComplete == true }
        val revisionBeforeUpdate =
            composeRule.runOnIdle {
                val revision = controller.forwardTargetsRevision
                controller.applyChatListRow(nativeChatRow(GROUP_A).copy(manuallyMarkedUnread = true))
                revision
            }
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        composeRule.runOnIdle {
            assertTrue(controller.forwardTargetsRevision > revisionBeforeUpdate)
            assertEquals(1, reads)
            assertTrue(source()!!.targetsComplete)
            assertTrue(source()!!.targets.any { it.id == GROUP_B })
        }
    }

    @Test fun otherAccountReloadCannotReplaceTheActiveAccountAfterSwitch() {
        val state = emptyAppState().also(states::add)
        val replacement = CompletableDeferred<Unit>().also(gates::add)
        val late = CompletableDeferred<Unit>().also(gates::add)
        val reads = mutableListOf<String>()
        val reader: suspend (String) -> List<PresentedChatRowFfi> = { account ->
            reads += account
            if (account == OTHER && reads.count { it == OTHER } == 2) replacement.await()
            if (account == OTHER && reads.count { it == OTHER } == 3) late.await()
            listOf(presentedRow(if (account == OTHER) GROUP_B else GROUP_C))
        }
        val active = controller(state, read = reader)
        state.attachChatsController(active)
        val other = controller(state, OTHER, reader)
        var selected by mutableStateOf<String?>(OTHER)
        val source = mount(state, { selected }, { other })
        awaitPicker { source()?.targetsComplete == true }
        composeRule.runOnIdle {
            assertEquals(listOf(OTHER), reads)
            removeRetained(other)
        }
        awaitPicker { reads.count { it == OTHER } == 2 && source()?.targetsComplete == false }
        composeRule.runOnIdle { replacement.complete(Unit) }
        awaitPicker { source()?.targetsComplete == true && source()!!.targets.any { it.id == GROUP_B } }
        composeRule.runOnIdle { other.applyChatListRow(nativeChatRow(GROUP_A)) }
        awaitPicker { reads.count { it == OTHER } == 3 && source()?.targetsComplete == false }
        composeRule.runOnIdle { selected = ACCOUNT_REF }
        awaitPicker { source()?.targetsComplete == true && source()!!.targets.any { it.id == GROUP_C } }
        composeRule.runOnIdle { late.complete(Unit) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(source()!!.targets.any { it.id == GROUP_B })
            assertTrue(source()!!.targets.any { it.id == GROUP_C })
            assertEquals(ACCOUNT_REF, reads.last())
        }
    }

    @Test fun defaultActiveSourceWithOldBindingCannotReadOrExposeAnotherAccountsRows() {
        val state =
            emptyAppState(
                accounts = listOf(testAccount(ACCOUNT_REF, ACCOUNT_HEX), testAccount(OTHER, OTHER_HEX)),
            ).also(states::add)
        val reads = mutableListOf<String>()
        val reader: suspend (String) -> List<PresentedChatRowFfi> = { account ->
            reads += account
            listOf(presentedRow(if (account == OTHER) GROUP_C else GROUP_B))
        }
        val old = controller(state, ACCOUNT_REF, reader)
        state.attachChatsController(old)
        val source = mount(state, { state.activeAccountRef })
        awaitPicker { source()?.targetsComplete == true }
        composeRule.runOnIdle {
            assertTrue(source()!!.targets.any { it.id == GROUP_B })
            reads.clear()
            state.replaceActiveAccountForTest(OTHER)
        }
        composeRule.runOnIdle {
            assertTrue(state.forwardTargets().isNotEmpty())
            assertTrue(source()!!.targets.isEmpty())
            assertFalse(source()!!.targetsComplete)
            assertTrue(source()!!.isLoading)
            assertTrue(reads.isEmpty())
            state.advanceLocalDeleteTestRuntime()
            state.attachChatsController(controller(state, OTHER, reader))
        }
        awaitPicker { source()?.targetsComplete == true && source()!!.targets.any { it.id == GROUP_C } }
        composeRule.runOnIdle {
            assertEquals(listOf(OTHER), reads)
            assertFalse(source()!!.targets.any { it.id == GROUP_B })
            assertTrue(source()!!.targets.any { it.id == GROUP_A })
        }
    }

    private fun controller(
        state: WhiteNoiseAppState,
        account: String = ACCOUNT_REF,
        read: suspend (String) -> List<PresentedChatRowFfi>,
    ): ChatsController {
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("no live window") },
                openChats = { _, _ -> error("no live feed") },
                presentedChatList = { owner, _ -> read(owner) },
            )
        return ChatsController(state, account) { _, _ -> emptyList() }.also {
            controllers += it
            it.applyChatListRow(nativeChatRow(GROUP_A))
        }
    }

    private fun mount(
        state: WhiteNoiseAppState,
        selected: () -> String? = { ACCOUNT_REF },
        factory: (WhiteNoiseAppState) -> ChatsController = { error("active controller owns this read") },
    ): () -> ShareChatPickerDataSource? {
        var source: ShareChatPickerDataSource? = null
        composeRule.setContent {
            val current = rememberShareChatPickerDataSource(state, selected(), factory, { _, _ -> })
            SideEffect { source = current }
        }
        return { source }
    }

    private fun removeRetained(controller: ChatsController) {
        assertTrue(controller.containsGroup(GROUP_A))
        controller.applyChatListSubscriptionUpdate(
            checkNotNull(controller.accountRef),
            ChatListSubscriptionUpdateFfi.RemoveRow(ChatListUpdateTriggerFfi.REMOVED, GROUP_A),
        )
        assertFalse(controller.containsGroup(GROUP_A))
    }

    private fun awaitPicker(ready: () -> Boolean) {
        composeRule.waitUntil {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            composeRule.runOnIdle(ready)
        }
    }

    private companion object {
        const val OTHER = "picker-other-account"
        val GROUP_C = "c1".repeat(32)
        val OTHER_HEX = "f1".repeat(32)
    }
}
