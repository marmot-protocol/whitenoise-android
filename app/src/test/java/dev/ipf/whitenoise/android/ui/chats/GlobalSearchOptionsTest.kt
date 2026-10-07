package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ProfilePresentationRevision
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real async options owner, including a refresh overlapping an account switch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchOptionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun ordinaryRepublishKeepsChoicesAndAccountChangeDropsThemImmediately() {
        val row = leftScopeRow("aa")
        val scope = mutableStateOf(filterScope("personal", listOf(row)))
        val revision = mutableStateOf(ProfilePresentationRevision(0, 0))
        val calls = AtomicInteger()
        val finishRefresh = CompletableDeferred<Unit>()
        var visible = GlobalSearchFilterOptions()
        composeRule.setContent {
            val options =
                rememberProjectedGlobalSearchFilterOptions(scope.value, revision.value) { input ->
                    if (calls.incrementAndGet() > 1) finishRefresh.await()
                    GlobalSearchFilterOptions(
                        senders = listOf(WhiteNoisePickerItem(input.selfId.orEmpty(), input.selfLabel)),
                    )
                }
            SideEffect { visible = options }
        }
        composeRule.waitUntil(OPTIONS_TIMEOUT_MILLIS) { !visible.loading && visible.senders.isNotEmpty() }
        composeRule.runOnIdle {
            // An ordinary activity/unread republish is not a picker dependency.
            val republished =
                row.copy(
                    hasOptimisticSendPreview = true,
                    group =
                        row.group.copy(
                            description = "Updated non-picker metadata",
                            relays = listOf("wss://relay.test"),
                        ),
                )
            scope.value = scope.value.copy(chatChoices = listOf(republished), senderChats = listOf(republished))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, calls.get()) }
        composeRule.runOnIdle { revision.value = ProfilePresentationRevision(1, 0) }
        composeRule.waitUntil(OPTIONS_TIMEOUT_MILLIS) { calls.get() == 2 }
        composeRule.runOnIdle {
            assertTrue(visible.loading)
            assertEquals("personal-self", visible.senders.single().id)
        }
        composeRule.runOnIdle { scope.value = filterScope("work", listOf(row)) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(visible.senders.isEmpty()) }
        finishRefresh.complete(Unit)
        composeRule.waitUntil(OPTIONS_TIMEOUT_MILLIS) {
            !visible.loading && visible.senders.singleOrNull()?.id == "work-self"
        }
    }

    @Test
    fun closeAndReopenCannotReuseAnOldProjectionWhileTheNewOneLoads() {
        val scope = filterScope("personal", listOf(leftScopeRow("aa")))
        val enabled = mutableStateOf(true)
        val calls = AtomicInteger()
        val finishRefresh = CompletableDeferred<Unit>()
        var visible = GlobalSearchFilterOptions()
        composeRule.setContent {
            val options =
                rememberProjectedGlobalSearchFilterOptions(
                    scope,
                    ProfilePresentationRevision(0, 0),
                    enabled.value,
                ) {
                    if (calls.incrementAndGet() > 1) finishRefresh.await()
                    GlobalSearchFilterOptions(senders = listOf(WhiteNoisePickerItem("person", "Alice")))
                }
            SideEffect { visible = options }
        }
        composeRule.waitUntil(OPTIONS_TIMEOUT_MILLIS) { !visible.loading && visible.senders.isNotEmpty() }
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { enabled.value = true }
        composeRule.waitUntil(OPTIONS_TIMEOUT_MILLIS) { calls.get() == 2 }
        composeRule.runOnIdle { assertTrue(visible.loading && visible.senders.isEmpty()) }
        finishRefresh.complete(Unit)
        composeRule.waitUntil(OPTIONS_TIMEOUT_MILLIS) { !visible.loading && visible.senders.isNotEmpty() }
    }

    private fun filterScope(
        account: String,
        chats: List<dev.ipf.whitenoise.android.state.ChatListItem>,
    ) = GlobalSearchFilterScope(
        folders = emptyList(),
        chatChoices = chats,
        senderChats = chats,
        titleCopy = GroupTitleCopy.Default,
        selfId = "$account-self",
        selfLabel = "You",
        accountRef = account,
        accountScope = account,
    )

    private companion object {
        const val OPTIONS_TIMEOUT_MILLIS = 5_000L
    }
}
