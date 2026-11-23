package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
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

/** Recent chats on the blank search page (#4004), read by ID even when the Chats window never loaded them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchRecentStripTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private var fixture: OffWindowActionFixture? = null
    private val search = mutableStateOf(GlobalSearchState())
    private val opened = mutableListOf<String>()

    @After fun close() {
        val model = fixture
        try {
            settle()
        } finally {
            rule.runOnIdle {
                model?.state?.mutationsScope?.cancel()
                model?.controller?.onCleared()
            }
            try {
                awaitProgress {
                    model
                        ?.state
                        ?.mutationsScope
                        ?.coroutineContext
                        ?.get(Job)
                        ?.isCompleted != false
                }
            } finally {
                scope.cancel()
            }
        }
    }

    /** A chat outside the loaded page reopens through the chat route and moves to the front. */
    @Test fun recentChatOutsideTheLoadedWindowReopens() {
        val (model, recents) = mount()

        awaitProgress { tileExists(ACTION_TARGET) }
        rule.onNodeWithTag(tag(ACTION_TARGET)).performClick()
        awaitProgress { opened.isNotEmpty() }

        assertEquals(listOf(ACTION_TARGET), opened)
        assertEquals(GlobalSearchRecent.Chat(ACTION_TARGET), recents.entries.first())
        assertFalse("reading a recent must not grow the window", model.controller.containsGroup(ACTION_TARGET))
    }

    /** A chat that can no longer be read is left out, and the others still show. */
    @Test fun unreadableRecentChatIsOmitted() {
        mount { it.targetReadUnavailable = true }

        awaitProgress { tileExists(ACTION_SURVIVOR) && requireNotNull(fixture).keyedReads > 0 }
        settle()

        rule.onNodeWithTag(tag(ACTION_TARGET)).assertDoesNotExist()
    }

    /** A chat that stopped being readable after it was shown leaves the list on tap instead of opening. */
    @Test fun recentChatUnreadableAtTapIsDroppedNotOpened() {
        val (model, recents) = mount()
        awaitProgress { tileExists(ACTION_TARGET) }
        model.targetReadUnavailable = true

        rule.onNodeWithTag(tag(ACTION_TARGET)).performClick()
        awaitProgress { recents.entries.size == 1 }
        settle()

        assertTrue(opened.isEmpty())
        assertEquals(listOf(GlobalSearchRecent.Chat(ACTION_SURVIVOR)), recents.entries)
        rule.onNodeWithTag(tag(ACTION_TARGET)).assertDoesNotExist()
    }

    /** Remove drops one destination for screen readers too; Clear empties the list and hides it. */
    @Test fun removeAndClearEmptyTheList() {
        val (_, recents) = mount()
        awaitProgress { tileExists(ACTION_TARGET) && tileExists(ACTION_SURVIVOR) }
        val remove = rule.activity.getString(R.string.chat_list_search_recent_remove)

        rule.runOnIdle {
            rule
                .onNodeWithTag(tag(ACTION_SURVIVOR))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == remove }
                .action()
        }
        settle()
        rule.onNodeWithTag(tag(ACTION_SURVIVOR)).assertDoesNotExist()
        assertEquals(listOf(GlobalSearchRecent.Chat(ACTION_TARGET)), recents.entries)

        rule.onNodeWithTag(GLOBAL_SEARCH_RECENTS_CLEAR_TAG).performClick()
        settle()
        assertTrue(recents.entries.isEmpty())
        rule.onNodeWithTag(GLOBAL_SEARCH_RECENTS_TAG).assertDoesNotExist()
    }

    /** Closing search hides the list without forgetting it. */
    @Test fun listIsHiddenOutsideTheBlankSearchPage() {
        val (_, recents) = mount()
        awaitProgress { tileExists(ACTION_TARGET) }

        rule.runOnIdle { search.value = search.value.copy(isOpen = false) }
        settle()

        rule.onNodeWithTag(GLOBAL_SEARCH_RECENTS_TAG).assertDoesNotExist()
        assertEquals(2, recents.entries.size)
    }

    private fun mount(prepare: (OffWindowActionFixture) -> Unit = {}): Pair<OffWindowActionFixture, GlobalSearchRecents> {
        val model = OffWindowActionFixture(scope)
        fixture = model
        prepare(model)
        val token = GlobalSearchAccountScope.from(ACTION_ACCOUNT, model.state.runtimeGeneration).encodeToken()
        val recents = GlobalSearchRecents(token)
        recents.record(token, GlobalSearchRecent.Chat(ACTION_SURVIVOR))
        recents.record(token, GlobalSearchRecent.Chat(ACTION_TARGET))
        search.value = GlobalSearchState(isOpen = true, accountScopeToken = token)
        settle()
        rule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(
                    model.state,
                    model.controller,
                    {},
                    { item, _, _, _ -> opened += item.group.groupIdHex },
                    globalSearchState = search.value,
                    searchRecents = recents,
                )
            }
        }
        settle()
        return model to recents
    }

    private fun tag(groupIdHex: String) = globalSearchRecentTag(GlobalSearchRecent.Chat(groupIdHex))

    private fun tileExists(groupIdHex: String): Boolean =
        rule.onAllNodes(hasTestTag(tag(groupIdHex))).fetchSemanticsNodes().isNotEmpty()

    private fun awaitProgress(ready: () -> Boolean) {
        rule.waitUntil(WAIT_MILLIS) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            scope.testScheduler.runCurrent()
            ready()
        }
    }

    private fun settle() {
        scope.testScheduler.runCurrent()
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.waitForIdle()
        scope.testScheduler.runCurrent()
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        rule.waitForIdle()
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
