package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A chat link resolves in the order the user navigated (#4186). An in-app lookup that finishes after a newer
 * notification route, or after a newer in-app link, drops its result. A newer route also clears a pending account
 * chooser, and a chat link never swaps the shell for the root loading screen while it resolves.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLinkRouteOrderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A notification tap that arrives while an in-app chat link reads its holders wins the screen. */
    @Test
    fun notificationRouteDuringLookupDropsTheLinkResult() =
        runTest {
            var routeGeneration = 4L
            val lookups = InAppChatLinkLookups(this) { routeGeneration }
            val holdersRead = CompletableDeferred<ChatLinkOutcome>()
            val applied = mutableListOf<ChatLinkOutcome>()

            lookups.launch(resolve = { holdersRead.await() }, apply = applied::add)
            runCurrent()
            routeGeneration += 1
            holdersRead.complete(OPEN)
            runCurrent()

            assertTrue(applied.isEmpty())
        }

    /** A lookup that finishes while no newer route arrived applies its outcome once. */
    @Test
    fun currentLookupAppliesItsOutcome() =
        runTest {
            val lookups = InAppChatLinkLookups(this) { 4L }
            val applied = mutableListOf<ChatLinkOutcome>()

            lookups.launch(resolve = { OPEN }, apply = applied::add)
            runCurrent()

            assertEquals(listOf(OPEN), applied)
        }

    /** A second in-app link supersedes the first lookup, so only the newer link's outcome lands. */
    @Test
    fun newerInAppLinkSupersedesTheOlderLookup() =
        runTest {
            val lookups = InAppChatLinkLookups(this) { 4L }
            val firstRead = CompletableDeferred<ChatLinkOutcome>()
            val applied = mutableListOf<ChatLinkOutcome>()

            lookups.launch(resolve = { firstRead.await() }, apply = applied::add)
            runCurrent()
            lookups.launch(resolve = { NOTICE }, apply = applied::add)
            firstRead.complete(OPEN)
            runCurrent()

            assertEquals(listOf(NOTICE), applied)
        }

    /** A newer notification route closes the account chooser, while the first composition keeps a restored one. */
    @Test
    fun newerRouteClearsThePendingChooser() {
        var routeGeneration by mutableLongStateOf(7L)
        var choice by mutableStateOf<ChatLinkAccountChoice?>(ChatLinkAccountChoice("group", listOf("a", "b")))
        composeRule.setContent {
            ChatLinkChoiceSupersededEffect(routeGeneration) { choice = null }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("a", "b"), choice?.accountRefs)

        routeGeneration += 1
        composeRule.waitForIdle()

        assertNull(choice)
    }

    /** Only a non-chat payload takes over the root with the loading screen while it resolves. */
    @Test
    fun onlyNonChatPayloadsShowTheRootLoadingScreen() {
        assertFalse("marmot://chat/0123456789abcdef0123456789abcdef".showsRootLoading())
        assertFalse("marmot://chat/not-hex".showsRootLoading())
        assertFalse((null as String?).showsRootLoading())
        assertTrue(PROFILE_LINK.showsRootLoading())
    }

    private companion object {
        val OPEN = ChatLinkOutcome.Open(chatLinkTarget("account", "group"))
        val NOTICE = ChatLinkOutcome.Notice(R.string.chat_link_not_found)
        const val PROFILE_LINK = "marmot://profile/npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
    }
}
