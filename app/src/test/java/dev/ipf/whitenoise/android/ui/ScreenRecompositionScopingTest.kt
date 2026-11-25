package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.chats.ChatFolderChipModel
import dev.ipf.whitenoise.android.ui.chats.ChatFolderPills
import dev.ipf.whitenoise.android.ui.chats.ChatsScreen
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchViewport
import dev.ipf.whitenoise.android.ui.chats.rememberGlobalSearchViewport
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.profile.PersonProfileBottomAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies recomposition scoping on the real [ChatsScreen], [WhiteNoiseApp], [ChatFolderPills],
 * and [PersonProfileBottomAction] composables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ScreenRecompositionScopingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Ensures no composition tracer remains installed after each test. */
    @After
    @OptIn(InternalComposeTracingApi::class)
    fun clearTracer() {
        Composer.setTracer(null)
    }

    /**
     * Programmatic scroll-offset changes within row 0 (via `requestScrollToItem`, outside a drag gesture)
     * cause zero ChatsScreen body recompositions.
     */
    @Test
    fun programmaticOffsetChangeWithinOneRowDoesNotRecomposeChatsScreen() {
        val appState = createChatsAppState()
        val controller = ChatsController(appState)
        controller.items = (0 until CHAT_ROW_COUNT).map(::chatItem)
        lateinit var searchViewport: GlobalSearchViewport
        try {
            composeRule.setContent {
                val viewport = rememberGlobalSearchViewport()
                searchViewport = viewport
                WhiteNoiseTheme {
                    Surface {
                        ChatsScreen(
                            appState = appState,
                            controller = controller,
                            onOpenSettings = {},
                            onOpenGroup = { _, _, _, _ -> },
                            searchViewport = viewport,
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("chats.list").assertExists()

            val listState = searchViewport.listState(showArchived = false)
            val counts = mutableMapOf<String, Int>()
            val screenRuns =
                countComposableRuns("ChatsScreen", counts) {
                    for (offsetPx in PIXEL_SCROLL_OFFSETS) {
                        composeRule.runOnIdle { listState.requestScrollToItem(0, offsetPx) }
                        composeRule.waitForIdle()
                    }
                }

            assertEquals("Offset changes within row 0 recomposed ChatsScreen: $counts", 0, screenRuns)
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(PIXEL_SCROLL_OFFSETS.last(), listState.firstVisibleItemScrollOffset)
        } finally {
            composeRule.runOnIdle { controller.onCleared() }
            cancelAppStateScope(appState)
        }
    }

    /** A full keyboard open/close animation recomposes WhiteNoiseApp at most twice. */
    @Test
    fun imeAnimationFramesRecomposeWhiteNoiseAppAtMostTwice() {
        val pendingAttempt = CompletableDeferred<Unit>()
        val appState = createRootAppState(pendingAttempt)
        val shell = MainShellStateHolder(appState, SavedStateHandle())
        lateinit var rootView: View
        try {
            composeRule.setContent {
                rootView = LocalView.current
                WhiteNoiseTheme { WhiteNoiseApp(appState, shell, 0, 0) }
            }
            composeRule.mainClock.advanceTimeBy(BOOTSTRAP_SETTLE_MS)
            composeRule.waitForIdle()
            dispatchWindowInsets(rootView, imeBottomPx = 0)

            val counts = mutableMapOf<String, Int>()
            val appRuns =
                countComposableRuns("WhiteNoiseApp", counts) {
                    for (bottomPx in IME_ANIMATION_FRAMES) {
                        dispatchWindowInsets(rootView, imeBottomPx = bottomPx)
                    }
                }

            assertTrue("Expected IME visibility transitions to reach WhiteNoiseApp ($counts)", appRuns > 0)
            assertTrue(
                "Expected at most 2 WhiteNoiseApp recompositions for open+close, got $appRuns ($counts)",
                appRuns <= 2,
            )
        } finally {
            pendingAttempt.complete(Unit)
            composeRule.runOnIdle { shell.release() }
            cancelAppStateScope(appState)
        }
    }

    /** ChatFolderPills ignores header overlap deltas on the same side of its scroll threshold. */
    @Test
    fun chatFolderPillsRecomposesOnlyWhenCrossingScrollThreshold() {
        lateinit var scrollState: TopAppBarState
        val chips =
            listOf(
                ChatFolderChipModel("f1", null, "Work", 0),
                ChatFolderChipModel("f2", null, "Friends", 1),
            )
        composeRule.setContent {
            val state = rememberTopAppBarState(initialHeightOffsetLimit = -100f)
            scrollState = state
            val behavior = TopAppBarDefaults.pinnedScrollBehavior(state)
            WhiteNoiseTheme {
                Surface {
                    CompositionLocalProvider(LocalWhiteNoiseHeaderScroll provides behavior) {
                        ChatFolderPills(
                            chips = chips,
                            selectedFolderId = null,
                            onSelect = {},
                            onEditFolder = {},
                            onFolders = null,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val belowThresholdRuns =
            countComposableRuns("ChatFolderPills") {
                for (offset in listOf(-0.2f, -0.5f, -0.8f)) {
                    composeRule.runOnIdle { scrollState.contentOffset = offset }
                    composeRule.waitForIdle()
                }
            }
        assertEquals("Below-threshold scroll deltas recomposed ChatFolderPills", 0, belowThresholdRuns)

        val crossThresholdRuns =
            countComposableRuns("ChatFolderPills") {
                composeRule.runOnIdle { scrollState.contentOffset = -20f }
                composeRule.waitForIdle()
            }
        assertTrue("Crossing the scroll threshold must recompose ChatFolderPills", crossThresholdRuns > 0)

        val aboveThresholdRuns =
            countComposableRuns("ChatFolderPills") {
                for (offset in listOf(-40f, -60f, -80f, -100f)) {
                    composeRule.runOnIdle { scrollState.contentOffset = offset }
                    composeRule.waitForIdle()
                }
            }
        assertEquals("Above-threshold scroll deltas recomposed ChatFolderPills", 0, aboveThresholdRuns)
    }

    /** PersonProfileBottomAction ignores header overlap deltas until crossing the scrolled boundary. */
    @Test
    fun personProfileBottomActionRecomposesOnlyWhenCrossingScrollThreshold() {
        lateinit var scrollState: TopAppBarState
        composeRule.setContent {
            val state = rememberTopAppBarState(initialHeightOffsetLimit = -100f, initialContentOffset = -20f)
            scrollState = state
            val behavior = TopAppBarDefaults.pinnedScrollBehavior(state)
            WhiteNoiseTheme {
                Surface {
                    CompositionLocalProvider(LocalWhiteNoiseHeaderScroll provides behavior) {
                        PersonProfileBottomAction(
                            label = "Message",
                            onClick = {},
                            enabled = true,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val sameSideRuns =
            countComposableRuns("PersonProfileBottomAction") {
                for (offset in listOf(-40f, -60f, -80f, -100f)) {
                    composeRule.runOnIdle { scrollState.contentOffset = offset }
                    composeRule.waitForIdle()
                }
            }
        assertEquals("Same-side scroll deltas recomposed PersonProfileBottomAction", 0, sameSideRuns)

        val crossRuns =
            countComposableRuns("PersonProfileBottomAction") {
                composeRule.runOnIdle { scrollState.contentOffset = 0f }
                composeRule.waitForIdle()
            }
        assertTrue("Crossing back to unscrolled must recompose PersonProfileBottomAction", crossRuns > 0)
    }

    /** Dispatches scripted navigation-bar and IME bottom insets to [rootView] and settles composition. */
    private fun dispatchWindowInsets(
        rootView: View,
        imeBottomPx: Int,
    ) {
        composeRule.runOnIdle {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR_BOTTOM_PX))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), imeBottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(rootView.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** Creates an active-account [WhiteNoiseAppState] for mounting [ChatsScreen] without native startup. */
    private fun createChatsAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence),
            accountIdHexResolver = { null },
            accounts = listOf(activeAccount()),
            activeAccountRef = ACCOUNT_REF,
        )

    /** Creates an onboarding-phase [WhiteNoiseAppState] whose seeded bootstrap attempt avoids native startup. */
    private fun createRootAppState(pendingAttempt: CompletableDeferred<Unit>): WhiteNoiseAppState {
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(InMemoryDraftPersistence),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                bootstrapActionableTimeoutMillis = { 10L },
                marmotRuntimeFactory = { error("native bootstrap must reuse the seeded attempt") },
            )
        appState.markDefaultNotificationsEnableAttempted()
        setAppPhase(appState, AppPhase.Onboarding)
        runBlocking { appState.accountSession.bootstrapAttempts.currentOrStart { pendingAttempt } }
        return appState
    }

    /** Returns the running active account summary for [ChatsScreen]. */
    private fun activeAccount(): AccountSummaryFfi =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
            unlinked = false,
        )

    /** Builds a distinct non-archived chat row for [ChatsScreen] pixel-scroll testing. */
    private fun chatItem(index: Int): ChatListItem {
        val prefix = index.toString(16).padStart(2, '0')
        val groupIdHex = prefix.repeat(32)
        val group =
            conversationTimelineTestGroup().copy(
                groupIdHex = groupIdHex,
                nostrGroupIdHex = "nostr-$groupIdHex",
                endpoint = "endpoint-$groupIdHex",
                name = "Group $index",
            )
        return ChatListItem(
            group = group,
            latest = null,
            otherMemberAccount = null,
            memberCount = 2,
            memberSnapshot = null,
            projection = chatRow(groupIdHex).copy(title = "Group $index", groupName = "Group $index"),
        )
    }

    /** Sets [WhiteNoiseAppState.phase] so [WhiteNoiseApp] mounts directly into a settled destination. */
    private fun setAppPhase(
        appState: WhiteNoiseAppState,
        phase: AppPhase,
    ) {
        val setter = WhiteNoiseAppState::class.java.getDeclaredMethod("setPhase", AppPhase::class.java)
        setter.isAccessible = true
        setter.invoke(appState, phase)
    }

    /** Cancels background mutation coroutines owned by a test [WhiteNoiseAppState]. */
    private fun cancelAppStateScope(appState: WhiteNoiseAppState) {
        appState.mutationsScope.coroutineContext[Job]?.cancel()
    }

    /** Counts executions of the composable named [targetName] during [block]. */
    @OptIn(InternalComposeTracingApi::class)
    private fun countComposableRuns(
        targetName: String,
        allSimpleCounts: MutableMap<String, Int>? = null,
        block: () -> Unit,
    ): Int {
        var runs = 0
        Composer.setTracer(
            object : CompositionTracer {
                /** Traces all composable executions while installed. */
                override fun isTraceInProgress() = true

                /** Increments counts when a composable whose simple name matches [targetName] runs. */
                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    val simpleName = info.substringBefore(" (").substringAfterLast('.')
                    allSimpleCounts?.let { it[simpleName] = it.getOrDefault(simpleName, 0) + 1 }
                    if (simpleName == targetName) runs++
                }

                /** Scope exits need no bookkeeping. */
                override fun traceEventEnd() = Unit
            },
        )
        try {
            block()
        } finally {
            Composer.setTracer(null)
        }
        return runs
    }

    private companion object {
        const val ACCOUNT_REF = "account"
        const val ACCOUNT_HEX = "a"
        const val CHAT_ROW_COUNT = 20
        const val NAV_BAR_BOTTOM_PX = 48
        const val BOOTSTRAP_SETTLE_MS = 50L
        val PIXEL_SCROLL_OFFSETS = listOf(4, 12, 24, 36, 48)
        val IME_ANIMATION_FRAMES = listOf(10, 30, 48, 60, 120, 240, 420, 600, 420, 240, 120, 60, 48, 24, 0)
    }
}

/** No-op draft persistence keeping test drafts strictly in memory. */
private object InMemoryDraftPersistence : DraftPersistence {
    /** Returns no persisted drafts at startup. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores persistence writes in the in-memory test store. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
