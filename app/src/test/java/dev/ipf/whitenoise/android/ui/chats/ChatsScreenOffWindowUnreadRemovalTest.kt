package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.LOCAL_DELETE_ACCOUNT
import dev.ipf.whitenoise.android.state.LOCAL_DELETE_GROUP
import dev.ipf.whitenoise.android.state.LocalGroupDeleteFixture
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.localGroupDeleteFixture
import dev.ipf.whitenoise.android.state.presentedRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsScreenOffWindowUnreadRemovalTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val controllers = mutableListOf<ChatsController>()

    @org.junit.After fun closeControllers() {
        composeRule.runOnIdle { controllers.forEach(ChatsController::onCleared) }
    }

    @Test fun offWindowDeleteRefreshWaitsForNativeCommitAndSettlesWithoutDeletedRow() {
        val nativeScope = TestScope()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        var deletion: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            val fixture = nativeScope.heldRemovalFixture(entered, release) { reads++ }
            prepareOffWindowRow(fixture.controller)
            composeRule.setContent {
                WhiteNoiseTheme { ChatsScreen(fixture.state, fixture.controller, {}, { _, _, _, _ -> }) }
            }
            composeRule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(composeRule)
            awaitNativeProgress(nativeScope) { reads == 1 }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$LOCAL_DELETE_GROUP").assertExists()
            val pending = nativeScope.async { fixture.controller.deleteGroupLocalFromChatList(LOCAL_DELETE_GROUP) }
            deletion = pending
            awaitNativeProgress(nativeScope) { entered.isCompleted }
            assertTrue(fixture.state.localGroupDeleteCleanupMutex.isLocked)
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$LOCAL_DELETE_GROUP").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.$SURVIVOR").assertExists()
            composeRule.onNodeWithTag("chats.unread_refresh").assertExists()
            release.complete(Unit)
            awaitNativeProgress(nativeScope) { pending.isCompleted }
            assertTrue(pending.getCompleted())
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            awaitNativeProgress(nativeScope) { reads >= 2 }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chat.row.$LOCAL_DELETE_GROUP").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.$SURVIVOR").assertExists()
            composeRule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        } finally {
            release.complete(Unit)
            deletion?.cancel()
            try {
                awaitNativeProgress(nativeScope) { deletion?.isCompleted != false }
            } finally {
                nativeScope.cancel()
            }
        }
    }

    /** Advances the native fixture scheduler at Compose's blocking test boundaries. */
    private fun awaitNativeProgress(
        scope: TestScope,
        ready: () -> Boolean,
    ) {
        composeRule.waitUntil {
            scope.testScheduler.runCurrent()
            ready()
        }
    }

    private fun TestScope.heldRemovalFixture(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        onRead: () -> Unit,
    ): LocalGroupDeleteFixture =
        localGroupDeleteFixture(
            controllers,
            beforeDelete = {
                entered.complete(Unit)
                release.await()
            },
            configureState = { state, present ->
                state.liveSubscriptionOverrides.chatList =
                    ChatListLiveSubscriptions(
                        openChatListWindow = { _, _ -> error("not used") },
                        openChats = { _, _ -> error("not used") },
                        presentedChatList = { _, _ ->
                            onRead()
                            val ids = if (present.get()) listOf(LOCAL_DELETE_GROUP, SURVIVOR) else listOf(SURVIVOR)
                            ids.map { id ->
                                presentedRow(id).let { it.copy(row = it.row.copy(manuallyMarkedUnread = true)) }
                            }
                        },
                    )
            },
        )

    private fun prepareOffWindowRow(controller: ChatsController) {
        controller.applyChatListSubscriptionUpdate(
            LOCAL_DELETE_ACCOUNT,
            dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi.RemoveRow(
                dev.ipf.marmotkit.ChatListUpdateTriggerFfi.REMOVED,
                LOCAL_DELETE_GROUP,
            ),
        )
        controller.applyChatListRow(chatRow(SURVIVOR).copy(manuallyMarkedUnread = true))
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
    }

    private companion object {
        val SURVIVOR = "c1".repeat(16)
    }
}
